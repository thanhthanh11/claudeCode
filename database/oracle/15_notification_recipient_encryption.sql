-- =============================================================
-- 15_notification_recipient_encryption.sql — notification-service (schema BANK_NOTIFICATION)
-- NOTIFICATIONS.RECIPIENT (số điện thoại / email) mã hóa AES-256-GCM ở tầng ứng dụng ("enc:v1:<base64>").
-- Dữ liệu cũ được mã hóa bởi PiiEncryptionBackfillService khi notification-service khởi động.
-- Chạy bằng user bank_notification hoặc bank_admin, đặt NLS_LANG=AMERICAN_AMERICA.AL32UTF8.
-- =============================================================
ALTER SESSION SET CURRENT_SCHEMA = BANK_NOTIFICATION;

ALTER TABLE NOTIFICATIONS MODIFY (RECIPIENT VARCHAR2(300 CHAR));

COMMENT ON COLUMN NOTIFICATIONS.RECIPIENT IS 'Người nhận (SĐT / email), mã hóa AES-GCM (enc:v1:...)';
