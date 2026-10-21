package com.bank.common.redis;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "bank.redis.request-reply")
public class RedisRequestReplyProperties {

    private boolean enabled = true;
    private String keyPrefix = "bank:rr";
    /** Số virtual thread lắng nghe queue của service này. */
    private int workers = 4;
    private Duration defaultTimeout = Duration.ofSeconds(5);
    /** TTL của key reply để dọn rác nếu bên gọi đã timeout. */
    private Duration replyTtl = Duration.ofSeconds(30);

    public String queueKey(String serviceName) {
        return keyPrefix + ":queue:" + serviceName;
    }

    public String replyKey(String correlationId) {
        return keyPrefix + ":reply:" + correlationId;
    }
}
