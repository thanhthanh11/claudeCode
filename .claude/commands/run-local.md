---
description: Hướng dẫn / kiểm tra thứ tự khởi động hệ thống local (hạ tầng + service)
argument-hint: "[tên service cần chạy, bỏ trống = toàn bộ]"
---

Giúp tôi chạy hệ thống local cho: $ARGUMENTS

1. Kiểm tra hạ tầng bằng `docker compose ps` (Redis 6379, Kafka 9092, Jaeger 16686, Mailpit 1025/8025). Nếu chưa chạy, đề xuất `docker compose up -d`.
2. Kiểm tra Oracle local (`localhost:1521/orcl`) có kết nối được không.
3. Thứ tự khởi động: `discovery-server` (8761) → `config-server` (8888) → auth/customer/account/transaction/notification/interbank (8086) → `api-gateway` (8080).
   Lệnh: `mvn -pl <module> spring-boot:run` (chạy nền).
4. Đợi ~30 giây để service đăng ký Eureka, kiểm tra `http://localhost:8761`.
5. Báo lại service nào đã lên, service nào lỗi (kèm dòng log lỗi đầu tiên).
6. Gọi API qua `http://localhost:8080` (ADMIN mặc định `admin` / `Admin@123456`). Giao diện: Swagger `http://localhost:8080/swagger-ui.html`, Jaeger `:16686`, Grafana `:3000` (admin/admin), Mailpit `:8025`.
7. Git Bash: JSON tiếng Việt gửi bằng file (`curl --data-binary @file.json`), truyền trực tiếp sẽ sai mã hóa → 400. `docker exec` cần `MSYS_NO_PATHCONV=1`.

Không dừng service đang chạy hoặc xóa volume Docker khi chưa hỏi tôi.
