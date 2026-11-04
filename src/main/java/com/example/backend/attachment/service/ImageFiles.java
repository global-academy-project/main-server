package com.example.backend.attachment.service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * 사진 파일 판별과 촬영 시각 읽기 (외부 라이브러리 없이).
 *  - 형식은 확장자·Content-Type 이 아니라 파일 앞부분(매직 바이트)으로 정한다: JPEG / PNG / HEIC
 *  - 촬영 시각은 JPEG EXIF 의 DateTimeOriginal(0x9003), 없으면 DateTime(0x0132). 메신저를 거치면 지워지므로 없을 수 있다 (null)
 */
public final class ImageFiles {

    /** 판별된 사진 형식 */
    public enum Type {
        JPEG("image/jpeg", "jpg"), PNG("image/png", "png"), HEIC("image/heic", "heic");

        private final String mimeType;
        private final String extension;

        Type(String mimeType, String extension) {
            this.mimeType = mimeType;
            this.extension = extension;
        }

        public String mimeType() { return mimeType; }
        public String extension() { return extension; }
    }

    private static final Set<String> HEIC_BRANDS = Set.of("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1");
    private static final DateTimeFormatter EXIF_TIME = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");

    private ImageFiles() {
    }

    /** 사진이 아니면 null */
    public static Type detect(byte[] b) {
        if (b == null || b.length < 12) {
            return null;
        }
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return Type.JPEG;
        }
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return Type.PNG;
        }
        if (b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p'
                && HEIC_BRANDS.contains(new String(b, 8, 4, StandardCharsets.US_ASCII))) {
            return Type.HEIC;
        }
        return null;
    }

    /** JPEG EXIF 촬영 시각. JPEG 가 아니거나 없거나 읽을 수 없으면 null */
    public static LocalDateTime takenAt(byte[] b) {
        try {
            return detect(b) == Type.JPEG ? jpegTakenAt(b) : null;
        } catch (RuntimeException e) {
            return null;   // 깨진 EXIF 는 촬영 시각 없음으로 본다
        }
    }

    private static LocalDateTime jpegTakenAt(byte[] b) {
        int pos = 2;
        while (pos + 4 <= b.length) {
            if ((b[pos] & 0xFF) != 0xFF) {
                return null;
            }
            int marker = b[pos + 1] & 0xFF;
            if (marker == 0xD9 || marker == 0xDA) {
                return null;   // 이미지 데이터 시작 전까지만 본다
            }
            int len = ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
            if (len < 2 || pos + 2 + len > b.length) {
                return null;
            }
            if (marker == 0xE1 && len >= 8 && new String(b, pos + 4, 6, StandardCharsets.US_ASCII).equals("Exif\0\0")) {
                return tiffTakenAt(b, pos + 10, pos + 2 + len);
            }
            pos += 2 + len;
        }
        return null;
    }

    private static LocalDateTime tiffTakenAt(byte[] b, int start, int end) {
        ByteBuffer buf = ByteBuffer.wrap(b, 0, end);
        if (b[start] == 'I' && b[start + 1] == 'I') {
            buf.order(ByteOrder.LITTLE_ENDIAN);
        } else if (b[start] == 'M' && b[start + 1] == 'M') {
            buf.order(ByteOrder.BIG_ENDIAN);
        } else {
            return null;
        }
        int ifd0 = buf.getInt(start + 4);
        String dateTime = null;
        int exifIfd = -1;
        int count = buf.getShort(start + ifd0) & 0xFFFF;
        for (int i = 0; i < count; i++) {
            int entry = start + ifd0 + 2 + i * 12;
            int tag = buf.getShort(entry) & 0xFFFF;
            if (tag == 0x8769) {
                exifIfd = buf.getInt(entry + 8);
            } else if (tag == 0x0132) {
                dateTime = ascii(b, buf, start, entry);
            }
        }
        if (exifIfd > 0) {
            int n = buf.getShort(start + exifIfd) & 0xFFFF;
            for (int i = 0; i < n; i++) {
                int entry = start + exifIfd + 2 + i * 12;
                if ((buf.getShort(entry) & 0xFFFF) == 0x9003) {
                    String original = ascii(b, buf, start, entry);
                    LocalDateTime t = parse(original);
                    if (t != null) {
                        return t;
                    }
                }
            }
        }
        return parse(dateTime);
    }

    /** ASCII 값 ("yyyy:MM:dd HH:mm:ss\0", 20바이트라 항상 오프셋에 있음) */
    private static String ascii(byte[] b, ByteBuffer buf, int start, int entry) {
        int type = buf.getShort(entry + 2) & 0xFFFF;
        int count = buf.getInt(entry + 4);
        if (type != 2 || count < 19 || count > 64) {
            return null;
        }
        int offset = start + buf.getInt(entry + 8);
        return new String(b, offset, 19, StandardCharsets.US_ASCII);
    }

    private static LocalDateTime parse(String s) {
        if (s == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(s, EXIF_TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
