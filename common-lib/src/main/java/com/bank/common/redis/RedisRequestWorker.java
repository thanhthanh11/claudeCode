package com.bank.common.redis;

import com.bank.common.crypto.AesGcmCipher;
import com.bank.common.exception.BusinessException;
import com.bank.common.security.AuthUser;
import com.bank.common.security.JwtTokenProvider;
import com.bank.common.security.SecurityUtils;
import com.bank.common.security.TokenRevocationChecker;
import com.bank.common.tracing.TraceContextPropagator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lắng nghe queue Redis của service hiện tại, verify token, gọi {@link RedisRequestHandler}
 * tương ứng và đẩy response về key reply. Chỉ chạy khi service có ít nhất một handler.
 * Request và reply trên Redis được mã hóa AES-256-GCM (xem {@link RedisRequestReplyClient}).
 */
public class RedisRequestWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RedisRequestWorker.class);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(2);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final JwtTokenProvider tokenProvider;
    private final RedisRequestReplyProperties properties;
    private final String queueKey;
    private final TokenRevocationChecker revocationChecker;
    private final TraceContextPropagator tracePropagator;
    private final AesGcmCipher cipher;
    private final Map<String, RedisRequestHandler> handlers = new HashMap<>();
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;

    public RedisRequestWorker(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                              JwtTokenProvider tokenProvider, RedisRequestReplyProperties properties,
                              String serviceName, List<RedisRequestHandler> handlerBeans,
                              TokenRevocationChecker revocationChecker, TraceContextPropagator tracePropagator,
                              AesGcmCipher cipher) {
        this.cipher = cipher;
        this.revocationChecker = revocationChecker;
        this.tracePropagator = tracePropagator;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.tokenProvider = tokenProvider;
        this.properties = properties;
        this.queueKey = properties.queueKey(serviceName);
        for (RedisRequestHandler handler : handlerBeans) {
            handler.supportedActions().forEach(action -> {
                RedisRequestHandler previous = handlers.putIfAbsent(action, handler);
                if (previous != null) {
                    throw new IllegalStateException("Action Redis bị đăng ký trùng: " + action);
                }
            });
        }
    }

    @Override
    public void start() {
        if (handlers.isEmpty()) {
            return;
        }
        running = true;
        for (int i = 0; i < properties.getWorkers(); i++) {
            threads.add(Thread.ofVirtual().name("redis-rr-worker-" + i).start(this::pollLoop));
        }
        log.info("Redis request worker lắng nghe {} với actions {}", queueKey, handlers.keySet());
    }

    @Override
    public void stop() {
        running = false;
        threads.forEach(Thread::interrupt);
        threads.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void pollLoop() {
        while (running) {
            try {
                String raw = redisTemplate.opsForList().rightPop(queueKey, POLL_TIMEOUT);
                if (raw != null) {
                    process(raw);
                }
            } catch (Exception e) {
                if (!running) {
                    return;
                }
                log.warn("Lỗi khi đọc queue {}: {}", queueKey, e.getMessage());
                sleepQuietly();
            }
        }
    }

    /** Xử lý một request (package-private cho test). */
    void process(String raw) {
        RedisRequestMessage request;
        try {
            request = objectMapper.readValue(cipher.decrypt(raw), RedisRequestMessage.class);
        } catch (JsonProcessingException | IllegalStateException e) {
            // IllegalStateException: không giải mã được (sai khóa / bị sửa) — không log nội dung
            log.error("Bỏ qua request Redis sai định dạng trên {}", queueKey);
            return;
        }
        if (request.isExpired(System.currentTimeMillis())) {
            log.debug("Bỏ qua request đã quá hạn: {} {}", request.action(), request.correlationId());
            return;
        }
        RedisReplyMessage reply = tracePropagator.runInSpan(request.traceContext(),
                "redis-rr " + request.action(), () -> execute(request));
        try {
            String encrypted = cipher.encrypt(objectMapper.writeValueAsString(reply));
            redisTemplate.opsForList().leftPush(request.replyTo(), encrypted);
            redisTemplate.expire(request.replyTo(), properties.getReplyTtl());
        } catch (JsonProcessingException e) {
            log.error("Không thể serialize reply cho {}", request.correlationId(), e);
        }
    }

    private RedisReplyMessage execute(RedisRequestMessage request) {
        String cid = request.correlationId();
        try {
            AuthUser caller = tokenProvider.parseToken(request.token());
            if (revocationChecker.isRevoked(caller)) {
                return RedisReplyMessage.failure(cid, 401, "UNAUTHORIZED", "Token đã bị thu hồi");
            }
            RedisRequestHandler handler = handlers.get(request.action());
            if (handler == null) {
                return RedisReplyMessage.failure(cid, 404, "UNKNOWN_ACTION", "Action không được hỗ trợ: " + request.action());
            }
            // Đặt SecurityContext để lời gọi tiếp theo (nếu có) chuyển tiếp đúng danh tính bên gọi
            SecurityContextHolder.getContext().setAuthentication(SecurityUtils.toAuthentication(caller, request.token()));
            Object result = handler.handle(request.action(), request.payload(), caller);
            return RedisReplyMessage.success(cid, objectMapper.valueToTree(result));
        } catch (JwtException | IllegalArgumentException e) {
            return RedisReplyMessage.failure(cid, 401, "UNAUTHORIZED", "Token không hợp lệ");
        } catch (BusinessException e) {
            return RedisReplyMessage.failure(cid, e.getStatus().value(), e.getCode(), e.getMessage());
        } catch (AccessDeniedException e) {
            return RedisReplyMessage.failure(cid, 403, "ACCESS_DENIED", "Không có quyền thực hiện thao tác này");
        } catch (Exception e) {
            log.error("Lỗi xử lý action {} (correlationId={})", request.action(), cid, e);
            return RedisReplyMessage.failure(cid, 500, "INTERNAL_ERROR", "Lỗi hệ thống");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
