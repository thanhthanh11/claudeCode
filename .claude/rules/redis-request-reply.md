---
paths:
  - "**/redis/**"
  - "**/*Redis*.java"
---

# Redis — request/reply

- `RedisRequestReplyClient.send(targetService, action, payload, type)`: LPUSH `bank:rr:queue:<service>`, BRPOP chờ `bank:rr:reply:<correlationId>` (mặc định 5s → `ServiceTimeoutException` 504). `RedisRequestWorker` (virtual threads) ở service đích verify token, đặt SecurityContext, gọi `RedisRequestHandler` theo action.
- Lỗi từ service đích giữ nguyên status/mã lỗi (`RemoteServiceException`).
- Request mang token người dùng hoặc service token (xem rule `security`) và `traceContext`. Toàn bộ message request/reply được mã hóa AES-GCM (khóa con `redis`) trước khi LPUSH; message không giải mã được bị worker bỏ qua.
- Action (`RedisActions`):
  - `customer.get-by-id`, `customer.get-by-user-id` → customer.
  - `account.get-by-number` → account (số dư chỉ trả cho chủ tài khoản / nhân viên / service).
  - `account.ledger-by-ref` → account (chỉ service / nhân viên, cho đối soát).
  - `notification.send-otp` → notification (chỉ service token).
- Thêm action: hằng trong `RedisActions` + bean `***RedisHandlerService implements RedisRequestHandler` ở service đích (tự kiểm tra quyền) — xem skill `add-redis-action`.
- Không dùng Hazelcast thay Redis cho request/reply.
