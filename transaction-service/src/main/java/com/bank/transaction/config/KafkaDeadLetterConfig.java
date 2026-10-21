package com.bank.transaction.config;

import com.bank.common.crypto.DecryptingStringDeserializer;
import com.bank.common.crypto.EncryptionKeyring;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Container factory cho consumer DLT: lỗi (vd. DB tạm thời không truy cập được) thì thử lại mỗi 5 giây không giới hạn,
 * không đẩy tiếp sang DLT của DLT — message DLT không được phép mất.
 * <p>
 * Consumer factory riêng với {@link DecryptingStringDeserializer} chế độ lenient: message vào DLT vì không giải mã
 * được (bytes gốc) vẫn được lưu, dưới dạng {@code undecryptable:<base64>} (không replay được).
 */
@Configuration
public class KafkaDeadLetterConfig {

    public static final String DEAD_LETTER_LISTENER_FACTORY = "deadLetterListenerFactory";

    @Bean(DEAD_LETTER_LISTENER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<Object, Object> deadLetterListenerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, KafkaProperties kafkaProperties,
            EncryptionKeyring keyring) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, deadLetterConsumerFactory(kafkaProperties, keyring));
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(5000L, FixedBackOff.UNLIMITED_ATTEMPTS)));
        return factory;
    }

    @SuppressWarnings("unchecked")
    private static ConsumerFactory<Object, Object> deadLetterConsumerFactory(KafkaProperties kafkaProperties,
                                                                             EncryptionKeyring keyring) {
        ConsumerFactory<?, ?> factory = new DefaultKafkaConsumerFactory<>(kafkaProperties.buildConsumerProperties(null),
                new StringDeserializer(), new DecryptingStringDeserializer(keyring.kafka(), true));
        return (ConsumerFactory<Object, Object>) factory;
    }
}
