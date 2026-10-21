package com.bank.notification.service;

import com.bank.notification.entity.Notification;
import com.bank.notification.enums.ChannelEnum;
import com.bank.notification.enums.NotificationStatusEnum;
import com.bank.notification.exception.NotificationSendException;
import com.bank.notification.repository.NotificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationSenderServiceTest {

    private static final int MAX_RETRIES = 3;

    @Test
    void shouldRouteToGatewayOfChannel() {
        NotificationGatewayService sms = gateway(ChannelEnum.SMS);
        NotificationSenderService sender = new NotificationSenderService(List.of(sms), "log", "http");

        sender.send(ChannelEnum.SMS, "0912345678", null, "noi dung");
        sender.send(ChannelEnum.EMAIL, "khach@example.com", "s", "noi dung"); // provider=log → chỉ ghi log

        verify(sms).send("0912345678", null, "noi dung");
    }

    @Test
    void shouldFailStartupWhenProviderHasNoGateway() {
        assertThatThrownBy(() -> new NotificationSenderService(List.of(), "sendgrid", "log"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sendgrid");
    }

    @Test
    void shouldRejectTwoGatewaysForSameChannel() {
        assertThatThrownBy(() -> new NotificationSenderService(
                List.of(gateway(ChannelEnum.SMS), gateway(ChannelEnum.SMS)), "log", "http"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldNotRetryPermanentFailure() {
        NotificationGatewayService sms = gateway(ChannelEnum.SMS);
        doThrow(new NotificationSendException("SMS gateway từ chối yêu cầu: HTTP 400", false))
                .when(sms).send("09", null, "c");
        Notification notification = dispatch(sms, "09");

        assertThat(notification.getStatus()).isEqualTo(NotificationStatusEnum.FAILED);
        assertThat(notification.getRetryCount()).isEqualTo(MAX_RETRIES);
    }

    @Test
    void shouldRetryTransientFailure() {
        NotificationGatewayService sms = gateway(ChannelEnum.SMS);
        doThrow(new NotificationSendException("SMS gateway lỗi: HTTP 502", true)).when(sms).send("09", null, "c");
        Notification notification = dispatch(sms, "09");

        assertThat(notification.getStatus()).isEqualTo(NotificationStatusEnum.FAILED);
        assertThat(notification.getRetryCount()).isEqualTo(1);
    }

    private static Notification dispatch(NotificationGatewayService gateway, String recipient) {
        NotificationService service = new NotificationService(mock(NotificationRepository.class),
                mock(NotificationTemplateService.class),
                new NotificationSenderService(List.of(gateway), "log", "http"),
                mock(AccountLookupService.class), mock(CustomerLookupService.class), new ObjectMapper(), MAX_RETRIES);
        Notification notification = new Notification();
        notification.setChannel(gateway.channel());
        notification.setRecipient(recipient);
        notification.setContent("c");
        service.dispatch(notification);
        return notification;
    }

    private static NotificationGatewayService gateway(ChannelEnum channel) {
        NotificationGatewayService gateway = mock(NotificationGatewayService.class);
        when(gateway.channel()).thenReturn(channel);
        return gateway;
    }
}
