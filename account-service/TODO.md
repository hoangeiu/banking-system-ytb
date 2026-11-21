# Technical Debt & Production Hardening Backlog (`account-service`)

## Immediate Pre-Commit Fixes

- [ ] Add missing `@KafkaListener(topics = "fraud.detected")` annotation to `AccountEventConsumer.consumeFraudDetected`.
- [ ] Add `.idea/` to `.gitignore` to keep IDE metadata out of version control.
- [ ] Clean up Git staging area so `application.properties` removal and full `application.yaml` changes are properly synced before committing.
- [ ] Fix minor typos in `AccountService.deductBalance` (missing space in `"Account is not active: "` message and Javadoc parameter description).

---

## Production Hardening & Refactoring

### Concurrency & Data Integrity

- [ ] Annotate write methods (`createAccount`, `deductBalance`, `creditBalance`, `blockAccount`) with `@Transactional` and read methods (`getAccount`, `getBalance`) with `@Transactional(readOnly = true)`.
- [ ] Prevent double-spending / lost updates in `deductBalance`, `creditBalance`, and `blockAccount` by implementing Pessimistic Write Locking (`@Lock(LockModeType.PESSIMISTIC_WRITE)` / `SELECT ... FOR UPDATE`) or an atomic conditional SQL update.
- [ ] Handle rare concurrent collisions during 12-digit account number generation using Spring Retry (`@Retryable` on `DataIntegrityViolationException`) or migrate to a DB Sequence / Snowflake ID generator.
- [ ] Add idempotency check in `blockAccount` (skip update if status is already `BLOCKED`).

### Kafka Resilience & Idempotency

- [ ] Remove exception-swallowing `try-catch` blocks in `AccountEventConsumer` so transient infrastructure failures (e.g., temporary DB outages) are not silently committed and dropped.
- [ ] Configure non-blocking retries with exponential backoff (`@RetryableTopic`) and a Dead Letter Topic handler (`@DltHandler`) for alerting and manual reconciliation; exclude non-retryable payload/domain errors from retries.
- [ ] Implement consumer-side transactional idempotency (e.g., a `processed_events` table keyed by `transactionId` within the same DB transaction) to guarantee at-most-once crediting under Kafka's at-least-once delivery semantics.
- [ ] Add null-safe payload extraction and parsing in Kafka consumers to avoid `NullPointerException` / `NumberFormatException`.

### Security, Configuration & Database Migrations

- [ ] Add defensive input validation in `deductBalance` and `creditBalance` to reject `null`, zero, or negative amounts (`amount <= 0`).
- [ ] Externalize sensitive credentials and environment-specific settings in `application.yaml` using environment variables (e.g., `${DB_PASSWORD}`, `${DB_URL}`) for Secrets Manager / Vault integration.
- [ ] Integrate **Flyway** (or Liquibase) for versioned database schema migrations and switch `spring.jpa.hibernate.ddl-auto` from `update` to `validate` for non-local environments.
- [ ] Set `spring.jpa.open-in-view: false` to avoid holding database connections across the web layer.
- [ ] Disable `spring.jpa.show-sql` by default and remove deprecated manual `hibernate.dialect` configuration (auto-detected in Hibernate 6 / Spring Boot 3+).
- [ ] Restrict Kafka `spring.json.trusted.packages` from `"*"` to explicit packages (`java.util,com.banking.*`) to prevent unsafe deserialization.

### Clean Code & Error Handling

- [ ] Replace generic `RuntimeException` with domain-specific exceptions (`AccountNotFoundException`, `AccountAlreadyExistsException`, `InsufficientFundsException`, `InvalidAccountStateException`).
- [ ] Implement a global `@RestControllerAdvice` exception handler to map domain exceptions to accurate HTTP status codes (`400`, `404`, `409`, `422`) instead of `500`.
- [ ] Extract daily transaction limits into named constants (or external configuration) and remove redundant `@Valid` from the Service layer.
- [ ] Evaluate migrating manual entity-to-DTO mapping (`mapToResponse`) to **MapStruct**.

### Testing

- [ ] Write Unit Tests (JUnit 5 + Mockito) for `AccountService.deductBalance` covering:
  - [ ] Happy path (`ACTIVE` account, `balance > amount`)
  - [ ] Boundary condition (`balance == amount`, resulting in `0.00`)
  - [ ] Account not found
  - [ ] Non-active account (`BLOCKED` / `CLOSED`)
  - [ ] Insufficient balance (`balance < amount`)
  - [ ] Invalid amount (`null`, `0`, or negative value)
- [ ] Add Unit Tests for `createAccount`, `creditBalance`, `blockAccount`, and `AccountEventConsumer`.
- [ ] Add Integration Tests using **Testcontainers** (PostgreSQL + Kafka) to verify locking behavior and event consumption end-to-end.
