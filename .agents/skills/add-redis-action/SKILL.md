---
name: add-redis-action
description: Thêm một luồng request/reply đồng bộ mới giữa các service qua Redis (RedisActions + RedisRequestHandler). Dùng khi service A cần lấy dữ liệu của service B theo thời gian thực.
---

# Thêm Redis action

1. Khai báo hằng trong `common-lib/src/main/java/com/bank/common/redis/RedisActions.java`, dạng `<service>.<dong-tu>-<doi-tuong>` (vd. `account.get-by-number`).
2. Tạo DTO request/response là `record` trong `common-lib` (nếu dùng chung) hoặc ở service đích.
3. Ở service đích tạo `com.bank.<service>.service.<Xxx>RedisHandlerService implements RedisRequestHandler`:
   - Đăng ký action trong handler.
   - **Tự kiểm tra quyền của caller**: chủ dữ liệu / nhân viên (`TELLER`, `ADMIN`) / service token (`roles=[SERVICE]`).
   - Lỗi nghiệp vụ ném `BusinessException` (status + mã lỗi được giữ nguyên qua `RemoteServiceException`).
4. Ở service nguồn gọi `RedisRequestReplyClient.send(targetService, action, payload, type)`; action chỉ cho service gọi bằng `sendAsService`.
5. Không đưa số dư/dữ liệu nhạy cảm vào log; che số tài khoản bằng `MaskingUtils`.
6. Viết unit test cho handler (case: đủ quyền, không đủ quyền, không tìm thấy).
7. Cập nhật danh sách action trong `AGENTS.md` mục "Redis — kênh xử lý request/response".
