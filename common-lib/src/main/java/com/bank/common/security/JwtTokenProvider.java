package com.bank.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phát hành và xác thực JWT dạng JWE (AES-256-GCM, direct key): payload (uid, roles, perms) được mã hóa nên client
 * và bên thứ ba không đọc được; tag GCM bảo đảm toàn vẹn thay cho chữ ký HMAC. auth-service phát hành access token;
 * các service khác chỉ verify, ngoài ra tự phát hành service token ngắn hạn cho lời gọi nội bộ.
 */
public class JwtTokenProvider {

    private static final String CLAIM_USER_ID = "uid";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_PERMISSIONS = "perms";
    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_SERVICE = "service";

    private final JwtProperties properties;
    private final SecretKey key;

    /**
     * @param encryptionKey khóa AES-256 dùng mã hóa trực tiếp (JWE {@code alg=dir}, {@code enc=A256GCM}) — dẫn xuất
     *                      từ khóa gốc bởi {@link com.bank.common.crypto.EncryptionKeyring#jweKey()}
     */
    public JwtTokenProvider(JwtProperties properties, SecretKey encryptionKey) {
        if (encryptionKey == null || encryptionKey.getEncoded().length != 32) {
            throw new IllegalStateException("Khóa mã hóa JWT phải là AES-256");
        }
        this.properties = properties;
        this.key = encryptionKey;
    }

    public String generateAccessToken(AuthUser user) {
        return build(user, TYPE_ACCESS, properties.getAccessTokenTtl());
    }

    public String generateServiceToken(String serviceName) {
        AuthUser service = new AuthUser(null, "service:" + serviceName, Set.of(RoleCodes.SERVICE), Set.of());
        return build(service, TYPE_SERVICE, properties.getServiceTokenTtl());
    }

    /**
     * @throws io.jsonwebtoken.JwtException nếu token không giải mã được (sai khóa / bị sửa / không phải JWE), hết hạn
     *                                      hoặc sai issuer
     */
    public AuthUser parseToken(String token) {
        Claims claims = Jwts.parser()
                .decryptWith(key)
                .requireIssuer(properties.getIssuer())
                .build()
                .parseEncryptedClaims(token)
                .getPayload();
        Number userId = claims.get(CLAIM_USER_ID, Number.class);
        return new AuthUser(
                userId == null ? null : userId.longValue(),
                claims.getSubject(),
                toStringSet(claims.get(CLAIM_ROLES)),
                toStringSet(claims.get(CLAIM_PERMISSIONS)),
                claims.getIssuedAt() == null ? null : claims.getIssuedAt().toInstant());
    }

    public long getAccessTokenTtlSeconds() {
        return properties.getAccessTokenTtl().toSeconds();
    }

    private String build(AuthUser user, String type, Duration ttl) {
        Instant now = Instant.now();
        JwtBuilder builder = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(properties.getIssuer())
                .subject(user.username())
                .claim(CLAIM_ROLES, user.roles())
                .claim(CLAIM_PERMISSIONS, user.permissions())
                .claim(CLAIM_TYPE, type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)));
        if (user.userId() != null) {
            builder.claim(CLAIM_USER_ID, user.userId());
        }
        return builder.encryptWith(key, Jwts.ENC.A256GCM).compact();
    }

    private static Set<String> toStringSet(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet());
        }
        return Set.of();
    }
}
