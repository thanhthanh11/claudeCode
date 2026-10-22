---
name: add-oracle-migration
description: Tạo script migration Oracle mới (ALTER/CREATE) trong database/oracle/ và đăng ký vào integration test. Dùng khi thêm/sửa bảng, cột, index hoặc dữ liệu seed.
---

# Thêm migration Oracle

1. Xác định số tiếp theo: `ls database/oracle/` → lấy số lớn nhất + 1 (vd. `12_`).
2. Tạo file `database/oracle/NN_<schema>_<mo-ta>.sql`:
   - Dòng đầu ghi chú: schema, mục đích, user cần chạy.
   - Đặt `ALTER SESSION SET CURRENT_SCHEMA = BANK_<SCHEMA>;` nếu chạy bằng `BANK_ADMIN` (chỉ khi không tạo cột IDENTITY).
   - Tuân thủ quy ước trong `.Codex/rules/oracle-migration.md`.
   - Không sửa file migration đã có (`00_` trở đi).
3. Cập nhật entity tương ứng (xem `.Codex/rules/jpa-entity.md`).
4. Nếu schema là `BANK_ACCOUNT` hoặc `BANK_TRANSACTION`: thêm tên file vào danh sách script của IT
   (`AccountBalanceConcurrencyIT` / `TransactionLimitConcurrencyIT`). Schema `BANK_CUSTOMER` / `BANK_ACCOUNT` /
   `BANK_TRANSACTION`: thêm luôn vào `createSchemas()` của `saga-it/.../TransferSagaIT`. Schema `BANK_AUTH`: thêm vào
   `RefreshTokenConcurrencyIT` (auth-service).
5. Nếu thêm permission: seed vào `BANK_AUTH.PERMISSIONS` và hằng trong `PermissionCodes`.
6. Chạy thử trên DB local:
   ```bash
   NLS_LANG=AMERICAN_AMERICA.AL32UTF8 sqlplus "<user>/<pass>@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=localhost)(PORT=1521))(CONNECT_DATA=(SID=orcl)))" @database/oracle/NN_xxx.sql
   ```
7. Kiểm tra: `mvn -pl <service> -am verify -Pintegration-test` (Hibernate `validate` sẽ bắt lệch mapping).
