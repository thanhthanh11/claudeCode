---
paths:
  - "auth-service/**"
  - "api-gateway/**"
  - "**/security/**"
  - "**/config/*Security*.java"
  - "**/*RedisHandler*.java"
  - "**/audit/**"
  - "**/crypto/**"
---

# Bảo mật — Spring Security + JWT + RBAC

- JWT stateless dạng **JWE** (`alg=dir`, `enc=A256GCM`, khóa con `jwe` của `EncryptionKeyring`) — client không đọc được claim; claim `sub`=username, `uid`, `roles`, `perms`, `typ`; access token 15 phút, refresh token 7 ngày (`bank.security.jwt.*`). Chỉ `auth-service` phát hành/làm mới; gateway và service khác chỉ verify (`JwtTokenProvider.parseToken`, token JWS cũ bị từ chối). Thông tin người dùng cho client lấy qua `GET /api/auth/me`.
- Role → `ROLE_<code>` (`hasRole`); permission giữ nguyên mã (`hasAuthority`), hằng ở `PermissionCodes`, seed trong `BANK_AUTH.PERMISSIONS`.
- Refresh token: chỉ lưu SHA-256, xoay vòng mỗi lần refresh (đọc `findByTokenHashForUpdate` — khóa dòng nên refresh song song cùng token chỉ một request thành công); dùng lại token đã thu hồi → thu hồi toàn bộ token của user. Sai mật khẩu 5 lần → khóa 15 phút (login đọc user `findByUsernameForUpdate` để không mất cập nhật bộ đếm khi đăng nhập song song).
- ADMIN đầu tiên do `AdminBootstrapService` tạo khi chưa có ADMIN: `admin` / `Admin@123456` (`bank.auth.bootstrap-admin.*`, env `BOOTSTRAP_ADMIN_USERNAME/PASSWORD/EMAIL`).
- Quyền sở hữu kiểm tra trong service: so `customerId` của tài khoản với customerId của user (tra qua Redis). `TELLER`/`ADMIN` bỏ qua.
- **Service token** (`roles=[SERVICE]`, TTL 1 phút): `RedisRequestReplyClient` tự sinh khi không có token người dùng (vd. trong Kafka consumer); chỉ hợp lệ trên kênh Redis — REST và gateway từ chối. Action chỉ cho service (vd. gửi OTP) gọi bằng `sendAsService`.
- Giao tiếp nội bộ mang danh tính: REST chuyển tiếp header `Authorization`; Redis request mang token; handler tự kiểm tra quyền của `caller`.
- **Thu hồi access token tức thì**: khóa user / đổi role → `AccessTokenRevocationService` (sau commit) ghi `bank:auth:revoked-before:<userId>` = epoch giây (TTL = TTL access token). Gateway, `JwtAuthenticationFilter`, `RedisRequestWorker` từ chối token có `iat` nhỏ hơn; Redis lỗi → fail-open.
- `OPERATION_MANAGE` (ADMIN): `/api/admin/**` (MANUAL_REVIEW, outbox, DLT, đối soát). Sửa hạn mức / biểu phí / xem audit: `hasRole('ADMIN')`.
- **Audit**: thao tác nhân viên gọi `AuditPublisher.publish(action, targetType, targetId, detail)` (mã ở `AuditActions`) → `bank.audit.events` (sau commit) → auth-service lưu `BANK_AUTH.AUDIT_LOGS` (idempotent theo eventId), tra `GET /api/audit-logs`. `detail` không chứa dữ liệu nhạy cảm; target là ID, không ghi số tài khoản.
- Hồ sơ khách hàng: khách chỉ tự sửa `address`; họ tên, số điện thoại, email (kênh nhận OTP) chỉ nhân viên có `CUSTOMER_MANAGE` sửa, ghi audit `CUSTOMER_PROFILE_UPDATED` (chỉ tên trường). Nhân viên chuyển khoản (không OTP) ghi audit `STAFF_TRANSFER_CREATED`.

## Mã hóa AES (`common-lib` package `crypto`)
- Một khóa gốc `bank.security.encryption.key` (env `AES_ENCRYPTION_KEY`, Base64 đúng 256 bit; thiếu/sai → không khởi động). `EncryptionKeyring` dẫn xuất bằng HKDF-SHA256 các khóa con: `jwe`, `db`, `kafka`, `redis`, `blind-index`. Đổi khóa gốc = mọi token cũ mất hiệu lực và phải mã hóa lại dữ liệu đã lưu.
- `AesGcmCipher`: AES-256-GCM, IV 12 byte ngẫu nhiên, định dạng `enc:v1:<base64(IV‖ct‖tag)>`; giá trị không có tiền tố đọc như plaintext cũ; lỗi giải mã không đưa dữ liệu vào message.
- Cột PII (`@Convert(converter = EncryptedStringConverter.class)`): `CUSTOMERS.ID_NUMBER/PHONE/EMAIL/ADDRESS`, `USERS.EMAIL`, `NOTIFICATIONS.RECIPIENT`, `OUTBOX_EVENTS.PAYLOAD`, `DEAD_LETTER_EVENTS.PAYLOAD`. Tìm / unique qua blind index `BlindIndexer` (HMAC, chuẩn hóa trước khi băm): `ID_NUMBER_HASH` (gồm loại giấy tờ), `PHONE_HASH`, `EMAIL_HASH` — service phải gán hash mỗi khi ghi giá trị.
- Dữ liệu cũ: `PiiEncryptionBackfillService` (mỗi service, `ApplicationRunner`) dùng `ColumnEncryptionBackfill` mã hóa theo lô `FOR UPDATE SKIP LOCKED`, idempotent.
- Kafka: value mã hóa bởi `EncryptingStringSerializer` / `DecryptingStringDeserializer` (gắn qua customizer trong `CommonKafkaConfig`, khóa không nằm trong config Kafka client). Redis request-reply: toàn bộ message request/reply mã hóa (token, payload, OTP, SĐT/email trả về).
- Không mã hóa: `FULL_NAME` (cần `LIKE`), `ACCOUNT_NUMBER` (khóa tra cứu liên service — che khi log), `NOTIFICATIONS.CONTENT` (giới hạn 4000 byte, đã che số tài khoản). Cache Hazelcast giữ plaintext trong bộ nhớ.
