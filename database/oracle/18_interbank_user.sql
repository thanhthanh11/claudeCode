-- =============================================================
-- 18_interbank_user.sql — tạo schema BANK_INTERBANK cho interbank-service (kết nối Napas)
-- Chạy bằng user BANK_ADMIN (có CREATE USER). Đổi mật khẩu trước khi dùng ngoài môi trường dev.
-- Sau đó chạy 19_interbank_schema.sql bằng user bank_interbank.
-- =============================================================

CREATE USER BANK_INTERBANK IDENTIFIED BY "ChangeMe_Interbank1" DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS;
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE VIEW TO BANK_INTERBANK;
