package com.bank.common.redis;

import com.bank.common.security.AuthUser;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/**
 * Bean xử lý request đến từ kênh Redis. Mỗi service khai báo các handler của mình;
 * {@link RedisRequestWorker} tự đăng ký theo {@link #supportedActions()}.
 * Handler phải tự kiểm tra quyền của {@code caller} (không mặc định tin service gọi tới).
 */
public interface RedisRequestHandler {

    Set<String> supportedActions();

    Object handle(String action, JsonNode payload, AuthUser caller);
}
