package com.bank.common.redis;

import com.bank.common.crypto.AesGcmCipher;
import com.bank.common.crypto.EncryptionKeyring;
import com.bank.common.resilience.RemoteCallGuard;
import com.bank.common.security.AuthUser;
import com.bank.common.security.JwtProperties;
import com.bank.common.security.JwtTokenProvider;
import com.bank.common.tracing.TraceContextPropagator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Client → queue Redis → worker → reply chạy trên một Redis giả lập bằng Map: mọi thứ nằm trên Redis (token,
 * payload, dữ liệu trả về) phải là ciphertext, hai đầu vẫn trao đổi đúng dữ liệu.
 */
class RedisRequestReplyEncryptionTest {

    private static final String PHONE = "0912345678";

    private final Map<String, Deque<String>> lists = new HashMap<>();
    private final List<String> wire = new ArrayList<>();
    private RedisRequestReplyClient client;
    private RedisRequestWorker worker;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        EncryptionKeyring keyring = new EncryptionKeyring(Base64.getEncoder().encodeToString(key));
        ObjectMapper objectMapper = new ObjectMapper();
        JwtTokenProvider tokenProvider = new JwtTokenProvider(new JwtProperties(), keyring.jweKey());
        RedisRequestReplyProperties properties = new RedisRequestReplyProperties();

        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ListOperations<String, String> listOperations = mock(ListOperations.class);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.leftPush(anyString(), anyString())).thenAnswer(inv -> {
            wire.add(inv.getArgument(1));
            lists.computeIfAbsent(inv.getArgument(0), k -> new ArrayDeque<>()).addFirst(inv.getArgument(1));
            return 1L;
        });
        String queueKey = properties.queueKey("customer-service");
        when(listOperations.rightPop(anyString(), any(Duration.class))).thenAnswer(inv -> {
            String listKey = inv.getArgument(0);
            if (!listKey.equals(queueKey) && lists.getOrDefault(listKey, new ArrayDeque<>()).isEmpty()) {
                // Bên gọi đang chờ reply → cho worker xử lý request trong queue
                worker.process(lists.get(queueKey).pollLast());
            }
            return lists.getOrDefault(listKey, new ArrayDeque<>()).pollLast();
        });

        RedisRequestHandler echoPhone = new RedisRequestHandler() {
            @Override
            public Set<String> supportedActions() {
                return Set.of("test.phone");
            }

            @Override
            public Object handle(String action, JsonNode payload, AuthUser caller) {
                return Map.of("customerId", payload.get("customerId").asLong(), "phone", PHONE);
            }
        };
        client = new RedisRequestReplyClient(redisTemplate, objectMapper, tokenProvider, properties,
                "transaction-service", RemoteCallGuard.NOOP, TraceContextPropagator.NOOP, keyring.redis());
        worker = new RedisRequestWorker(redisTemplate, objectMapper, tokenProvider, properties, "customer-service",
                List.of(echoPhone), user -> false, TraceContextPropagator.NOOP, keyring.redis());
    }

    @Test
    void requestAndReplyShouldBeEncryptedOnRedis() {
        @SuppressWarnings("unchecked")
        Map<String, Object> reply = client.send("customer-service", "test.phone", Map.of("customerId", 42), Map.class);

        assertThat(reply).containsEntry("phone", PHONE);
        assertThat(wire).hasSize(2).allSatisfy(message -> assertThat(message)
                .startsWith(AesGcmCipher.PREFIX)
                .doesNotContain(PHONE)
                .doesNotContain("test.phone")
                .doesNotContain("customerId"));
    }
}
