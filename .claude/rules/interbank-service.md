---
paths:
  - "interbank-service/**"
  - "config-server/src/main/resources/config-repo/interbank-service.yml"
---

# interbank-service — Napas 247 (port 8086, schema `BANK_INTERBANK`)

## Cổng Napas
- `NapasGatewayService` — một bean theo `bank.interbank.gateway.provider` (env `NAPAS_PROVIDER`):
  - `mock` (mặc định dev) → `MockNapasGatewayService`, trạng thái trong bộ nhớ. Đuôi tài khoản nhận: `000` từ chối, `999` không phản hồi rồi thành công khi tra soát, `404` tra tên không thấy.
  - `http` → `HttpNapasGatewayService`: base URL `NAPAS_GATEWAY_URL`, `Bearer NAPAS_API_KEY`, hợp đồng ở `dto/NapasApi`, circuit breaker `napas-gateway`.
- **Gửi lệnh chuyển không bao giờ coi lỗi kết nối / timeout / 5xx / 408 / 429 / mạch mở là từ chối** → `PENDING` (tiền có thể đã đi). Chỉ 4xx khác hoặc body `REJECTED` mới là `REJECTED`. Tra soát `404` = `NOT_FOUND` → gửi lại (Napas idempotent theo `transactionRef`).

## Chuyển đi (`OUTBOUND_TRANSFERS`, `OutboundTransferService`)
- Nhận `INTERBANK_TRANSFER_REQUESTED` từ `bank.interbank.commands`: lưu `NEW` (unique `TRANSACTION_REF`). Lệnh trùng: chưa có kết quả → bỏ qua; đã có kết quả → `RESULT_PUBLISHED = 0` để publish lại (Saga gửi lại do quá hạn).
- `NEW → COMPLETED | REJECTED | UNKNOWN`; `UNKNOWN` tra soát giãn cách `inquiry-interval × attempts` (tối đa 10 phút), dừng sau `max-attempts` → nhân viên tra soát `POST /api/admin/interbank/outbound/{ref}/inquiry` (audit `INTERBANK_INQUIRY_TRIGGERED`).
- Job giữ khóa dòng (`FOR UPDATE SKIP LOCKED`) trong lúc gọi Napas nên các instance không gửi trùng; giữ `batch-size` nhỏ.

## Tiền đến (`INBOUND_TRANSFERS`, `InboundTransferService`)
- Webhook `POST /api/interbank/inbound` công khai (gateway `PUBLIC_PATHS` + `SecurityConfig` `permitAll`), **body nhận dạng String** để `NapasSignatureVerifier` kiểm HMAC-SHA256 trên body gốc: `X-Napas-Signature = hex(HMAC(secret, timestamp + "." + body))`, `X-Napas-Timestamp` epoch giây lệch ≤ `webhook.tolerance` (5m). Secret `NAPAS_WEBHOOK_SECRET` Base64 ≥ 256 bit, thiếu → không khởi động.
- Chống trùng `NAPAS_REF` (unique): cùng nội dung → trả trạng thái hiện có; khác → 409 `NAPAS_REF_CONFLICT`.
- Kiểm tra sớm tài khoản nhận (Redis `account.get-by-number` bằng `sendAsService`): không tồn tại / `INTERNAL` / không `ACTIVE` / khác VND → `RETURNED` ngay, không tạo giao dịch.
- Kết quả từ transaction-service (`INTERBANK_INBOUND_SETTLED / RETURNED`) → `CALLBACK_STATUS = PENDING` → `InboundCallbackService` báo Napas, lỗi gửi lại tới `max-callback-attempts` rồi `FAILED` (log ERROR).

## Outbox bằng cột trạng thái
- Không có bảng outbox: `RESULT_PUBLISHED` / `EVENT_PUBLISHED = 0` ghi cùng transaction với thay đổi trạng thái; `InterbankEventPublisherService` khóa lô, gửi `bank.interbank.events` (`send().get(5s)`), đánh dấu 1. At-least-once — transaction-service idempotent (Saga theo bước, tiền đến theo key `0:ibin-<napasRef>`).

## Job (`@Scheduled`)
| Job | Lịch (property, mặc định) |
|---|---|
| `OutboundTransferService.dispatchDue` — gửi / tra soát Napas | `bank.interbank.dispatch-interval` 2s |
| `InterbankEventPublisherService` — publish kết quả / tiền đến | `bank.interbank.publish-interval` 1s |
| `InboundCallbackService` — báo Napas kết quả tiền đến | `bank.interbank.callback-interval` 5s |

- Cache `interbank-banks` (danh mục ngân hàng, TTL 1 giờ). `bank.interbank.own-bank-code` (env `INTERBANK_OWN_BANK_CODE`): chuyển tới mã này bị chặn (`INTERBANK_SAME_BANK`).
- Không log số tài khoản / tên đầy đủ: `MaskingUtils`. `BENEFICIARY_NAME`, `SOURCE_NAME` mã hóa `EncryptedStringConverter`.
- Chưa làm: quyết toán bù trừ / đối soát file cuối ngày với Napas; nhận tiền đến theo alias số điện thoại.
