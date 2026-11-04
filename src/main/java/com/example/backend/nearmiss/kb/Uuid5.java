package com.example.backend.nearmiss.kb;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * 이름 기반 UUID 버전 5 (SHA-1, RFC 4122). Python uuid.uuid5 와 같은 값.
 * Java 의 UUID.nameUUIDFromBytes 는 버전 3(MD5)이라 값이 다르다.
 */
public final class Uuid5 {

    /** RFC 4122 NAMESPACE_URL (Python uuid.NAMESPACE_URL) */
    public static final UUID NAMESPACE_URL = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");

    private Uuid5() {
    }

    public static UUID of(UUID namespace, String name) {
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        sha1.update(ByteBuffer.allocate(16).putLong(namespace.getMostSignificantBits())
                .putLong(namespace.getLeastSignificantBits()).array());
        sha1.update(name.getBytes(StandardCharsets.UTF_8));
        byte[] h = sha1.digest();
        h[6] = (byte) ((h[6] & 0x0F) | 0x50);   // 버전 5
        h[8] = (byte) ((h[8] & 0x3F) | 0x80);   // RFC 4122 변형
        ByteBuffer b = ByteBuffer.wrap(h, 0, 16);
        return new UUID(b.getLong(), b.getLong());
    }
}
