package com.example.backend.auth.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 메일 링크·refresh 토큰 원문 생성과 저장용 해시.
 * 원문: 32바이트 난수 → base64url(43자). 저장: SHA-256 hex 64자 (DB ck_*_hash 형식).
 */
public final class TokenCodec {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TokenCodec() {
    }

    public static String newRawToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
