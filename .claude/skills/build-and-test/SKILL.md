---
name: build-and-test
description: Build và chạy test Maven cho một hoặc nhiều service, kể cả integration test Testcontainers. Dùng khi cần kiểm chứng thay đổi code.
---

# Build và test

Trong Git Bash gọi `mvn.cmd` (`mvn` có thể lỗi "Could not find or load main class ...Launcher" và in ra rỗng); chỉ kết luận khi thấy `BUILD SUCCESS`.

Xác định module bị ảnh hưởng từ các file đã sửa, rồi chạy lệnh nhỏ nhất đủ kiểm chứng:

| Mục đích | Lệnh |
|---|---|
| Unit test một service | `mvn.cmd -pl <service> -am test` |
| Một class / method | `mvn.cmd -pl <service> test -Dtest=ClassName#method` |
| Sửa `common-lib` | `mvn.cmd -pl common-lib -amd test` (kèm các service phụ thuộc) |
| Integration test (cần Docker) | `mvn.cmd -pl account-service,transaction-service -am verify -Pintegration-test` |
| Saga E2E | `mvn.cmd -pl saga-it -am verify -Pintegration-test -Dit.test=TransferSagaIT` |
| Test (kể cả IT) không đóng gói jar | `mvn.cmd -pl <service> -am test-compile surefire:test failsafe:integration-test failsafe:verify -Pintegration-test` |
| Build toàn bộ | `mvn.cmd clean install -DskipTests` |

- `*Test`: unit Mockito (common-lib, account, transaction, notification), chạy mặc định.
- `*IT`: Testcontainers Oracle Free 23, chỉ khi bật `-Pintegration-test`; dùng `src/test/resources/application-it.yml` (thay toàn bộ cấu hình, tắt config client), tạo schema bằng script thật trong `database/oracle/`.
- Jar: customer/account/transaction-service đóng fat jar classifier `exec` (`<module>-1.0.0-SNAPSHOT-exec.jar`; jar chính làm dependency cho `saga-it`); module khác là `<module>-1.0.0-SNAPSHOT.jar`.

Xử lý lỗi thường gặp:
- `Unable to rename ... .jar` (Windows): service đang chạy khóa jar → dừng service, loại module (`-pl '!discovery-server,!config-server'`) hoặc dùng lệnh "không đóng gói jar" ở trên.
- Hibernate `validate` lỗi trong IT: thiếu script migration trong danh sách script của IT (`AccountBalanceConcurrencyIT`, `TransactionLimitConcurrencyIT`, `TransferSagaIT.createSchemas()`), hoặc mapping sai (`CHAR(3)`, `NUMBER(1)`).

Báo cáo kết quả: số test chạy/lỗi; nếu lỗi, trích đoạn stack trace liên quan.
