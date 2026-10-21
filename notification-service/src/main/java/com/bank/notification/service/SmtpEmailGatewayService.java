package com.bank.notification.service;

import com.bank.notification.enums.ChannelEnum;
import com.bank.notification.exception.NotificationSendException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

/**
 * Gửi email text thuần (UTF-8) qua SMTP ({@code spring.mail.*}). Bật bằng
 * {@code bank.notification.email.provider=smtp}. Địa chỉ người nhận bị máy chủ từ chối → lỗi vĩnh viễn;
 * mất kết nối / máy chủ tạm lỗi → lỗi tạm thời (job retry gửi lại).
 */
@Service
@ConditionalOnProperty(prefix = "bank.notification.email", name = "provider", havingValue = "smtp")
public class SmtpEmailGatewayService implements NotificationGatewayService {

    private final JavaMailSender mailSender;
    private final InternetAddress from;

    public SmtpEmailGatewayService(JavaMailSender mailSender,
                                   @Value("${bank.notification.email.from}") String fromAddress,
                                   @Value("${bank.notification.email.from-name:}") String fromName) {
        this.mailSender = mailSender;
        try {
            this.from = new InternetAddress(fromAddress, fromName.isBlank() ? null : fromName,
                    StandardCharsets.UTF_8.name());
            this.from.validate();
        } catch (UnsupportedEncodingException | MessagingException e) {
            throw new IllegalStateException("bank.notification.email.from không hợp lệ", e);
        }
    }

    @Override
    public ChannelEnum channel() {
        return ChannelEnum.EMAIL;
    }

    @Override
    public void send(String recipient, String subject, String content) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(recipient);
            helper.setSubject(subject == null ? "" : subject);
            helper.setText(content, false);
        } catch (MessagingException e) {
            throw new NotificationSendException("Địa chỉ người nhận hoặc nội dung email không hợp lệ", e, false);
        }
        try {
            mailSender.send(message);
        } catch (MailParseException | MailPreparationException e) {
            throw new NotificationSendException("Không tạo được email", e, false);
        } catch (MailSendException e) {
            boolean rejected = isRecipientRejected(e);
            throw new NotificationSendException(rejected ? "Máy chủ SMTP từ chối địa chỉ người nhận"
                    : "Gửi email qua SMTP thất bại", e, !rejected);
        } catch (MailException e) {
            throw new NotificationSendException("Gửi email qua SMTP thất bại", e, true);
        }
    }

    static boolean isRecipientRejected(MailSendException e) {
        return e.getFailedMessages().values().stream().anyMatch(cause -> cause instanceof SendFailedException failed
                && failed.getInvalidAddresses() != null && failed.getInvalidAddresses().length > 0);
    }
}
