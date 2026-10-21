package com.bank.notification.service;

import com.bank.notification.exception.NotificationSendException;
import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmtpEmailGatewayServiceTest {

    private JavaMailSender mailSender;
    private SmtpEmailGatewayService gateway;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage())
                .thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
        gateway = new SmtpEmailGatewayService(mailSender, "no-reply@bank.local", "Ngân hàng");
    }

    @Test
    void shouldSendUtf8PlainTextEmail() throws Exception {
        gateway.send("khach@example.com", "Thông báo giao dịch", "Tài khoản ****1234 vừa nhận 1.000 VND");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage message = captor.getValue();
        assertThat(message.getSubject()).isEqualTo("Thông báo giao dịch");
        assertThat(((InternetAddress) message.getFrom()[0]).getPersonal()).isEqualTo("Ngân hàng");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("khach@example.com");
        assertThat(message.getContent()).isEqualTo("Tài khoản ****1234 vừa nhận 1.000 VND");
    }

    @Test
    void shouldNotRetryRejectedRecipient() throws Exception {
        Address invalid = new InternetAddress("khach@example.com");
        SendFailedException rejected = new SendFailedException("550 no such user", null,
                new Address[0], new Address[0], new Address[]{invalid});
        doThrow(new MailSendException(Map.of(new Object(), rejected))).when(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> gateway.send("khach@example.com", "s", "c"))
                .isInstanceOfSatisfying(NotificationSendException.class, e -> assertThat(e.isRetryable()).isFalse());
    }

    @Test
    void shouldRetryOnConnectionError() {
        doThrow(new MailSendException("Mail server connection failed", new MessagingException("timeout")))
                .when(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> gateway.send("khach@example.com", "s", "c"))
                .isInstanceOfSatisfying(NotificationSendException.class, e -> assertThat(e.isRetryable()).isTrue());
    }

    @Test
    void shouldNotRetryMalformedRecipient() {
        assertThatThrownBy(() -> gateway.send("not an email@@", "s", "c"))
                .isInstanceOfSatisfying(NotificationSendException.class, e -> assertThat(e.isRetryable()).isFalse());
    }
}
