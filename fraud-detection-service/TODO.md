# TODO — Digital Banking Microservices (`transaction-service` & `fraud-detection-service`)

Tài liệu này tổng hợp toàn bộ các lỗi cần sửa ngay và lộ trình nâng cấp hệ thống lên chuẩn **Senior / Production Readiness** dựa trên quá trình code review.

---

## P0 — Sửa ngay trước khi chạy (Build, Wiring & Config Blockers)

- [ ] **Bật OpenFeign Clients:** Thêm `@EnableFeignClients` vào class `*Application.java` của cả `fraud-detection-service` và `transaction-service`.
- [ ] **Sửa Maven dependency ở `fraud-detection-service/pom.xml`:** Đổi `spring-cloud-openfeign-core` sang `spring-cloud-starter-openfeign` để kích hoạt Spring Boot Auto-Configuration.
- [ ] **Bổ sung cấu hình `fraud-detection-service/src/main/resources/application.yaml`:**
  - [ ] Thêm `server.port` (ví dụ `8084`).
  - [ ] Thêm `account.service.url` (tránh lỗi crash placeholder `${account.service.url}` lúc startup).
  - [ ] Thêm cấu hình Redis (`spring.data.redis.host`, `port`).
  - [ ] Thêm cấu hình Kafka (`bootstrap-servers`, consumer/producer serializers, `group-id` riêng biệt là `fraud-detection-group`).
  - [ ] Thêm cấu hình Actuator (`management.endpoints.web.exposure.include: health,info`).
- [ ] **Sửa lỗi nối chuỗi Redis key thiếu dấu phân cách `:` trong `FraudDetectionService`:**
  - [ ] Đổi `"fraud:avg_amount" + accountNumber` thành `"fraud:avg_amount:" + accountNumber`.
  - [ ] Đổi `"fraud:velocity" + accountNumber` thành `"fraud:velocity:" + accountNumber`.
- [ ] **Dọn dẹp annotation thừa:** Xóa `@Service` trên interface `@FeignClient` (`AccountServiceClient` ở cả hai service) để tránh gây hiểu nhầm (Spring bỏ qua `@Service` trên interface).
- [ ] **Sửa log sai tham số trong `FraudDetectionService`:** Dòng `"Suspicious activity detected - account: {} reason: {}"` đang truyền nhầm `transactionId` vào placeholder `account: {}` -> sửa thành `accountNumber` (hoặc log rõ cả `transactionId` và `accountNumber`).
- [ ] **Chuẩn hóa Line Endings:** Chuyển CRLF sang LF cho các file `pom.xml` và `application.yaml` (có thể thêm file `.gitattributes`) để tránh cảnh báo Git.

---

## P1 — Sửa 4 Bug Logic & Toàn vẹn dữ liệu (Critical Business Bugs)

- [ ] **Fix lỗi số dư sau khi trừ tiền (Post-Deduction Balance Bug):**
  - *Vấn đề:* `transaction-service` gọi `deductBalance()` trừ tiền trước rồi mới bắn `transaction.initiated`. Khi `FraudDetectionService` gọi `getBalance()`, giá trị nhận về là **số dư còn lại** nên check `amount > senderBalance * 0.90` sẽ chặn oan mọi giao dịch vượt quá ~47.4% số dư gốc.
  - *Hướng sửa:* Nhúng luôn `preDeductionBalance` (số dư trước khi trừ) vào payload của event `transaction.initiated` (mô hình *Event-Carried State Transfer*), hoặc tạm thời tính tỷ lệ trên tổng `senderBalance.add(amount)`.
- [ ] **Fix lỗi tự đầu độc ngưỡng trung bình (Running Average Poisoning) & thứ tự ghi Redis:**
  - *Vấn đề 1:* `isAmountSuspicious()` đang gọi `redisTemplate.opsForValue().set(avgKey, ...)` **trước khi** kiểm tra `amount > threshold`. Nếu kẻ gian bắn lệnh lớn, lệnh đầu bị chặn nhưng trung bình bị kéo vọt lên giúp lệnh thứ hai lọt qua.
  - *Vấn đề 2:* Velocity và Running Average đang ghi thay đổi vào Redis kể cả khi giao dịch bị chặn ở rule phía sau (ví dụ Balance check).
  - *Hướng sửa:* Tách rõ pha **Evaluate (chỉ đọc & đánh giá toàn bộ rule)** và pha **Commit State (chỉ cập nhật `avgKey` khi giao dịch sạch hoàn toàn hoặc đã verify OTP thành công)**.
- [ ] **Sửa công thức tính trung bình động (Running Average):**
  - *Vấn đề:* Công thức `(avg + amount) / 2` gán trọng số 50% cho giao dịch gần nhất, không phải giá trị trung bình thực sự.
  - *Hướng sửa:* Lưu cả `sum` và `count` (ví dụ bằng Redis Hash `fraud:stats:{accountNumber}`) để tính trung bình chuẩn xác.
- [ ] **Fix lỗi mất số lẻ tiền tệ ở `BigDecimal.divide`:**
  - Đổi `avgAmount.add(amount).divide(BigDecimal.valueOf(2), RoundingMode.HALF_UP)` thành bản 3 tham số chỉ định rõ scale: `.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP)`.
- [ ] **Bổ sung Kafka Partition Key trong `FraudDetectionService`:**
  - Sửa `kafkaTemplate.send(topic, event)` (2 tham số) thành `kafkaTemplate.send(topic, transactionId, event)` (3 tham số, hoặc dùng `accountNumber` nếu cần đảm bảo thứ tự chặt theo từng tài khoản khi topic có nhiều partition).

---

## P2 — Tối ưu Database & Hibernate (`transaction-service`)

- [ ] **Thay thế trả về toàn bộ `List<Transaction>` bằng phân trang chuẩn Production:**
  - Không dùng `findBySenderAccountNumberOrderByCreatedAtDesc` trả về `List` không giới hạn (gây Seq Scan/Disk Spill ở PostgreSQL và phình gấp đôi RAM Heap do Hibernate Persistence Context + Dirty Checking snapshot).
  - Áp dụng **Keyset / Cursor Pagination** (lọc theo `created_at` và `id` của dòng cuối trang trước: `WHERE sender_account_number = ? AND (created_at, id) < (?, ?) ORDER BY created_at DESC, id DESC LIMIT ?`) để giữ độ phức tạp luôn ở mức $O(\log N)$ thay vì `OFFSET`.
- [ ] **Tạo Composite B-Tree Index trong PostgreSQL:**
  - Thêm migration (Flyway/Liquibase):
    ```sql
    CREATE INDEX idx_tx_sender_created_at 
    ON transactions (sender_account_number, created_at DESC);
    ```
  - Đặt cột lọc bằng (`=`) đứng trước, cột `ORDER BY` đứng sau để PostgreSQL đọc thẳng thứ tự đã sắp xếp trên index, bỏ qua bước `Sort`.
- [ ] **Tối ưu luồng chỉ đọc (Read-Only) ở tầng Spring / Hibernate:**
  - [ ] Thêm `@Transactional(readOnly = true)` cho `getTransaction` và `getTransactionHistory` để tắt Dirty Checking và hỗ trợ định tuyến sang Read Replica.
  - [ ] Chuyển sang dùng **DTO Projection** thay vì load nguyên Entity quản lý bởi Hibernate.
  - [ ] Cấu hình `spring.jpa.open-in-view: false` trong `application.yaml` để giải phóng DB connection ngay khi kết thúc tầng Service.

---

## P3 — Concurrency, Atomicity & Idempotency (Redis & Kafka)

- [ ] **Đảm bảo tính nguyên tử (Atomicity) trên Redis bằng Lua Script:**
  - *Velocity Check:* Gói lệnh `INCR` + kiểm tra set `EXPIRE` vào một **Lua Script** chạy qua `RedisTemplate.execute(RedisScript)`. Tránh trường hợp app crash ngay giữa hai lệnh tạo ra key sống vĩnh viễn (`TTL = -1`) làm khóa oan tài khoản khách hàng.
  - *Amount Check:* Chuyển thao tác đọc-tính-ghi trung bình vào Lua Script để chống **Race Condition (Lost Update)** khi 2 giao dịch cùng tài khoản chạy song song.
- [ ] **Chống tấn công biên khung thời gian (Fixed Window Burst Attack):**
  - Nâng cấp Velocity Check từ bộ đếm Fixed Window 60 giây sang thuật toán **Sliding Window Log** (dùng Redis Sorted Set `ZSET` lưu timestamp và xóa phần tử cũ hơn 60 giây) hoặc **Sliding Window Counter** để ngăn gửi dồn gấp đôi hạn mức tại điểm giao nhau giữa hai phút.
- [ ] **Bổ sung Idempotency Guard cho `FraudDetectionEventConsumer`:**
  - Chống Kafka gửi trùng event (*At-Least-Once Delivery*) bằng cách kiểm tra khóa nguyên tử ngay đầu consumer: `SET fraud:processed:{transactionId} 1 NX EX 86400` trên Redis (hoặc bảng DB có `UNIQUE(transaction_id)`). Nếu đã xử lý thì bỏ qua ngay, không tăng velocity hay bắn lặp event.

---

## P4 — Resilience & Failure Handling (`fraud-detection-service`)

- [ ] **Giảm phụ thuộc đồng bộ (Temporal Coupling) trong Kafka Consumer:**
  - Ưu tiên dùng **Event-Carried State Transfer** (gửi kèm `senderBalance` từ `transaction-service`).
  - Nếu bắt buộc gọi đồng bộ qua OpenFeign (`AccountServiceClient`), phải cấu hình **Connect/Read Timeout chặt** và bọc **Resilience4j Circuit Breaker** để tránh Consumer Lag, Rebalance Storm hoặc vô tình DDoS ngược `account-service`.
- [ ] **Phân tách lỗi trong Kafka Consumer (thay vì bọc chung `RuntimeException`):**
  - *Non-retryable / Poison Pill* (lỗi JSON, dữ liệu hỏng, `NullPointerException`): Đẩy thẳng vào **Dead Letter Topic (DLT)** ngay lập tức để tránh **Head-of-Line Blocking** làm kẹt cả partition.
  - *Retryable / Transient* (timeout mạng, `account-service` trả 503): Dùng **Non-blocking Retry Topics** (`@RetryableTopic` với Exponential Backoff: 1s -> 5s -> 30s). Quá số lần thử mới đẩy DLT và phát sự kiện Saga compensation.
- [ ] **Xử lý kịch bản Redis sập hoàn toàn (Fail-Safe / Degraded Mode):**
  - Bọc các lời gọi Redis bằng Circuit Breaker + Fallback.
  - Không chọn Fail-Open (rủi ro mất tiền) hay Fail-Closed (sập hệ thống); chuyển sang **Fail-Safe**: tự động đẩy các giao dịch (đặc biệt trên ngưỡng an toàn) sang topic `verification.required` (ép xác thực OTP/sinh trắc học) và bắn cảnh báo khẩn cho đội vận hành.

---

## P5 — Application Security (Secure Coding)

- [ ] **Chặn lỗ hổng BOLA / IDOR (Top 1 OWASP API Security):**
  - Áp dụng Spring Security + JWT cho `GET /{transactionId}` và `GET /account/{accountNumber}` (`getTransactionHistory`).
  - Không tin tưởng `accountNumber` / `transactionId` từ client truyền lên URL. Luôn trích xuất `userId` từ JWT (`@AuthenticationPrincipal` / `SecurityContextHolder`) và kiểm tra quyền sở hữu ở tầng Service/Security (`@PreAuthorize` hoặc truy vấn kèm `ownerId`).
  - *(Đã làm tốt — giữ nguyên)*: `Transaction.id` hiện đã dùng `UUID` giúp chống dò quét Enumeration.

---

## P6 — Chiến lược Testing (Unit + Integration)

- [ ] **Unit Tests (JUnit 5 + Mockito):**
  - Kiểm thử nhanh logic thuần của từng rule: biên `maxTransactionsPerMinute`, hệ số `suspiciousAmountMultiplier`, `maxBalancePercentage`, `senderBalance <= 0`, và độ chính xác thập phân của `BigDecimal`.
- [ ] **Integration Tests (`@SpringBootTest` + Testcontainers [Kafka, Redis, PostgreSQL] + WireMock):**
  - [ ] **Test Case 1 — Context Load & Wiring:** Xác nhận Spring Boot khởi động sạch, Feign bean được tạo đúng, YAML binding đầy đủ và Redis key có đúng định dạng `:` .
  - [ ] **Test Case 2 — Boundary & State Isolation:** Kiểm tra đúng ngưỡng biên (`count == max` hợp lệ vs `count == max + 1` báo fraud) và chứng minh giao dịch bị chặn ở rule sau không làm bẩn dữ liệu Redis.
  - [ ] **Test Case 3 — Idempotency End-to-End:** Bắn 2 event `transaction.initiated` trùng `transactionId` vào Kafka thật -> xác nhận Redis chỉ đếm 1 lần và chỉ có đúng 1 event đầu ra được publish.
  - [ ] **Test Case 4 — Failure & Resilience:** Dùng WireMock giả lập `account-service` timeout / 500 và gửi payload hỏng vào Kafka -> xác nhận Consumer không treo vô hạn, đẩy đúng Retry Topic / DLT, và kích hoạt Fallback khi Redis mất kết nối.