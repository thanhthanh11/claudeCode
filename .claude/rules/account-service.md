---
paths:
  - "account-service/**"
  - "config-server/src/main/resources/config-repo/account-service.yml"
---

# account-service — số dư, sổ cái, tạm giữ

- `AccountBalanceService` khóa dòng tài khoản `SELECT ... FOR UPDATE`, ghi sổ cái, commit rồi mới gửi kết quả (đồng bộ) và ack. Lỗi nghiệp vụ (thiếu số dư, bị phong tỏa) trả event `*_FAILED`, không ném exception.
- Consumer `bank.account.commands` idempotent: unique `ACCOUNT_ID, TRANSACTION_REF, ENTRY_TYPE`.
- Phí: DEBIT/REFUND mang `fee` → trừ/hoàn `amount + fee` trên một bút toán, ghi phí vào tài khoản `INTERNAL` (customerId 0) trong cùng transaction (chi tiết ở rule `transaction-service`).
- Mở tài khoản gọi `customer-service` qua `@LoadBalanced RestClient` (`http://customer-service/...`) để kiểm tra KYC; timeout `spring.http.client.*`.
- `ACCOUNTS.VERSION` cho `@Version`.
- Sao kê JSON/CSV/PDF (`/api/accounts/{no}/statement[/export]`, OpenPDF + font Liberation).
- Alias số điện thoại (`PHONE_ALIASES`, `PhoneAliasService`, script 16): chỉ lưu `PHONE_HASH` (blind index, cùng khóa với `CUSTOMERS.PHONE_HASH`), unique theo SĐT và theo tài khoản. Đăng ký lấy SĐT từ hồ sơ (`customer.get-by-user-id`), yêu cầu KYC `VERIFIED` + tài khoản `PAYMENT ACTIVE`. Phân giải **so lại SĐT hiện tại của chủ** (`customer.get-by-id` bằng `sendAsService`) — đổi số / khóa khách là alias mất hiệu lực; mọi trường hợp không dùng được trả `PHONE_ALIAS_NOT_FOUND` để không lộ trạng thái người khác. REST lookup che số tài khoản; Redis action trả số đầy đủ cho transaction-service.

## Job (`@Scheduled`)
| Job | Lịch (property, mặc định) |
|---|---|
| `LedgerReconciliationService` — đối soát số dư ↔ sổ cái | `bank.reconciliation.cron` 01:30 |
| `AccountHoldService` — giải tỏa tạm giữ hết hạn (khóa từng dòng) | `bank.hold.expiry-check-interval` 60s |
