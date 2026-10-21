-- =============================================================
-- 13_customer_pii_encryption.sql — customer-service (schema BANK_CUSTOMER)
-- Mã hóa PII bằng AES-256-GCM ở tầng ứng dụng (EncryptedStringConverter): ID_NUMBER, PHONE, EMAIL, ADDRESS lưu
-- dạng "enc:v1:<base64>" nên nới độ dài cột. Ciphertext khác nhau mỗi lần ghi → unique / tìm kiếm chuyển sang
-- cột blind index HMAC-SHA256 (ID_NUMBER_HASH gồm cả loại giấy tờ, PHONE_HASH).
-- Dữ liệu cũ được mã hóa và tính hash bởi PiiEncryptionBackfillService khi customer-service khởi động.
-- Chạy bằng user bank_customer hoặc bank_admin, đặt NLS_LANG=AMERICAN_AMERICA.AL32UTF8.
-- =============================================================
ALTER SESSION SET CURRENT_SCHEMA = BANK_CUSTOMER;

-- Unique / index trên plaintext không còn ý nghĩa với ciphertext ngẫu nhiên
ALTER TABLE CUSTOMERS DROP CONSTRAINT UK_CUSTOMERS_ID_NUMBER;
DROP INDEX IX_CUSTOMERS_PHONE;

ALTER TABLE CUSTOMERS MODIFY (
    ID_NUMBER VARCHAR2(100 CHAR),
    PHONE     VARCHAR2(100 CHAR),
    EMAIL     VARCHAR2(300 CHAR),
    ADDRESS   VARCHAR2(4000 CHAR)
);

ALTER TABLE CUSTOMERS ADD (
    ID_NUMBER_HASH VARCHAR2(64 CHAR),
    PHONE_HASH     VARCHAR2(64 CHAR)
);

-- Unique một cột cho phép NULL (dòng cũ chưa backfill) — hash đã gắn loại giấy tờ
CREATE UNIQUE INDEX UK_CUSTOMERS_ID_NUMBER_HASH ON CUSTOMERS (ID_NUMBER_HASH);
CREATE INDEX IX_CUSTOMERS_PHONE_HASH ON CUSTOMERS (PHONE_HASH);

COMMENT ON COLUMN CUSTOMERS.ID_NUMBER IS 'Số giấy tờ, mã hóa AES-GCM (enc:v1:...)';
COMMENT ON COLUMN CUSTOMERS.PHONE IS 'Số điện thoại, mã hóa AES-GCM (enc:v1:...)';
COMMENT ON COLUMN CUSTOMERS.EMAIL IS 'Email, mã hóa AES-GCM (enc:v1:...)';
COMMENT ON COLUMN CUSTOMERS.ADDRESS IS 'Địa chỉ, mã hóa AES-GCM (enc:v1:...)';
COMMENT ON COLUMN CUSTOMERS.ID_NUMBER_HASH IS 'Blind index HMAC-SHA256 của ID_TYPE:ID_NUMBER (unique, tra cứu)';
COMMENT ON COLUMN CUSTOMERS.PHONE_HASH IS 'Blind index HMAC-SHA256 của số điện thoại đã chuẩn hóa (tìm kiếm)';
