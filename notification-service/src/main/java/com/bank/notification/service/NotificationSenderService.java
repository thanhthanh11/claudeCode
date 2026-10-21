package com.bank.notification.service;

import com.bank.common.util.MaskingUtils;
import com.bank.notification.enums.ChannelEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Cổng gửi email/SMS: định tuyến theo kênh tới {@link NotificationGatewayService} được bật bằng
 * {@code bank.notification.email.provider} ({@code smtp}) / {@code bank.notification.sms.provider} ({@code http}).
 * Kênh có provider {@code log} chạy giả lập (chỉ ghi log, không lộ nội dung) — chỉ dùng cho dev.
 * Provider khác {@code log} mà không có gateway tương ứng → service không khởi động (tránh lặng lẽ giả lập ở
 * môi trường thật). Ném {@link com.bank.notification.exception.NotificationSendException} khi gửi lỗi.
 */
@Slf4j
@Service
public class NotificationSenderService {

    static final String LOG_PROVIDER = "log";

    private final Map<ChannelEnum, NotificationGatewayService> gateways = new EnumMap<>(ChannelEnum.class);

    public NotificationSenderService(List<NotificationGatewayService> gatewayBeans,
                                     @Value("${bank.notification.email.provider:log}") String emailProvider,
                                     @Value("${bank.notification.sms.provider:log}") String smsProvider) {
        for (NotificationGatewayService gateway : gatewayBeans) {
            if (gateways.put(gateway.channel(), gateway) != null) {
                throw new IllegalStateException("Có nhiều gateway cho kênh " + gateway.channel());
            }
        }
        verifyProvider(ChannelEnum.EMAIL, emailProvider);
        verifyProvider(ChannelEnum.SMS, smsProvider);
    }

    private void verifyProvider(ChannelEnum channel, String provider) {
        boolean hasGateway = gateways.containsKey(channel);
        if (LOG_PROVIDER.equalsIgnoreCase(provider) && !hasGateway) {
            log.warn("Kênh {} đang chạy giả lập (provider=log): thông báo chỉ được ghi log, không gửi thật", channel);
        } else if (!hasGateway) {
            throw new IllegalStateException("Không hỗ trợ provider '" + provider + "' cho kênh " + channel);
        } else {
            log.info("Kênh {} gửi qua {}", channel, gateways.get(channel).getClass().getSimpleName());
        }
    }

    public void send(ChannelEnum channel, String recipient, String subject, String content) {
        NotificationGatewayService gateway = gateways.get(channel);
        if (gateway == null) {
            log.info("[MOCK {}] Gửi thông báo tới {} ({} ký tự)", channel, MaskingUtils.maskRecipient(recipient),
                    content.length());
            return;
        }
        gateway.send(recipient, subject, content);
        log.info("Đã gửi {} tới {} ({} ký tự)", channel, MaskingUtils.maskRecipient(recipient), content.length());
    }
}
