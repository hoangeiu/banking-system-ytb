# Production Readiness & Refactoring TODO (`transaction-service`)

> **Mục tiêu:** Nâng cấp `transaction-service` từ phiên bản cơ bản (course baseline) lên chuẩn **Production Fintech/Banking** (Java 21, Spring Boot, PostgreSQL, Apache Kafka, Redis).
> **Chiến lược:** Hoàn thành luồng end-to-end của khóa học trước, sau đó refactor lần lượt theo mức độ ưu tiên từ `P0` → `P4`.

---

## P0 - Build & Startup Blockers (Cần xử lý trước khi chạy)

- [ ] **Sửa lệch kiểu khóa chính ở `TransactionRepository.java`:** Đổi `JpaRepository<Transaction, Long>` thành `JpaRepository<Transaction, String>` (và sau này chuyển sang `UUID` ở mục P3) để tránh lỗi lúc startup.
- [ ] **Hoàn thiện các method chưa implement trong `TransactionService.java`:** `getTransaction`, `getTransactionHistory`, `verifyOTP` (đang được gọi từ `TransactionController.java`).
- [ ] **Bật OpenFeign & cấu hình properties:** Thêm `@EnableFeignClients` vào `TransactionServiceApplication.java` và khai báo đầy đủ `${account.service.url}`, Kafka producer/consumer, Redis trong `application.yml`.
- [ ] **Hoàn thiện các class stub:** Implement `RedisConfig.java` và `TransactionEventConsumer.java`.

---

## P1 - Reliability, Saga & Distributed Consistency (Chống mất tiền & trừ đúp)

- [ ] **Áp dụng Intent-First Flow trong `TransactionService.transfer()`:**
  - *Vấn đề hiện tại:* Gọi `accountServiceClient.deductBalance()` trước rồi mới lưu `Transaction` xuống DB. Nếu lưu DB thất bại hoặc pod crash giữa chừng, tiền đã trừ nhưng không có bản ghi đối soát/hoàn tiền.
  - *Hướng xử lý:* Lưu `Transaction` với trạng thái `INITIATED` (hoặc `PENDING`) xuống PostgreSQL **trước** để sinh `transactionId`, sau đó mới gọi `account-service` và cập nhật trạng thái sang `PROCESSING`.
- [ ] **Thiết kế End-to-End Idempotency (Chống Double-Spending):**
  - Yêu cầu Frontend gửi header `Idempotency-Key` (UUID) cho mỗi lệnh chuyển tiền.
  - Thêm cột `idempotency_key` với `UNIQUE` constraint trong PostgreSQL (Single Source of Truth) để chặn 2 request trùng lặp đập vào cùng thời điểm.
  - Truyền `transactionId` / `Idempotency-Key` qua `AccountServiceClient.deductBalance()` sang `account-service` để bên nhận lưu bảng đối soát trong cùng local DB transaction — đảm bảo retry sau Feign Read Timeout không bị trừ tiền lần 2.
  - *(Tùy chọn)* Thêm Redis Distributed Lock ở tầng ngoài để giảm tải cho PostgreSQL.
- [ ] **Giải quyết Dual-Write Problem (PostgreSQL + Kafka):**
  - *Vấn đề hiện tại:* Lưu DB rồi gọi `kafkaTemplate.send()` trực tiếp không đảm bảo atomicity (kể cả khi dùng `@Transactional`).
  - *Hướng xử lý:* Áp dụng **Transactional Outbox Pattern** — lưu `Transaction` và bản ghi event vào bảng `outbox_events` trong **cùng 1 local DB transaction**, sau đó dùng **Debezium (CDC)** hoặc Outbox Publisher đẩy lên Kafka (At-Least-Once Delivery) kết hợp **Idempotent Consumer** ở các service nhận.
- [ ] **Cấu hình Timeout, Resilience & Reconciliation:**
  - Cấu hình rõ Connect Timeout / Read Timeout cho OpenFeign và Circuit Breaker (Resilience4j).
  - Xử lý Ambiguous Failure (Read Timeout không rõ thành công hay thất bại) bằng trạng thái `UNKNOWN` kết hợp **Reconciliation Job** chạy nền đối soát lại với `account-service`.
- [ ] **Nâng cấp cơ chế trừ tiền sang Two-Phase Balance Hold (Nâng cao):**
  - Thay vì trừ thẳng rồi refund khi bị fraud/sai OTP, tách luồng `account-service` thành `HOLD` (phong tỏa số dư khả dụng) → `CAPTURE` (khi thành công) hoặc `RELEASE` (nhả phong tỏa khi hủy).

---

## P2 - Application Security & Input Validation (OWASP API Security)

- [ ] **Sửa lỗ hổng thiết kế API tại `TransactionController.verifyOTP()`:**
  - Đổi `@GetMapping("/{transactionId}/verify")` sang **`@PostMapping`** (HTTP `GET` không được phép thay đổi trạng thái tiền tệ / tránh CSRF & prefetch).
  - Chuyển `otp` từ `@RequestParam` sang **`@RequestBody`** để không bị lộ mã OTP plaintext trong Access Logs (API Gateway, Nginx, Load Balancer) và lịch sử trình duyệt.
- [ ] **Chặn lỗ hổng BOLA / IDOR (OWASP API1:2023):**
  - Không tin tuyệt đối `senderAccountNumber` / `accountNumber` từ client gửi lên.
  - Lấy `userId` từ JWT (`SecurityContextHolder`) và kiểm tra quyền sở hữu tài khoản trước khi thực thi `transfer()`, `getTransaction()`, và `getTransactionHistory()`.
- [ ] **Siết chặt Input Validation tại `TransferRequest.java` & Service:**
  - Thêm `@DecimalMin("0.01")` và `@Digits(integer = 13, fraction = 2)` cho `amount` (tránh lỗi làm tròn `0.000001` thành `0.00` hoặc tràn `precision = 15`).
  - Chặn tự chuyển tiền cho chính mình (`senderAccountNumber.equals(receiverAccountNumber)`).
  - Kiểm tra tài khoản đích (`receiverAccountNumber`) tồn tại và hợp lệ trước khi khởi tạo Saga.
- [ ] **Data Masking cho PII trong Logs:**
  - Không log plaintext số tài khoản khách hàng (`log.info` trong `TransactionService.java`). Masking theo định dạng `********1234` để tuân thủ tiêu chuẩn bảo mật Fintech.

---

## P3 - Database & Performance (PostgreSQL Optimization)

- [ ] **Chuyển `Transaction.id` sang `UUIDv7` + Native PostgreSQL `uuid`:**
  - *Vấn đề hiện tại:* Lưu UUIDv4 dưới dạng `String` (`VARCHAR`) vừa tốn gấp đôi bộ nhớ (~37 bytes vs 16 bytes) vừa gây **B-Tree Page Splits** & **Index Fragmentation** khi bảng lớn.
  - *Hướng xử lý:* Đổi kiểu `id` thành `java.util.UUID` (map native `uuid` trong PostgreSQL) và dùng thư viện sinh **UUIDv7** (time-ordered, chuẩn RFC 9562) để insert tuần tự cuối B-Tree.
- [ ] **Chuẩn hóa `referenceNumber`:**
  - Thay vì sinh thêm một chuỗi UUID 36 ký tự khó đọc cho khách hàng tra soát, format lại thành mã giao dịch thân thiện (ví dụ: `TXN-20261124-A9F2C1`) kèm `UNIQUE` index.

---

## P4 - Testing & Observability (TDD / CI-CD)

- [ ] **Viết Unit Tests (JUnit 5 + Mockito) cho Failure Paths & Edge Cases:**
  - `account-service` trả lỗi không đủ tiền / tài khoản bị khóa → không lưu `PROCESSING`, không bắn Kafka.
  - Chuyển cho chính mình, `amount = 0.001`, hoặc vi phạm BOLA (user A dùng tài khoản user B) → trả `400` / `403`.
- [ ] **Viết Integration Tests tự động (Testcontainers + WireMock):**
  - Dùng **Testcontainers** dựng PostgreSQL & Kafka thật trong CI/CD để test `UNIQUE` constraint cho `Idempotency-Key` (gửi 2 request concurrent), kiểm tra mapping entity và Kafka serialization.
  - Dùng **WireMock** giả lập `account-service` bị `ReadTimeout` và HTTP `500/503` để verify cơ chế xử lý lỗi & compensation.
- [ ] **Bổ sung Distributed Tracing (Observability):**
  - Tích hợp Micrometer Tracing + OpenTelemetry để truyền `traceId` / `spanId` xuyên suốt từ API Gateway → `transaction-service` → Feign (`account-service`) → Kafka (`fraud-detection-service`, `notification-service`).