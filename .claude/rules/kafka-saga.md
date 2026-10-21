---
paths:
  - "transaction-service/**/*.java"
  - "account-service/**/*.java"
  - "notification-service/**/*.java"
  - "auth-service/**/*.java"
  - "common-lib/**/event/**"
---

# Quy tắc Kafka / Saga

- Topic và event type khai báo hằng ở `common-lib` (`KafkaTopics`, `EventTypes`); value JSON `EventEnvelope{eventId, eventType, occurredAt, payload}` (`EventSerializer`), key = `transactionRef`. Trên dây value được mã hóa AES-GCM (serializer/deserializer gắn trong `CommonKafkaConfig`) — đọc topic bằng `kafka-console-consumer` chỉ thấy `enc:v1:...`; code nghiệp vụ không cần xử lý gì thêm. Thêm event mới: skill `add-kafka-event`.

  | Topic | Event | Hướng |
  |---|---|---|
  | `bank.account.commands` | `ACCOUNT_DEBIT/CREDIT/REFUND_REQUESTED` | transaction → account |
  | `bank.transaction.events` | `ACCOUNT_DEBITED`, `ACCOUNT_CREDITED`, `ACCOUNT_REFUNDED`, `*_FAILED` | account → transaction |
  | `bank.notification.events` | `TRANSACTION_COMPLETED` / `TRANSACTION_FAILED` | transaction → notification |
  | `bank.audit.events` | `AUDIT_RECORDED` | mọi service → auth |
  | `<topic>.DLT` | message lỗi sau retry | → `DeadLetterConsumerService` (transaction) |

- transaction-service: mọi event Saga phát ra đi qua `OutboxService.enqueue` (`Propagation.MANDATORY`), **không** gọi `KafkaTemplate` trực tiếp.
- Consumer phải idempotent:
  - account-service: unique `ACCOUNT_ID, TRANSACTION_REF, ENTRY_TYPE`.
  - Saga bỏ qua event không khớp bước hiện tại.
  - notification-service: unique `EVENT_ID, CHANNEL`.
- account-service: khóa dòng tài khoản bằng `SELECT ... FOR UPDATE`, ghi sổ cái, commit rồi mới gửi kết quả và ack. Lỗi nghiệp vụ trả event `*_FAILED`, không ném exception.
- Lỗi kỹ thuật để `DefaultErrorHandler` retry rồi đẩy sang `<topic>.DLT`.
- Khi Kafka tạm lỗi, `send().get(5s)` có thể timeout nhưng message vẫn được gửi sau đó → outbox ghi FAILED/NEW dù message đã đi; idempotency phía consumer xử lý trùng.
- Khi thêm bước Saga mới: cập nhật sơ đồ trong Javadoc `TransactionSagaService` và `SagaTimeoutMonitorService.resendCurrentCommand`.
