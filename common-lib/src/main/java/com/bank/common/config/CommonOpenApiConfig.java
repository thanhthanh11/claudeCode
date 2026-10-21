package com.bank.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Tài liệu OpenAPI cho mỗi service (springdoc): khai báo xác thực Bearer JWT, server = gateway để
 * "Try it out" trên Swagger UI của gateway đi đúng đường (rate limit, kiểm tra token).
 */
@AutoConfiguration
@ConditionalOnClass(name = "io.swagger.v3.oas.models.OpenAPI")
public class CommonOpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    @ConditionalOnMissingBean
    public OpenAPI bankOpenApi(@Value("${spring.application.name}") String serviceName,
                               @Value("${bank.openapi.server-url:http://localhost:8080}") String serverUrl) {
        return new OpenAPI()
                .info(new Info().title(serviceName + " API").version("1.0.0")
                        .description("Hệ thống ngân hàng microservice — " + serviceName))
                .addServersItem(new Server().url(serverUrl).description("API Gateway"))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
