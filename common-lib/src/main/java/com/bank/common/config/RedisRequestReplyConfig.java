package com.bank.common.config;

import com.bank.common.crypto.EncryptionKeyring;
import com.bank.common.redis.RedisRequestHandler;
import com.bank.common.redis.RedisRequestReplyClient;
import com.bank.common.redis.RedisRequestReplyProperties;
import com.bank.common.redis.RedisRequestWorker;
import com.bank.common.resilience.RemoteCallGuard;
import com.bank.common.security.JwtTokenProvider;
import com.bank.common.security.RedisTokenRevocationChecker;
import com.bank.common.security.TokenRevocationChecker;
import com.bank.common.tracing.MicrometerTraceContextPropagator;
import com.bank.common.tracing.TraceContextPropagator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@AutoConfiguration(after = {RedisAutoConfiguration.class, CommonCryptoConfig.class, CommonJwtConfig.class,
        CommonResilienceConfig.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = {
        "org.springframework.data.redis.core.StringRedisTemplate",
        "org.springframework.security.core.context.SecurityContextHolder"})
@ConditionalOnProperty(prefix = "bank.redis.request-reply", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(RedisRequestReplyProperties.class)
public class RedisRequestReplyConfig {

    @Bean
    public RedisRequestReplyClient redisRequestReplyClient(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                                                           JwtTokenProvider tokenProvider,
                                                           RedisRequestReplyProperties properties,
                                                           @Value("${spring.application.name}") String serviceName,
                                                           ObjectProvider<RemoteCallGuard> callGuard,
                                                           ObjectProvider<TraceContextPropagator> tracePropagator,
                                                           EncryptionKeyring keyring) {
        return new RedisRequestReplyClient(redisTemplate, objectMapper, tokenProvider, properties, serviceName,
                callGuard.getIfAvailable(() -> RemoteCallGuard.NOOP),
                tracePropagator.getIfAvailable(() -> TraceContextPropagator.NOOP), keyring.redis());
    }

    @Bean
    public RedisRequestWorker redisRequestWorker(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                                                 JwtTokenProvider tokenProvider,
                                                 RedisRequestReplyProperties properties,
                                                 @Value("${spring.application.name}") String serviceName,
                                                 ObjectProvider<RedisRequestHandler> handlers,
                                                 TokenRevocationChecker revocationChecker,
                                                 ObjectProvider<TraceContextPropagator> tracePropagator,
                                                 EncryptionKeyring keyring) {
        return new RedisRequestWorker(redisTemplate, objectMapper, tokenProvider, properties, serviceName,
                handlers.orderedStream().toList(), revocationChecker,
                tracePropagator.getIfAvailable(() -> TraceContextPropagator.NOOP), keyring.redis());
    }

    /** Dùng cho JwtAuthenticationFilter (REST) và RedisRequestWorker. */
    @Bean
    @ConditionalOnMissingBean
    public TokenRevocationChecker tokenRevocationChecker(StringRedisTemplate redisTemplate) {
        return new RedisTokenRevocationChecker(redisTemplate);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.tracing.Tracer")
    static class TracePropagationConfig {

        @Bean
        @ConditionalOnMissingBean
        public TraceContextPropagator traceContextPropagator(ObjectProvider<Tracer> tracer,
                                                             ObjectProvider<Propagator> propagator) {
            Tracer currentTracer = tracer.getIfAvailable();
            Propagator currentPropagator = propagator.getIfAvailable();
            if (currentTracer == null || currentPropagator == null) {
                return TraceContextPropagator.NOOP;
            }
            return new MicrometerTraceContextPropagator(currentTracer, currentPropagator);
        }
    }
}
