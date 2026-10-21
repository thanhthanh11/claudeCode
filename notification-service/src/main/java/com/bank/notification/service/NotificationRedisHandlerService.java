package com.bank.notification.service;

import com.bank.common.dto.OtpDeliveryRequest;
import com.bank.common.exception.BadRequestException;
import com.bank.common.exception.ForbiddenException;
import com.bank.common.redis.RedisActions;
import com.bank.common.redis.RedisRequestHandler;
import com.bank.common.security.AuthUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Xử lý yêu cầu gửi OTP từ transaction-service qua Redis. Chỉ chấp nhận service token.
 */
@Service
@RequiredArgsConstructor
public class NotificationRedisHandlerService implements RedisRequestHandler {

    private final OtpNotificationService otpNotificationService;
    private final ObjectMapper objectMapper;

    @Override
    public Set<String> supportedActions() {
        return Set.of(RedisActions.NOTIFICATION_SEND_OTP);
    }

    @Override
    public Object handle(String action, JsonNode payload, AuthUser caller) {
        if (!caller.isService()) {
            throw new ForbiddenException("Chỉ service nội bộ được yêu cầu gửi OTP");
        }
        OtpDeliveryRequest request;
        try {
            request = objectMapper.treeToValue(payload, OtpDeliveryRequest.class);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("INVALID_PAYLOAD", "Payload gửi OTP không hợp lệ");
        }
        if (request.customerId() == null || request.transactionRef() == null || request.otp() == null) {
            throw new BadRequestException("INVALID_PAYLOAD", "Thiếu thông tin gửi OTP");
        }
        otpNotificationService.sendOtp(request);
        return null;
    }
}
