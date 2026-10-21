package com.bank.notification.dto;

import com.bank.notification.enums.ChannelEnum;

import java.io.Serializable;

/**
 * Serializable vì được cache trong Hazelcast.
 */
public record NotificationTemplateResponse(
        Long id,
        String code,
        ChannelEnum channel,
        String subject,
        String body,
        Boolean active) implements Serializable {
}
