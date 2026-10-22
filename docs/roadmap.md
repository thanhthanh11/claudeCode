# Lộ trình phát triển

Ưu tiên: **an toàn tiền → kiểm soát rủi ro → mở rộng nghiệp vụ → vận hành**. Thiết kế chi tiết nằm ở `.claude/rules/*.md`; mục mới đánh dấu ✅ khi xong và cập nhật rule liên quan.

- ✅ **GĐ1 — An toàn tiền**: phát hiện Saga kẹt / `MANUAL_REVIEW` (06); back-office `/api/admin/transactions`, `/outbox`, `/dead-letters` (07, 09); đối soát số dư ↔ sổ cái (`/api/admin/ledger-reconciliation`, job 01:30) và giao dịch ↔ sổ cái (`/api/admin/transaction-reconciliation`, job 02:00) (08, 09).
- ✅ **GĐ2 — Rủi ro**: chặn khách hàng không `ACTIVE` (`CUSTOMER_NOT_ACTIVE`); hạn mức (`/api/transaction-limits`); OTP (`/api/transactions/{ref}/confirm`, `/otp`); thu hồi access token tức thì.
- ✅ **GĐ3 — Nghiệp vụ**: biểu phí (`/api/fee-schedules`) + tài khoản thu phí (08, 09, 12); tạm giữ tiền (`/api/accounts/{no}/holds`, tự giải tỏa); sao kê JSON/CSV/PDF (`/api/accounts/{no}/statement[/export]`, OpenPDF + font Liberation); thông báo người nhận, mẫu nộp/rút/OTP (10); tự `VERIFIED` KYC (`bank.kyc.auto-verify`); danh bạ (`/api/beneficiaries`), chuyển định kỳ (`/api/scheduled-transfers`).
- ✅ **GĐ4 — Vận hành**: tracing OTLP/Jaeger (11); log tập trung Grafana + Loki + Alloy; circuit breaker Resilience4j; audit log (`/api/audit-logs`); IT Testcontainers (trừ tiền / hạn mức song song); Saga E2E `saga-it` (thành công + phí, lỗi trừ tiền, bù trừ, message trùng, idempotency key); springdoc + Swagger gộp; chia tải tài khoản thu phí (12), đã xóa topic thừa `bank.account.commands-dlt`.
- ✅ **Cổng gửi thật**: email SMTP (`SmtpEmailGatewayService`, Mailpit cho dev), SMS qua HTTP gateway (`HttpSmsGatewayService`), phân loại lỗi tạm thời / vĩnh viễn cho job retry.

- ✅ **Chuyển tiền qua số điện thoại**: alias SĐT → tài khoản (`PHONE_ALIASES`, 16), `/api/accounts/{no}/phone-alias`, `/api/transactions/transfer-by-phone`.
- ✅ **Liên ngân hàng Napas 247** (`interbank-service`, 17–19): tra tên, chuyển đi (Saga `INTERBANK_REQUESTED`, tra soát khi không rõ kết quả, không tự hoàn tiền), tiền đến qua webhook HMAC, Napas giả lập cho dev.

## Việc nên làm tiếp
- Liên ngân hàng: quyết toán bù trừ với Napas (tài khoản thanh toán bù trừ `INTERNAL`, đối soát file cuối ngày), IT Testcontainers cho `interbank-service`, nhận tiền đến theo alias SĐT, kịch bản `saga-it` cho chuyển đi / tiền đến.
- `NotificationRetryService` khóa lô (`FOR UPDATE SKIP LOCKED`) trước khi gửi lại, để chạy nhiều instance không gửi trùng.
- Metrics (Prometheus/Micrometer) + dashboard Grafana cho Saga, outbox, consumer lag; cảnh báo khi có `MANUAL_REVIEW` / message vào DLT.
