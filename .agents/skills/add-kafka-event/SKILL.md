---
name: add-kafka-event
description: Thêm event/command Kafka mới (topic, event type, payload, producer qua outbox, consumer idempotent). Dùng khi mở rộng Saga hoặc thêm luồng bất đồng bộ giữa các service.
---

# Thêm Kafka event

1. Hằng số trong `common-lib/src/main/java/com/bank/common/event/`:
   - `EventTypes`: tên event dạng `UPPER_SNAKE_CASE` (`ACCOUNT_XXX_REQUESTED`, `ACCOUNT_XXX_FAILED`...).
   - `KafkaTopics`: chỉ thêm topic mới khi thật cần; khai báo topic + `.DLT` trong `CommonKafkaConfig`.
2. Payload là `record` (tiền dùng `BigDecimal`), bọc trong `EventEnvelope`; key = `transactionRef`.
3. Producer:
   - transaction-service: gọi `OutboxService.enqueue(...)` trong cùng transaction nghiệp vụ.
   - Service khác: gửi sau commit.
4. Consumer (`***ConsumerService` trong package `service`):
   - Idempotent (unique constraint hoặc kiểm tra bước Saga hiện tại).
   - Lỗi nghiệp vụ → phát event `*_FAILED`; lỗi kỹ thuật → ném ra để retry/DLT.
5. Nếu là bước Saga: cập nhật `TransactionSagaService` (sơ đồ Javadoc), `resendCurrentCommand`, và bước bù trừ.
6. Test: unit test consumer cho event trùng và event không khớp bước.
7. Cập nhật mục "Giao tiếp giữa các service" trong `AGENTS.md`.
