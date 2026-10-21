-- =============================================================
-- 14_auth_email_encryption.sql — auth-service (schema BANK_AUTH)
-- USERS.EMAIL mã hóa AES-256-GCM ở tầng ứng dụng ("enc:v1:<base64>"); kiểm tra trùng email chuyển sang cột blind
-- index EMAIL_HASH (HMAC-SHA256 của email đã trim + lowercase).
-- Dữ liệu cũ được mã hóa và tính hash bởi PiiEncryptionBackfillService khi auth-service khởi động.
-- Chạy bằng user bank_auth hoặc bank_admin, đặt NLS_LANG=AMERICAN_AMERICA.AL32UTF8.
-- =============================================================
ALTER SESSION SET CURRENT_SCHEMA = BANK_AUTH;

ALTER TABLE USERS DROP CONSTRAINT UK_USERS_EMAIL;
ALTER TABLE USERS MODIFY (EMAIL VARCHAR2(300 CHAR));
ALTER TABLE USERS ADD (EMAIL_HASH VARCHAR2(64 CHAR));
CREATE UNIQUE INDEX UK_USERS_EMAIL_HASH ON USERS (EMAIL_HASH);

COMMENT ON COLUMN USERS.EMAIL IS 'Email, mã hóa AES-GCM (enc:v1:...)';
COMMENT ON COLUMN USERS.EMAIL_HASH IS 'Blind index HMAC-SHA256 của email đã chuẩn hóa (unique)';
