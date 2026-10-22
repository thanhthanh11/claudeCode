package com.bank.common.config;

import com.bank.common.audit.AuditPublisher;
import com.bank.common.crypto.DecryptingStringDeserializer;
import com.bank.common.crypto.EncryptingStringSerializer;
import com.bank.common.crypto.EncryptionKeyring;
import com.bank.common.event.KafkaTopics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Khai báo topic và error handler chung: retry 3 lần (cách 1 giây) rồi đẩy sang topic {@code <topic>.DLT}.
 * Cung cấp {@link AuditPublisher} cho mọi service có Kafka.
 * <p>
 * Value message được mã hóa AES-256-GCM (khóa con {@code kafka}): producer dùng {@link EncryptingStringSerializer},
 * consumer dùng {@link DecryptingStringDeserializer} bọc trong {@link ErrorHandlingDeserializer} — message không giải
 * mã được đi thẳng sang DLT (bytes gốc) thay vì làm consumer lỗi lặp lại.
 */
@AutoConfiguration(after = {KafkaAutoConfiguration.class, CommonCryptoConfig.class})
@ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
public class CommonKafkaConfig {

    private static final int PARTITIONS = 3;

    @Bean
    public KafkaAdmin.NewTopics bankTopics() {
        return new KafkaAdmin.NewTopics(
                topic(KafkaTopics.ACCOUNT_COMMANDS),
                topic(KafkaTopics.ACCOUNT_COMMANDS + KafkaTopics.DLT_SUFFIX),
                topic(KafkaTopics.TRANSACTION_EVENTS),
                topic(KafkaTopics.TRANSACTION_EVENTS + KafkaTopics.DLT_SUFFIX),
                topic(KafkaTopics.NOTIFICATION_EVENTS),
                topic(KafkaTopics.NOTIFICATION_EVENTS + KafkaTopics.DLT_SUFFIX),
                topic(KafkaTopics.INTERBANK_COMMANDS),
                topic(KafkaTopics.INTERBANK_COMMANDS + KafkaTopics.DLT_SUFFIX),
                topic(KafkaTopics.INTERBANK_EVENTS),
                topic(KafkaTopics.INTERBANK_EVENTS + KafkaTopics.DLT_SUFFIX),
                topic(KafkaTopics.AUDIT_EVENTS),
                topic(KafkaTopics.AUDIT_EVENTS + KafkaTopics.DLT_SUFFIX));
    }

    @Bean
    @SuppressWarnings("unchecked")
    public DefaultKafkaProducerFactoryCustomizer encryptingProducerFactoryCustomizer(EncryptionKeyring keyring) {
        return factory -> ((DefaultKafkaProducerFactory<Object, String>) factory)
                .setValueSerializer(new EncryptingStringSerializer(keyring.kafka()));
    }

    @Bean
    @SuppressWarnings("unchecked")
    public DefaultKafkaConsumerFactoryCustomizer decryptingConsumerFactoryCustomizer(EncryptionKeyring keyring) {
        return factory -> ((DefaultKafkaConsumerFactory<Object, String>) factory).setValueDeserializer(
                new ErrorHandlingDeserializer<>(new DecryptingStringDeserializer(keyring.kafka(), false)));
    }

    /**
     * Đích DLT khai báo tường minh: {@code <topic>.DLT}, cùng partition với message gốc (topic DLT có cùng số partition).
     * Không dùng resolver mặc định của Spring Kafka 3.x (đẩy sang {@code <topic>-dlt} — topic không được khai báo,
     * gửi lỗi thì message bị retry mãi và chặn cả partition).
     * <p>
     * Message lỗi giải mã / deserialize chỉ còn bytes gốc → gửi bằng template {@code byte[]} riêng; message khác gửi
     * bằng {@link KafkaTemplate} chung (được mã hóa lại).
     */
    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<?, ?> kafkaTemplate, KafkaProperties kafkaProperties) {
        KafkaTemplate<String, byte[]> rawTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
                kafkaProperties.buildProducerProperties(null), new StringSerializer(), new ByteArraySerializer()));
        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, rawTemplate);
        templates.put(Object.class, kafkaTemplate);
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(templates,
                (record, exception) -> new TopicPartition(record.topic() + KafkaTopics.DLT_SUFFIX, record.partition()));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditPublisher auditPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                                         @Value("${spring.application.name}") String serviceName) {
        return new AuditPublisher(kafkaTemplate, objectMapper, serviceName);
    }

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(PARTITIONS).replicas(1).build();
    }
}
