---
paths:
  - "**/src/test/**"
---

# Quy tắc test

- `*Test`: unit test Mockito, không cần DB/Kafka/Redis, chạy mặc định khi `mvn test`.
- `*IT`: integration test Testcontainers Oracle Free 23 (cần Docker), chỉ chạy với `-Pintegration-test`.
- IT dùng `src/test/resources/application-it.yml` (thay toàn bộ cấu hình, tắt config client) và tạo schema bằng script thật trong `database/oracle/`.
- Tên method test mô tả hành vi: `shouldDebitWhenBalanceIsSufficient`.
- Assert số tiền bằng `isEqualByComparingTo` (AssertJ), không dùng `isEqualTo` với `BigDecimal`.
- Luồng tiền cần test cả trường hợp xử lý trùng (idempotent) và đồng thời (concurrency).
- Saga liên service: thêm kịch bản vào `saga-it/TransferSagaIT` (Oracle + Kafka + Redis thật); assert phí bằng tổng số dư các tài khoản `INTERNAL` vì phí chia theo hash `transactionRef`.
