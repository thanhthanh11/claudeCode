-- =============================================================
-- 11_transaction_outbox_trace.sql — transaction-service (schema BANK_TRANSACTION)
-- Lưu W3C traceparent cùng event outbox để trace không bị đứt qua bước publish bất đồng bộ.
-- Chạy bằng user bank_transaction, đặt NLS_LANG=AMERICAN_AMERICA.AL32UTF8.
-- =============================================================
ALTER SESSION SET CURRENT_SCHEMA = BANK_TRANSACTION;

ALTER TABLE OUTBOX_EVENTS ADD (TRACE_CONTEXT VARCHAR2(100 CHAR));
COMMENT ON COLUMN OUTBOX_EVENTS.TRACE_CONTEXT IS 'W3C traceparent lúc ghi event (tracing)';
