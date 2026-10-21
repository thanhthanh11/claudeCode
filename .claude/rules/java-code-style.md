---
paths:
  - "**/*.java"
---

# Quy tắc code Java

- Java 21: dùng `record` cho DTO/event, pattern matching, switch expression.
- Package theo `com.bank.<service>.{dto,entity,controller,repository,service,enums,exception,config}`; hậu tố class đúng quy ước (`***Request`, `***Response`, `***Service`, `***Enum`, `***Exception`, `***Config`).
- Controller chỉ nhận/trả DTO bọc trong `ApiResponse<T>` (phân trang: `PageResponse<T>`), không gọi repository trực tiếp.
- Tiền tệ luôn là `BigDecimal`; so sánh bằng `compareTo`, không dùng `equals`, `double`, `float`.
- Exception nghiệp vụ kế thừa `BusinessException`; `GlobalExceptionHandler` chỉ `extends BaseExceptionHandler`.
- Giá trị cache Hazelcast phải `Serializable`; xóa cache **sau commit** (`CacheEvictionUtils.clearAfterCommit`).
- Không log mật khẩu, token, OTP, số thẻ, nội dung thông báo. Số tài khoản / số điện thoại / email che bằng `MaskingUtils` trước khi log.
- Thao tác của nhân viên phải gọi `AuditPublisher.publish(...)` với mã trong `AuditActions`.
- Chi tiết: xem `CLAUDE.md`.
