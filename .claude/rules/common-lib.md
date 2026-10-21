---
paths:
  - "common-lib/**"
---

# common-lib — auto-configuration

Nạp qua `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`; dependency web/security/redis/kafka/hazelcast là `optional`, auto-config dùng `@ConditionalOnClass(name = ...)` để gateway (WebFlux) không nạp phần servlet. Package riêng: `audit`, `crypto`, `event`, `redis`, `resilience`, `security`, `tracing`, `util`.

- `CommonCryptoConfig` (không điều kiện servlet — gateway cần khóa JWE) → `EncryptionKeyring` (khóa AES gốc + khóa con HKDF; chi tiết rule `security`).
- `CommonJwtConfig` → `JwtTokenProvider` (JWE, khóa `keyring.jweKey()`); `CommonSecurityConfig` (servlet) → `JwtAuthenticationFilter`, entry point/denied handler JSON. Mỗi service vẫn tự khai báo `SecurityConfig` và gắn filter này.
- `RedisRequestReplyConfig` (servlet + Redis) → `RedisRequestReplyClient`, `RedisRequestWorker`, `TokenRevocationChecker`, `TraceContextPropagator` (khi có micrometer-tracing).
- `CommonKafkaConfig` → khai báo topic, customizer gắn `EncryptingStringSerializer` / `ErrorHandlingDeserializer(DecryptingStringDeserializer)` (value mã hóa), `DefaultErrorHandler` (retry 3 lần rồi đẩy `<topic>.DLT` cùng partition; message lỗi giải mã gửi bytes gốc bằng template `byte[]` riêng), `AuditPublisher`. Destination resolver DLT phải khai báo tường minh: mặc định Spring Kafka 3.x là `<topic>-dlt` → topic không tồn tại, message retry mãi chặn partition (lỗi đã gặp).
- `CommonResilienceConfig` → `RemoteCallGuard`: circuit breaker theo service đích cho Redis request-reply và REST nội bộ; chỉ timeout/5xx tính lỗi (`bank.resilience.circuit-breaker.*`); mạch mở → `ServiceUnavailableException` 503.
- `CommonOpenApiConfig` → OpenAPI có bearer JWT, server = gateway (`OPENAPI_SERVER_URL`).
- `CommonJacksonConfig` → số tiền qua JSON chính xác (đọc `BigDecimal`, ghi plain). **Không tắt.**
- `CacheEvictionUtils.clearAfterCommit/runAfterCommit`: xóa cache / chạy tác vụ sau commit.
- Sửa common-lib → test kèm module phụ thuộc: `mvn.cmd -pl common-lib -amd test`.
