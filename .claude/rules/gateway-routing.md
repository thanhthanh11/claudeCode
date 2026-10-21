---
paths:
  - "api-gateway/**"
  - "**/controller/**/*.java"
  - "**/config/*Security*.java"
---

# API và định tuyến gateway

Route khai báo bằng Java trong `api-gateway/.../config/GatewayRouteConfig.java` (không nằm trong yml), mọi route có rate limit. **Controller có prefix mới → phải thêm path vào đây**, nếu không gọi qua gateway sẽ 404.

| Service | Path |
|---|---|
| auth | `/api/auth/**`, `/api/users/**`, `/api/roles/**`, `/api/audit-logs/**` |
| customer | `/api/customers/**` (gồm `/{id}/kyc-documents`) |
| account | `/api/accounts/**` (gồm `/{no}/holds`, `/{no}/statement`), `/api/admin/ledger-reconciliation/**` |
| transaction | `/api/transactions/**`, `/api/beneficiaries/**`, `/api/scheduled-transfers/**`, `/api/transaction-limits/**`, `/api/fee-schedules/**`, `/api/admin/{transactions,outbox,dead-letters,transaction-reconciliation}/**` |
| notification | `/api/notifications/**` (`/me`), `/api/notification-templates/**` |

- Không cần JWT (`PUBLIC_PATHS` trong gateway `JwtAuthenticationFilter`): `/api/auth/login`, `/register`, `/refresh`, `/actuator/health/**`, `/actuator/info`, tài liệu springdoc. Thêm path công khai thì sửa cả gateway lẫn `SecurityConfig` của service.
- Rate limit: 20 request/giây, burst 40 cho mỗi user (hoặc IP) — `bank.gateway.rate-limit.*`; `RedisRateLimiter`, key theo userId hoặc IP.
- Swagger UI gộp `http://localhost:8080/swagger-ui.html` (route `/api-docs/<service>`; tắt bằng `springdoc.api-docs.enabled=false` ngoài dev).
