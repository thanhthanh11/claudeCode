-- =============================================================
-- setup_admin_user.sql
-- Tạo account quản trị BANK_ADMIN để chạy các script 00_ → 05_.
-- Chạy MỘT LẦN bằng SYS AS SYSDBA; sau đó không dùng SYS nữa.
-- =============================================================

CREATE USER BANK_ADMIN IDENTIFIED BY "123456" DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS;

GRANT CREATE SESSION TO BANK_ADMIN;
GRANT CREATE USER, ALTER USER, DROP USER TO BANK_ADMIN;

-- WITH ADMIN OPTION để BANK_ADMIN cấp lại các quyền này cho schema BANK_* (script 00_)
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE VIEW TO BANK_ADMIN WITH ADMIN OPTION;

-- Tạo/sửa đối tượng và seed dữ liệu trong schema khác (ALTER SESSION SET CURRENT_SCHEMA)
GRANT CREATE ANY TABLE, ALTER ANY TABLE, DROP ANY TABLE, CREATE ANY INDEX, CREATE ANY SEQUENCE,
      COMMENT ANY TABLE, SELECT ANY TABLE, INSERT ANY TABLE, UPDATE ANY TABLE, DELETE ANY TABLE TO BANK_ADMIN;

-- Bắt buộc để tạo bảng có cột IDENTITY trong schema khác (thiếu → ORA-01031 ở script 02_ → 05_).
-- Lưu ý: DB local hiện tại CHƯA được cấp quyền này (bảng 02_ → 05_ đã tạo bằng user chủ schema).
GRANT SELECT ANY SEQUENCE TO BANK_ADMIN;

-- Đọc data dictionary (DBA_USERS, V$...) để kiểm tra
GRANT SELECT_CATALOG_ROLE TO BANK_ADMIN;
