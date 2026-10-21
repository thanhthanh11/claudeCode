-- =============================================================
-- 06_transaction_saga_timeout.sql — transaction-service (schema BANK_TRANSACTION)
-- Theo dõi giao dịch Saga bị kẹt: đếm số lần gửi lại lệnh và trạng thái MANUAL_REVIEW.
-- Chạy sau 04_transaction_schema.sql, bằng user bank_transaction:
--   NLS_LANG=AMERICAN_AMERICA.AL32UTF8 sqlplus "bank_transaction/ChangeMe_Transaction1@(...)" @database/oracle/06_transaction_saga_timeout.sql
-- =============================================================
ALTER SESSION SET CURRENT_SCHEMA = BANK_TRANSACTION;

ALTER TABLE TRANSACTIONS ADD (RETRY_COUNT NUMBER(5) DEFAULT 0 NOT NULL);
COMMENT ON COLUMN TRANSACTIONS.RETRY_COUNT IS 'Số lần gửi lại lệnh của bước Saga hiện tại do quá thời gian chờ';

ALTER TABLE TRANSACTIONS DROP CONSTRAINT CK_TRANSACTIONS_STATUS;
ALTER TABLE TRANSACTIONS ADD CONSTRAINT CK_TRANSACTIONS_STATUS
    CHECK (STATUS IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'COMPENSATED', 'MANUAL_REVIEW'));

-- Phục vụ job quét giao dịch quá hạn theo trạng thái + thời điểm cập nhật
DROP INDEX IX_TRANSACTIONS_STATUS;
CREATE INDEX IX_TRANSACTIONS_STATUS ON TRANSACTIONS (STATUS, UPDATED_AT);
