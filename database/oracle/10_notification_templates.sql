-- =============================================================
-- 10_notification_templates.sql — notification-service (schema BANK_NOTIFICATION)
-- Mẫu riêng cho nộp/rút tiền, thông báo người nhận chuyển khoản, OTP; hiển thị phí giao dịch.
-- Chạy bằng user bank_notification, đặt NLS_LANG=AMERICAN_AMERICA.AL32UTF8.
-- =============================================================
ALTER SESSION SET CURRENT_SCHEMA = BANK_NOTIFICATION;

UPDATE NOTIFICATION_TEMPLATES
   SET BODY = 'TK {accountNumber} -{amount} {currency} (phi {fee}) luc {time}. Ma GD: {transactionRef}. So du: {balance}',
       UPDATED_AT = SYSTIMESTAMP
 WHERE CODE = 'TRANSFER_SUCCESS' AND CHANNEL = 'SMS';
UPDATE NOTIFICATION_TEMPLATES
   SET BODY = 'Tài khoản {accountNumber} vừa chuyển {amount} {currency} (phí {fee} {currency}) lúc {time}. Mã giao dịch: {transactionRef}.',
       UPDATED_AT = SYSTIMESTAMP
 WHERE CODE = 'TRANSFER_SUCCESS' AND CHANNEL = 'EMAIL';

INSERT INTO NOTIFICATION_TEMPLATES (CODE, CHANNEL, SUBJECT, BODY)
VALUES ('TRANSFER_RECEIVED', 'SMS', NULL,
        'TK {accountNumber} +{amount} {currency} luc {time}. Ma GD: {transactionRef}. So du: {balance}');
INSERT INTO NOTIFICATION_TEMPLATES (CODE, CHANNEL, SUBJECT, BODY)
VALUES ('TRANSFER_RECEIVED', 'EMAIL', 'Thông báo nhận tiền',
        'Tài khoản {accountNumber} vừa nhận {amount} {currency} lúc {time}. Mã giao dịch: {transactionRef}.');

INSERT INTO NOTIFICATION_TEMPLATES (CODE, CHANNEL, SUBJECT, BODY)
VALUES ('DEPOSIT_SUCCESS', 'SMS', NULL,
        'TK {accountNumber} +{amount} {currency} (nop tien) luc {time}. Ma GD: {transactionRef}. So du: {balance}');
INSERT INTO NOTIFICATION_TEMPLATES (CODE, CHANNEL, SUBJECT, BODY)
VALUES ('DEPOSIT_SUCCESS', 'EMAIL', 'Thông báo nộp tiền thành công',
        'Tài khoản {accountNumber} vừa được nộp {amount} {currency} lúc {time}. Mã giao dịch: {transactionRef}.');
INSERT INTO NOTIFICATION_TEMPLATES (CODE, CHANNEL, SUBJECT, BODY)
VALUES ('DEPOSIT_FAILED', 'EMAIL', 'Thông báo nộp tiền không thành công',
        'Giao dịch nộp tiền {transactionRef} số tiền {amount} {currency} không thành công. Lý do: {reason}.');

INSERT INTO NOTIFICATION_TEMPLATES (CODE, CHANNEL, SUBJECT, BODY)
VALUES ('WITHDRAWAL_SUCCESS', 'SMS', NULL,
        'TK {accountNumber} -{amount} {currency} (rut tien, phi {fee}) luc {time}. Ma GD: {transactionRef}. So du: {balance}');
INSERT INTO NOTIFICATION_TEMPLATES (CODE, CHANNEL, SUBJECT, BODY)
VALUES ('WITHDRAWAL_SUCCESS', 'EMAIL', 'Thông báo rút tiền thành công',
        'Tài khoản {accountNumber} vừa rút {amount} {currency} (phí {fee} {currency}) lúc {time}. Mã giao dịch: {transactionRef}.');
INSERT INTO NOTIFICATION_TEMPLATES (CODE, CHANNEL, SUBJECT, BODY)
VALUES ('WITHDRAWAL_FAILED', 'EMAIL', 'Thông báo rút tiền không thành công',
        'Giao dịch rút tiền {transactionRef} số tiền {amount} {currency} không thành công. Lý do: {reason}.');

-- {otp} chỉ được thay khi gửi; nội dung lưu trong NOTIFICATIONS đã che mã OTP
INSERT INTO NOTIFICATION_TEMPLATES (CODE, CHANNEL, SUBJECT, BODY)
VALUES ('TRANSACTION_OTP', 'SMS', NULL,
        'Ma OTP xac nhan GD {transactionRef} so tien {amount} {currency}: {otp}. Hieu luc {ttlMinutes} phut. KHONG cung cap ma cho bat ky ai.');

COMMIT;
