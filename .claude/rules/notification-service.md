---
paths:
  - "notification-service/**"
  - "config-server/src/main/resources/config-repo/notification-service.yml"
---

# Thông báo — email / SMS (notification-service)

- `NotificationSenderService` định tuyến theo kênh tới `NotificationGatewayService` (mỗi kênh tối đa một bean, chọn bằng `bank.notification.<email|sms>.provider`):
  - Email `smtp` → `SmtpEmailGatewayService` (`spring.mail.*`, text thuần UTF-8, from `bank.notification.email.from`). Dev mặc định gửi vào Mailpit (`localhost:1025`, UI `http://localhost:8025`).
  - SMS `http` → `HttpSmsGatewayService`: `POST bank.notification.sms.url`, `Authorization: Bearer <api-key>`, body `SmsGatewayRequest{to, message, sender}`; mặc định bỏ dấu tiếng Việt (`strip-diacritics`). Qua circuit breaker `sms-gateway` + timeout `spring.http.client.*`. Nhà cung cấp có API khác → viết thêm một `NotificationGatewayService` cho kênh SMS.
  - `log` = giả lập (chỉ ghi log đã che người nhận) — mặc định của SMS ở dev. Provider khác `log` mà không có gateway → service không khởi động.
- Lỗi gửi ném `NotificationSendException`: `retryable=true` (mất kết nối, 5xx, 408/429, SMTP tạm lỗi) → `NotificationRetryService` gửi lại tới `bank.notification.max-retries`; `false` (4xx, địa chỉ bị từ chối/sai định dạng) → đặt `RETRY_COUNT = max-retries`, không gửi lại. Message lỗi không chứa nội dung/body phản hồi (có thể có OTP) vì được lưu `ERROR_MESSAGE` và ghi log.
- OTP gửi đồng bộ qua SMS (`OtpNotificationService`, Redis action `notification.send-otp`), lỗi trả về transaction-service ngay, không retry (nội dung lưu đã che mã).
- Consumer `bank.notification.events` idempotent: unique `EVENT_ID, CHANNEL`.
- Job `NotificationRetryService` (`bank.notification.retry-interval` 60s) khóa lô 50 dòng (`NotificationRepository.lockRetryBatch`, `ORDER BY ID FOR UPDATE SKIP LOCKED`, đọc giới hạn từ cursor — không dùng `ROWNUM`) trong transaction nên nhiều instance không gửi trùng.
- Biến môi trường: `EMAIL_PROVIDER` (mặc định `smtp`), `MAIL_HOST/PORT/USERNAME/PASSWORD/FROM`, `MAIL_SMTP_AUTH`, `MAIL_SMTP_STARTTLS`; `SMS_PROVIDER` (mặc định `log`), `SMS_GATEWAY_URL`, `SMS_GATEWAY_API_KEY`, `SMS_SENDER`.
- Dev: Mailpit không chạy → email lỗi tạm thời (job gửi lại) và health `mail` của actuator `DOWN`; không cần email thì đặt `EMAIL_PROVIDER=log`. Đổi cấu hình phải restart config-server rồi notification-service.
