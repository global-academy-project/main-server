package com.example.backend.attachment.service;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** 테스트용 작은 사진 바이트 (실제 이미지 데이터는 없고 머리 부분만) */
final class TestImages {

    private TestImages() {
    }

    /** EXIF DateTimeOriginal 이 든 JPEG. littleEndian=false 면 MM(빅 엔디언) */
    static byte[] jpegWithExif(String dateTimeOriginal, boolean littleEndian) {
        ByteOrder order = littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        // TIFF: 머리(8) + IFD0(2 + 12 + 4) + ExifIFD(2 + 12 + 4) + 날짜(20)
        int ifd0 = 8;
        int exif = ifd0 + 2 + 12 + 4;
        int dateAt = exif + 2 + 12 + 4;
        ByteBuffer t = ByteBuffer.allocate(dateAt + 20).order(order);
        t.put(littleEndian ? (byte) 'I' : (byte) 'M').put(littleEndian ? (byte) 'I' : (byte) 'M');
        t.putShort((short) 42).putInt(ifd0);
        t.putShort((short) 1);                                         // IFD0 항목 1개
        t.putShort((short) 0x8769).putShort((short) 4).putInt(1).putInt(exif);
        t.putInt(0);
        t.putShort((short) 1);                                         // Exif IFD 항목 1개
        t.putShort((short) 0x9003).putShort((short) 2).putInt(20).putInt(dateAt);
        t.putInt(0);
        t.put((dateTimeOriginal + "\0").getBytes(StandardCharsets.US_ASCII));
        byte[] tiff = t.array();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF); out.write(0xD8);                              // SOI
        int len = 2 + 6 + tiff.length;
        out.write(0xFF); out.write(0xE1); out.write(len >> 8); out.write(len & 0xFF);
        out.writeBytes("Exif\0\0".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(tiff);
        out.write(0xFF); out.write(0xDA); out.write(0); out.write(2);  // SOS (여기서 멈춤)
        out.write(0xFF); out.write(0xD9);
        return out.toByteArray();
    }

    static byte[] jpegWithoutExif() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 4, 'J', 'F', (byte) 0xFF, (byte) 0xDA, 0, 2,
                (byte) 0xFF, (byte) 0xD9};
    }

    static byte[] png() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    }

    static byte[] heic() {
        return new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0, 0, 0};
    }
}
