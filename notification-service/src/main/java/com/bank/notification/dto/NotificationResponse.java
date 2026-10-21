package com.bank.notification.dto;

import com.bank.notification.enums.ChannelEnum;
import com.bank.notification.enums.NotificationStatusEnum;

import java.time.LocalDateTime;

public record NotificationResponse(
        Long id,
        String templateCode,
        ChannelEnum channel,
        String recipient,
        String subject,
        String content,
        NotificationStatusEnum status,
        Integer retryCount,
        LocalDateTime sentAt,
        LocalDateTime createdAt) {
}
