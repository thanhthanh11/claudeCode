# CLAUDE.md

Hướng dẫn cho Claude Code khi làm việc với repo này. Chỉ giữ thứ cần cho mọi tác vụ; chi tiết từng vùng nằm ở `.claude/rules/` (tự nạp khi làm việc với file khớp `paths`).

## Ngôn ngữ và người dùng
- Luôn trả lời, giải thích, viết tài liệu bằng tiếng Việt. Tên class/method/biến/package và commit message dùng tiếng Anh theo chuẩn Java.
- Người dùng là lập trình viên Java: dùng thẳng thuật ngữ Java/Spring, không giải thích kiến thức cơ bản.

## Tổng quan
Hệ thống ngân hàng microservice: **Java 21**, **Spring Boot 3.5.7**, **Spring Cloud 2025.0.0**, Lombok, jjwt 0.12, Oracle, Kafka, Redis, Hazelcast.
- Maven multi-module: `pom.xml` gốc là parent (`dependencyManagement`/`pluginManagement`), mỗi service một module.
- Java 21: `record` cho DTO/event, pattern matching, switch expression; virtual threads bật (`spring.threads.virtual.enabled=true`).

| Module | Port | Trách nhiệm |
|---|---|---|
| `common-lib` | — | Thư viện dùng chung: DTO/event Kafka, exception, JWT, Redis request-reply, auto-configuration |
| `api-gateway` | 8080 | Spring Cloud Gateway (WebFlux): điểm vào duy nhất, verify JWT, định tuyến `lb://`, rate limit Redis |
| `auth-service` | 8081 | Đăng nhập, phát hành/làm mới JWT, user/role/permission (RBAC), audit log |
| `customer-service` | 8082 | Hồ sơ khách hàng, KYC |
| `account-service` | 8083 | Tài khoản, số dư, sổ cái, tạm giữ tiền, sao kê; xử lý lệnh trừ/cộng/hoàn từ Kafka |
| `transaction-service` | 8084 | Chuyển / nộp / rút, Saga, Outbox, idempotency, phí, hạn mức, OTP, back-office |
| `notification-service` | 8085 | Gửi thông báo email (SMTP) / SMS (HTTP gateway) từ Kafka, gửi OTP |
| `discovery-server` | 8761 | Eureka |
| `config-server` | 8888 | Spring Cloud Config (profile `native`) |
| `saga-it` | — | Chỉ có test: Saga chuyển tiền liên service bằng Testcontainers |

## Tài liệu chi tiết (đọc khi làm vùng tương ứng)
| File | Nội dung |
|---|---|
| `nghiep-vu-ngan-hang.md` | Nghiệp vụ: vai trò, ma trận quyền, luồng API, trạng thái. Cập nhật khi đổi nghiệp vụ |
| `.claude/rules/gateway-routing.md` | Route gateway, `PUBLIC_PATHS`, rate limit, Swagger |
| `.claude/rules/security.md` | JWT/RBAC, service token, thu hồi token, audit |
| `.claude/rules/common-lib.md` | Auto-configuration, DLT resolver, circuit breaker, Jackson |
| `.claude/rules/kafka-saga.md` | Topic/event, idempotency consumer, outbox |
| `.claude/rules/transaction-service.md` | Saga, phí, hạn mức, OTP, back-office, chuyển định kỳ, job |
| `.claude/rules/account-service.md` | Khóa dòng số dư, sổ cái, tạm giữ, job đối soát |
| `.claude/rules/notification-service.md` | Email/SMS provider, phân loại lỗi retry |
| `.claude/rules/redis-request-reply.md` | Kênh Redis, danh sách action |
| `.claude/rules/hazelcast-cache.md` | Cache, danh sách cache, TTL |
| `.claude/rules/observability.md` | Tracing Jaeger, log Loki/Grafana, hạ tầng docker-compose |
| `.claude/rules/{oracle-migration,jpa-entity,java-code-style,testing}.md` | Script Oracle, mapping JPA, code style, test |
| `CLAUDE.local.md` | Oracle local, mật khẩu dev (gitignore) |
| `docs/roadmap.md` | Lộ trình, việc nên làm tiếp |

- Skills: `add-oracle-migration`, `add-redis-action`, `add-kafka-event`, `build-and-test`; command `/run-local`. Agents: `code-reviewer`, `security-auditor`, `db-migration-reviewer`. Xem `.claude/README.md`.
- Hook `block-sensitive-files.sh` chặn sửa `.env`, `*.pem/key/jks`, `setup_admin_user.sql`, script `00_`–`05_`.
- `AGENTS.md`: bản tương đương cho Codex — khi sửa CLAUDE.md, đồng bộ phần liên quan.
- **Giữ CLAUDE.md < 150 dòng**: nội dung mới chỉ cho một vùng code → thêm vào rule tương ứng (hoặc tạo rule mới có `paths`), không thêm vào đây.

## Lệnh thường dùng
- Git Bash: gọi `mvn.cmd` (`mvn` có thể lỗi "Could not find or load main class ...Launcher" và in ra rỗng); chỉ kết luận khi thấy `BUILD SUCCESS`.
- Build: `mvn.cmd clean install -DskipTests`; một service kèm phụ thuộc: `mvn.cmd -pl account-service -am clean install`.
- Test: `mvn.cmd -pl account-service test [-Dtest=Class#method]`; IT (Docker): thêm `verify -Pintegration-test`. Chi tiết, Saga E2E, lỗi khóa jar trên Windows: skill `build-and-test`.
- Chạy: `mvn.cmd -pl <module> spring-boot:run`. Thứ tự `discovery-server` → `config-server` (bắt buộc trước, `configserver:` không optional) → các service → `api-gateway`; hạ tầng `docker compose up -d` (Oracle chạy riêng). Chi tiết: `/run-local`.

## Kiến trúc — quy tắc chung
- Database-per-service: không truy cập DB của service khác — dùng REST, Kafka hoặc kênh Redis. Giao dịch liên service theo Saga qua Kafka, có bù trừ.
- Cấu hình tập trung ở `config-server/src/main/resources/config-repo/` (`application.yml` chung + `<service>.yml`); `application.yml` trong service chỉ có port, tên, `spring.config.import`. Thư mục **không** tên `config/` (Spring Boot sẽ tự nạp cho chính config-server).
- Bí mật lấy từ biến môi trường (`AES_ENCRYPTION_KEY`, `OTP_HMAC_SECRET`, `DB_PASSWORD`, `REDIS_PASSWORD`, `MAIL_PASSWORD`, `SMS_GATEWAY_API_KEY`...); mặc định trong config-repo chỉ cho dev.
- Địa chỉ hạ tầng ghi đè bằng biến môi trường: `CONFIG_SERVER_URL`, `EUREKA_URL`, `DB_URL`/`DB_USERNAME`, `KAFKA_BOOTSTRAP_SERVERS`, `REDIS_HOST`/`REDIS_PORT`, `HAZELCAST_MEMBERS`, `LOG_DIR`... (biến riêng của từng vùng ghi ở rule tương ứng).
- **Controller có prefix mới → thêm path vào `GatewayRouteConfig`**, nếu không gọi qua gateway sẽ 404.

### Cấu trúc source mỗi service
Package gốc `com.bank.<service>`:

| Package | Nội dung | Tên class |
|---|---|---|
| `dto` | Request/response | `***Request`, `***Response` |
| `entity` | JPA entity | Danh từ số ít, vd. `Account` |
| `controller` | REST controller | `***Controller` |
| `repository` | Spring Data JPA | `***Repository` |
| `service` | Nghiệp vụ, Kafka listener, Redis handler | `***Service` (vd. `AccountCommandConsumerService`, `CustomerRedisHandlerService`) |
| `enums` | Enum | `***Enum` |
| `exception` | Exception + `GlobalExceptionHandler` | `***Exception` |
| `config` | Security, Kafka, Redis, Hazelcast… | `***Config` |

- Controller chỉ nhận/trả DTO bọc `ApiResponse<T>` (phân trang `PageResponse<T>`), không gọi repository.
- `GlobalExceptionHandler` chỉ `extends BaseExceptionHandler`; exception nghiệp vụ kế thừa `BusinessException` (HTTP status + mã lỗi).
- Ngoại lệ: `api-gateway` có package `filter` (`GlobalFilter`); `common-lib` có thêm `audit`, `crypto`, `event`, `redis`, `resilience`, `security`, `tracing`, `util`.

### Cơ sở dữ liệu
- Schema riêng `BANK_AUTH`, `BANK_CUSTOMER`, `BANK_ACCOUNT`, `BANK_TRANSACTION`, `BANK_NOTIFICATION`; không FK giữa các schema. Hibernate `ddl-auto=validate` → đổi schema bằng script trong `database/oracle/` (skill `add-oracle-migration`, **không sửa file đã chạy**).
- **Thêm migration mới → bổ sung vào danh sách script của** `AccountBalanceConcurrencyIT`, `TransactionLimitConcurrencyIT` (nếu thuộc schema tương ứng) và `TransferSagaIT.createSchemas()`.

### Job định kỳ
- `@Scheduled` bật bằng `SchedulingConfig` của từng service; danh sách job ở rule của service. Chưa có ShedLock/leader election: chạy nhiều instance thì mọi instance cùng chạy job → **job mới phải khóa dòng (`FOR UPDATE SKIP LOCKED`) hoặc idempotent**.

## Lưu ý nghiệp vụ ngân hàng
- Tiền dùng `BigDecimal` (không `double`/`float`), so sánh bằng `compareTo`; test assert bằng `isEqualByComparingTo`.
- Thay đổi số dư phải nhất quán: transaction, idempotency key, consumer chống trùng; test cả trường hợp trùng và đồng thời.
- **Không cache số dư/tài khoản/giao dịch**; xóa cache sau commit.
- Không log dữ liệu nhạy cảm (số thẻ, mật khẩu, token, OTP, nội dung thông báo); số tài khoản / điện thoại / email che bằng `MaskingUtils`. Log đã được đẩy tập trung lên Loki nên quy tắc này áp dụng cả với log JSON.
- **Mã hóa AES-256-GCM** (một khóa gốc `AES_ENCRYPTION_KEY`, khóa con dẫn xuất HKDF): JWT là JWE; PII trong DB qua `EncryptedStringConverter` — tìm/unique bằng cột blind index `*_HASH`, không query trên cột mã hóa; value Kafka và message Redis request-reply mã hóa trên dây. Chi tiết: rule `security`.
