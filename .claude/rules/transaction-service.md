---
paths:
  - "transaction-service/**"
  - "saga-it/**"
  - "config-server/src/main/resources/config-repo/transaction-service.yml"
---

# transaction-service — Saga, phí, hạn mức, OTP, back-office

## Saga chuyển tiền
- `TransactionService` kiểm tra tài khoản qua Redis → `TransactionPersistenceService` lưu giao dịch `PROCESSING` + lệnh đầu tiên vào `OUTBOX_EVENTS` trong một transaction → `OutboxPublisherService` (`@Scheduled` 500ms, `FOR UPDATE SKIP LOCKED`) publish.
- `TransactionSagaService` (sơ đồ trong Javadoc):
  - Thành công: `DEBIT_REQUESTED → CREDIT_REQUESTED → COMPLETED`. Cộng tiền lỗi: `COMPENSATION_REQUESTED → COMPENSATED`.
  - Nhận `ACCOUNT_DEBITED` khi giao dịch đã `FAILED` → tự gửi lệnh hoàn tiền.
  - Nhận `ACCOUNT_CREDITED` đến muộn (chuyển khoản đã `COMPENSATION_REQUESTED`/`COMPENSATED`, nộp tiền đã `FAILED`) → `MANUAL_REVIEW` với `failureReason = LATE_CREDIT_REASON`, giữ cả khi hoàn tiền về sau; nhân viên thu hồi thủ công.
  - Quá `bank.saga.step-timeout`: `SagaTimeoutMonitorService` gửi lại lệnh bước hiện tại (`resendCurrentCommand`, đếm `RETRY_COUNT`, reset khi sang bước mới); quá `bank.saga.max-retries` → `MANUAL_REVIEW`; kết quả đến muộn vẫn được áp dụng.
- Mọi event Saga phát ra qua `OutboxService.enqueue` (`Propagation.MANDATORY`); **không** gọi `KafkaTemplate` trực tiếp.
- API tạo giao dịch trả **202** + `PROCESSING`; tra kết quả `GET /api/transactions/{transactionRef}`.
- **Chuyển qua SĐT** (`transfer-by-phone`): `AccountLookupService.resolvePhoneAlias` (Redis `account.resolve-phone-alias`) rồi gọi lại `transfer` — không có loại giao dịch riêng. Retry cùng key trả giao dịch cũ, không phân giải lại.
- **Liên ngân hàng chiều đi** (`INTERBANK_OUT`, `interbank-transfer`): chỉ VND; tra tên qua `InterbankLookupService` (Redis `interbank.name-inquiry`), lưu `COUNTERPARTY_*`; `TO_ACCOUNT_NUMBER` null. Saga `DEBIT_REQUESTED → INTERBANK_REQUESTED` (lệnh `INTERBANK_TRANSFER_REQUESTED` lên `bank.interbank.commands`) → `INTERBANK_TRANSFER_COMPLETED` = COMPLETED / `REJECTED` = hoàn tiền + phí. **Timeout không bao giờ tự hoàn tiền** — `resendCurrentCommand` gửi lại lệnh (interbank-service idempotent, publish lại kết quả nếu đã có) rồi MANUAL_REVIEW. Kết quả trái ngược sau khi kết thúc → MANUAL_REVIEW (`LATE_INTERBANK_*_REASON`). OTP như `TRANSFER` (`confirmPending` nhận cả hai loại).
- **Liên ngân hàng tiền đến** (`INTERBANK_IN`): `InterbankEventConsumerService` (`bank.interbank.events`) → `createInboundTransfer`, key `0:ibin-<napasRef>`, `EXTERNAL_REF = napasRef`, không phí/hạn mức; Saga như DEPOSIT, kết quả báo lại `INTERBANK_INBOUND_SETTLED / RETURNED`. Đối soát coi `INTERBANK_OUT` như WITHDRAWAL, `INTERBANK_IN` như DEPOSIT.
- Bắt buộc header `Idempotency-Key` (8–40 ký tự), lưu `<userId>:<key>`: cùng key + cùng nội dung → trả giao dịch cũ; khác nội dung → 409.

## Phí (`FEE_SCHEDULES`, `FeeScheduleService`)
- Tính lúc tạo, lưu `TRANSACTIONS.FEE`. DEBIT/REFUND mang `fee` (`AccountCommandPayload.fee`): account trừ/hoàn `amount + fee` trên một bút toán và ghi phí vào tài khoản `INTERNAL` (customerId 0) trong cùng transaction; CREDIT không mang phí.
- `bank.fee.accounts.<currency>` là danh sách tài khoản thu phí (VND: 4 — script 12) để chia tải khóa dòng; chọn theo hash `transactionRef`. Hoàn phí tìm đúng tài khoản đã thu qua sổ cái (`findByTransactionRefOnAccountType`) nên thêm/bớt tài khoản an toàn.
- Tài khoản `INTERNAL` không được dùng trong giao dịch của khách hàng.

## Hạn mức (`TRANSACTION_LIMITS`, `TransactionLimitService`)
- Theo lần / tổng ngày / số lần ngày; kiểm tra trong transaction tạo giao dịch sau khi khóa dòng `ACCOUNT_LIMIT_LOCKS`; số đã dùng tính từ `TRANSACTIONS` (bỏ FAILED/COMPENSATED). Chỉ cache cấu hình.

## OTP
- CUSTOMER tự chuyển ≥ `bank.otp.thresholds.<currency>` (dev: VND 10.000.000, USD/EUR 500; gửi lại tối đa `max-resends` 3 lần, PENDING hết hạn sau `pending-timeout` 10 phút) → lưu `PENDING` (đã tính hạn mức, chưa ghi outbox).
- OTP 6 số lưu HMAC-SHA256 trong Redis (`bank:otp:<ref>` field `mac`, khóa `OTP_HMAC_SECRET` — thiếu hoặc < 256 bit thì service không khởi động; TTL 3 phút, sai tối đa 5 lần — đếm bằng Lua script chỉ HINCRBY khi `mac` còn tồn tại), gửi qua Redis action `notification.send-otp` (không qua outbox/Kafka/log).
- `POST /{ref}/confirm` → PROCESSING; `POST /{ref}/otp` gửi lại; `PendingTransactionExpiryService` hủy PENDING quá hạn.

## Back-office và định kỳ
- `TransactionOperationService` (retry giao dịch, replay outbox FAILED), `DeadLetterService`; `TransactionReconciliationService` (job 02:00 + API) đối soát giao dịch đã kết thúc với sổ cái qua `account.ledger-by-ref`.
- `DeadLetterConsumerService` (group `transaction-service-dlt`, retry vô hạn khi lỗi DB) lưu `<topic>.DLT` vào `DEAD_LETTER_EVENTS`; nhân viên replay (qua outbox, giữ eventId) hoặc bỏ qua.
- Chuyển tiền định kỳ: `ScheduledTransferRunnerService` (cron 5 phút) gọi `transferOnBehalf` với key `<createdBy>:sched-<id>-<yyyyMMdd>` (idempotent theo kỳ), không cần OTP; lỗi nghiệp vụ ghi `LAST_ERROR`, vẫn sang kỳ sau.

## Job (`@Scheduled`)
| Job | Lịch (property, mặc định) |
|---|---|
| `OutboxPublisherService` — publish outbox (tối đa `bank.outbox.max-retries` 10 lần → FAILED) | `bank.outbox.poll-interval` 500ms |
| `SagaTimeoutMonitorService` — gửi lại lệnh bước bị kẹt (`step-timeout` 2m, `max-retries` 3) | `bank.saga.poll-interval` 30s |
| `PendingTransactionExpiryService` — hủy giao dịch chờ OTP quá hạn | `bank.otp.expiry-check-interval` 60s |
| `ScheduledTransferRunnerService` — chuyển tiền định kỳ | `bank.scheduled-transfer.cron` mỗi 5 phút |
| `TransactionReconciliationService` — đối soát giao dịch ↔ sổ cái | `bank.reconciliation.cron` 02:00 |

## Saga E2E (`saga-it`)
- `mvn.cmd -pl saga-it -am verify -Pintegration-test -Dit.test=TransferSagaIT`. Chạy customer + account + transaction-service thật trong một JVM (mỗi service một Spring context, cấu hình lấy từ `config-repo`, chỉ thay địa chỉ Oracle/Kafka/Redis).
- Lỗi giữa lúc tạo và lúc trừ tiền được tái hiện tất định bằng cách giữ `SELECT ... FOR UPDATE` dòng tài khoản nguồn.
