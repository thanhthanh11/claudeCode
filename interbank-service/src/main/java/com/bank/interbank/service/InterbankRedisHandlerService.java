package com.bank.interbank.service;

import com.bank.common.dto.NameInquiryRequest;
import com.bank.common.exception.BadRequestException;
import com.bank.common.exception.ForbiddenException;
import com.bank.common.redis.RedisActions;
import com.bank.common.redis.RedisRequestHandler;
import com.bank.common.security.AuthUser;
import com.bank.common.security.PermissionCodes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Tra tên người nhận ở ngân hàng khác cho transaction-service (Redis action {@code interbank.name-inquiry}).
 * Cho phép service nội bộ hoặc người dùng có quyền tạo giao dịch.
 */
@Service
@RequiredArgsConstructor
public class InterbankRedisHandlerService implements RedisRequestHandler {

    private final NameInquiryService nameInquiryService;
    private final ObjectMapper objectMapper;

    @Override
    public Set<String> supportedActions() {
        return Set.of(RedisActions.INTERBANK_NAME_INQUIRY);
    }

    @Override
    public Object handle(String action, JsonNode payload, AuthUser caller) {
        if (!RedisActions.INTERBANK_NAME_INQUIRY.equals(action)) {
            throw new BadRequestException("UNKNOWN_ACTION", "Action không được hỗ trợ: " + action);
        }
        if (!caller.isService() && !caller.hasPermission(PermissionCodes.TRANSFER_CREATE)) {
            throw new ForbiddenException("Không có quyền tra tên người nhận");
        }
        NameInquiryRequest request;
        try {
            request = objectMapper.treeToValue(payload, NameInquiryRequest.class);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("INVALID_PAYLOAD", "Payload tra tên không hợp lệ");
        }
        return nameInquiryService.inquire(request.bankCode(), request.accountNumber());
    }
}
