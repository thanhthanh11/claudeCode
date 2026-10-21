package com.bank.common.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Giữ nguyên độ chính xác của số tiền khi đi qua JSON (Kafka, Redis, REST):
 * số thập phân được đọc thành BigDecimal, không bỏ số 0 cuối, ghi dạng plain (không 1E+2).
 */
@AutoConfiguration(before = JacksonAutoConfiguration.class)
public class CommonJacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer bankMoneyPrecisionCustomizer() {
        return builder -> builder
                .featuresToEnable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                        JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
                .postConfigurer(mapper -> mapper.configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false));
    }
}
