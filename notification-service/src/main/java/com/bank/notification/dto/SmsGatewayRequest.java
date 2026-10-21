package com.bank.notification.dto;

/**
 * Body gửi tới SMS gateway HTTP ({@code bank.notification.sms.url}).
 */
public record SmsGatewayRequest(String to, String message, String sender) {
}
