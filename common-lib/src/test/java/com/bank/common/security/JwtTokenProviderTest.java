package com.bank.common.security;

import com.bank.common.crypto.EncryptionKeyring;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private JwtProperties properties;
    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        properties = new JwtProperties();
        provider = new JwtTokenProvider(properties, new EncryptionKeyring(randomKey()).jweKey());
    }

    @Test
    void shouldGenerateAndParseAccessToken() {
        AuthUser user = new AuthUser(10L, "nguyenvana", Set.of(RoleCodes.CUSTOMER),
                Set.of(PermissionCodes.ACCOUNT_READ, PermissionCodes.TRANSFER_CREATE));

        AuthUser parsed = provider.parseToken(provider.generateAccessToken(user));

        assertThat(parsed.userId()).isEqualTo(10L);
        assertThat(parsed.username()).isEqualTo("nguyenvana");
        assertThat(parsed.roles()).containsExactly(RoleCodes.CUSTOMER);
        assertThat(parsed.permissions()).containsExactlyInAnyOrder(
                PermissionCodes.ACCOUNT_READ, PermissionCodes.TRANSFER_CREATE);
        assertThat(parsed.isService()).isFalse();
    }

    @Test
    void accessTokenShouldBeEncryptedJwe() {
        String token = provider.generateAccessToken(
                new AuthUser(10L, "nguyenvana", Set.of(RoleCodes.CUSTOMER), Set.of(PermissionCodes.ACCOUNT_READ)));

        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(5);
        String header = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        assertThat(header).contains("\"enc\":\"A256GCM\"").contains("\"alg\":\"dir\"");
        // Claims không đọc được nếu không có khóa
        String ciphertext = new String(Base64.getUrlDecoder().decode(parts[3]), StandardCharsets.ISO_8859_1);
        assertThat(ciphertext).doesNotContain("nguyenvana").doesNotContain("ACCOUNT_READ");
    }

    @Test
    void shouldGenerateServiceToken() {
        AuthUser parsed = provider.parseToken(provider.generateServiceToken("transaction-service"));

        assertThat(parsed.isService()).isTrue();
        assertThat(parsed.userId()).isNull();
        assertThat(parsed.username()).isEqualTo("service:transaction-service");
    }

    @Test
    void shouldRejectTamperedToken() {
        String token = provider.generateAccessToken(new AuthUser(1L, "a", Set.of(RoleCodes.CUSTOMER), Set.of()));
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");

        assertThatThrownBy(() -> provider.parseToken(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void shouldRejectTokenEncryptedWithAnotherKey() {
        JwtTokenProvider other = new JwtTokenProvider(properties, new EncryptionKeyring(randomKey()).jweKey());

        String token = other.generateAccessToken(new AuthUser(1L, "a", Set.of(RoleCodes.ADMIN), Set.of()));

        assertThatThrownBy(() -> provider.parseToken(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void shouldRejectLegacySignedJws() {
        String jws = Jwts.builder().issuer(properties.getIssuer()).subject("admin")
                .signWith(Keys.hmacShaKeyFor(new byte[32])).compact();

        assertThatThrownBy(() -> provider.parseToken(jws)).isInstanceOf(JwtException.class);
    }

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
