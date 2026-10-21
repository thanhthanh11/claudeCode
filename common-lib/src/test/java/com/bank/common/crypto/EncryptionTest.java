package com.bank.common.crypto;

import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptionTest {

    static final String TEST_KEY = randomKey();

    private final EncryptionKeyring keyring = new EncryptionKeyring(TEST_KEY);

    @Test
    void encryptedValueShouldRoundTripAndUseRandomIv() {
        AesGcmCipher cipher = keyring.db();

        String first = cipher.encrypt("0912345678");
        String second = cipher.encrypt("0912345678");

        assertThat(first).startsWith(AesGcmCipher.PREFIX).doesNotContain("0912345678").isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("0912345678");
        assertThat(cipher.decrypt(cipher.encrypt("Nguyễn Văn A — 12 Lý Thường Kiệt"))).isEqualTo("Nguyễn Văn A — 12 Lý Thường Kiệt");
    }

    @Test
    void tamperedCiphertextShouldBeRejected() {
        String encrypted = keyring.db().encrypt("079123456789");
        byte[] raw = Base64.getDecoder().decode(encrypted.substring(AesGcmCipher.PREFIX.length()));
        raw[raw.length - 1] ^= 1;
        String tampered = AesGcmCipher.PREFIX + Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> keyring.db().decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("079123456789");
    }

    @Test
    void derivedKeysShouldBeIndependentPerPurpose() {
        String forDb = keyring.db().encrypt("secret");

        assertThatThrownBy(() -> keyring.kafka().decrypt(forDb)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new EncryptionKeyring(randomKey()).db().decrypt(forDb))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void legacyPlaintextShouldBeReadAsIs() {
        assertThat(keyring.db().decrypt("0912345678")).isEqualTo("0912345678");
        assertThat(keyring.db().decrypt(null)).isNull();
        assertThat(keyring.db().encrypt(null)).isNull();
    }

    @Test
    void missingOrShortMasterKeyShouldFailFast() {
        assertThatThrownBy(() -> new EncryptionKeyring(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new EncryptionKeyring("not base64!")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new EncryptionKeyring(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void blindIndexShouldBeDeterministicAfterNormalization() {
        BlindIndexer indexer = keyring.blindIndexer();

        assertThat(indexer.hashEmail(" Alice@Bank.VN ")).isEqualTo(indexer.hashEmail("alice@bank.vn")).hasSize(64);
        assertThat(indexer.hashPhone("+84912345678")).isEqualTo(indexer.hashPhone("0912 345 678"));
        assertThat(indexer.hashIdNumber("CCCD", "c123")).isEqualTo(indexer.hashIdNumber("CCCD", " C123"))
                .isNotEqualTo(indexer.hashIdNumber("PASSPORT", "C123"));
        assertThat(indexer.hashEmail(null)).isNull();
    }

    @Test
    void kafkaValueShouldBeEncryptedOnTheWireAndDecryptedByConsumer() {
        EncryptingStringSerializer serializer = new EncryptingStringSerializer(keyring.kafka());
        DecryptingStringDeserializer deserializer = new DecryptingStringDeserializer(keyring.kafka(), false);
        String envelope = "{\"eventType\":\"ACCOUNT_DEBIT_REQUESTED\",\"payload\":{\"accountNumber\":\"100000000001\"}}";

        byte[] wire = serializer.serialize("bank.account.commands", envelope);

        assertThat(new String(wire, StandardCharsets.UTF_8)).startsWith(AesGcmCipher.PREFIX)
                .doesNotContain("100000000001");
        assertThat(deserializer.deserialize("bank.account.commands", wire)).isEqualTo(envelope);
        // Message cũ chưa mã hóa vẫn đọc được
        assertThat(deserializer.deserialize("t", envelope.getBytes(StandardCharsets.UTF_8))).isEqualTo(envelope);
        assertThat(serializer.serialize("t", null)).isNull();
    }

    @Test
    void undecryptableKafkaValueShouldFailOrBeKeptRawForDlt() {
        byte[] foreign = new EncryptingStringSerializer(new EncryptionKeyring(randomKey()).kafka()).serialize("t", "{}");

        assertThatThrownBy(() -> new DecryptingStringDeserializer(keyring.kafka(), false).deserialize("t", foreign))
                .isInstanceOf(SerializationException.class);
        assertThat(new DecryptingStringDeserializer(keyring.kafka(), true).deserialize("t", foreign))
                .startsWith(DecryptingStringDeserializer.UNDECRYPTABLE_PREFIX);
    }

    static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
