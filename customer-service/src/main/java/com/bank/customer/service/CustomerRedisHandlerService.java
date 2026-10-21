package com.bank.customer.service;

import com.bank.common.dto.CustomerInfoResponse;
import com.bank.common.dto.CustomerLookupRequest;
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

import java.util.Objects;
import java.util.Set;

/**
 * Xử lý request tra cứu khách hàng từ service khác qua Redis.
 * Cho phép: service nội bộ, nhân viên có CUSTOMER_MANAGE, hoặc chính chủ hồ sơ.
 */
@Service
@RequiredArgsConstructor
public class CustomerRedisHandlerService implements RedisRequestHandler {

    private final CustomerInfoService customerInfoService;
    private final ObjectMapper objectMapper;

    @Override
    public Set<String> supportedActions() {
        return Set.of(RedisActions.CUSTOMER_GET_BY_ID, RedisActions.CUSTOMER_GET_BY_USER_ID);
    }

    @Override
    public Object handle(String action, JsonNode payload, AuthUser caller) {
        CustomerLookupRequest request = parse(payload);
        CustomerInfoResponse info = switch (action) {
            case RedisActions.CUSTOMER_GET_BY_ID -> {
                requireNonNull(request.customerId(), "customerId");
                yield customerInfoService.getById(request.customerId());
            }
            case RedisActions.CUSTOMER_GET_BY_USER_ID -> {
                requireNonNull(request.userId(), "userId");
                yield customerInfoService.getByUserId(request.userId());
            }
            default -> throw new BadRequestException("UNKNOWN_ACTION", "Action không được hỗ trợ: " + action);
        };
        authorize(info, caller);
        return info;
    }

    private void authorize(CustomerInfoResponse info, AuthUser caller) {
        boolean isOwner = caller.userId() != null && Objects.equals(info.userId(), caller.userId());
        if (!caller.isService() && !caller.hasPermission(PermissionCodes.CUSTOMER_MANAGE) && !isOwner) {
            throw new ForbiddenException("Không có quyền tra cứu khách hàng này");
        }
    }

    private CustomerLookupRequest parse(JsonNode payload) {
        try {
            return objectMapper.treeToValue(payload, CustomerLookupRequest.class);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("INVALID_PAYLOAD", "Payload tra cứu khách hàng không hợp lệ");
        }
    }

    private static void requireNonNull(Object value, String field) {
        if (value == null) {
            throw new BadRequestException("INVALID_PAYLOAD", "Thiếu " + field);
        }
    }
}
