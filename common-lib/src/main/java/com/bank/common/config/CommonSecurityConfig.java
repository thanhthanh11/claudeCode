package com.bank.common.config;

import com.bank.common.security.JwtAuthenticationFilter;
import com.bank.common.security.JwtTokenProvider;
import com.bank.common.security.RestAccessDeniedHandler;
import com.bank.common.security.RestAuthenticationEntryPoint;
import com.bank.common.security.TokenRevocationChecker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * Bean bảo mật dùng chung cho service servlet. Mỗi service tự khai báo {@code SecurityConfig}
 * (SecurityFilterChain) và gắn {@link JwtAuthenticationFilter} vào chain.
 */
@AutoConfiguration(after = CommonJwtConfig.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.security.web.SecurityFilterChain")
public class CommonSecurityConfig {

    @Bean
    @ConditionalOnMissingBean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenProvider tokenProvider,
                                                           ObjectProvider<TokenRevocationChecker> revocationChecker) {
        return new JwtAuthenticationFilter(tokenProvider, revocationChecker.getIfAvailable(() -> user -> false));
    }

    /** Không để Spring Boot đăng ký filter vào servlet container (chỉ chạy trong security chain). */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    public RestAuthenticationEntryPoint restAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return new RestAuthenticationEntryPoint(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public RestAccessDeniedHandler restAccessDeniedHandler(ObjectMapper objectMapper) {
        return new RestAccessDeniedHandler(objectMapper);
    }
}
