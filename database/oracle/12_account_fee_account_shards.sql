-- =============================================================
-- 12_account_fee_account_shards.sql — account-service (schema BANK_ACCOUNT)
-- Thêm tài khoản nội bộ thu phí VND để chia tải khóa dòng: mỗi giao dịch có phí ghi vào một trong các
-- tài khoản thu phí của loại tiền (chọn theo hash transactionRef) thay vì cùng khóa một dòng.
-- Danh sách tài khoản khai báo ở bank.fee.accounts.<currency> (config-repo/account-service.yml).
-- Chạy bằng user bank_account hoặc bank_admin, đặt NLS_LANG=AMERICAN_AMERICA.AL32UTF8.
-- =============================================================
ALTER SESSION SET CURRENT_SCHEMA = BANK_ACCOUNT;

INSERT INTO ACCOUNTS (ACCOUNT_NUMBER, CUSTOMER_ID, ACCOUNT_TYPE, CURRENCY, STATUS, OPENED_AT)
VALUES ('990000000004', 0, 'INTERNAL', 'VND', 'ACTIVE', SYSTIMESTAMP);
INSERT INTO ACCOUNTS (ACCOUNT_NUMBER, CUSTOMER_ID, ACCOUNT_TYPE, CURRENCY, STATUS, OPENED_AT)
VALUES ('990000000005', 0, 'INTERNAL', 'VND', 'ACTIVE', SYSTIMESTAMP);
INSERT INTO ACCOUNTS (ACCOUNT_NUMBER, CUSTOMER_ID, ACCOUNT_TYPE, CURRENCY, STATUS, OPENED_AT)
VALUES ('990000000006', 0, 'INTERNAL', 'VND', 'ACTIVE', SYSTIMESTAMP);

COMMIT;
