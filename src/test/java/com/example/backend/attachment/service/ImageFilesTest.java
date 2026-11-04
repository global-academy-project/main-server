package com.example.backend.attachment.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 사진 형식 판별(매직 바이트)과 EXIF 촬영 시각 */
@DisplayName("사진 파일 판별·촬영 시각")
class ImageFilesTest {

    @Test
    @DisplayName("JPEG·PNG·HEIC 는 파일 앞부분으로 판별, 그 밖은 null")
    void detect() {
        assertThat(ImageFiles.detect(TestImages.jpegWithoutExif())).isEqualTo(ImageFiles.Type.JPEG);
        assertThat(ImageFiles.detect(TestImages.png())).isEqualTo(ImageFiles.Type.PNG);
        assertThat(ImageFiles.detect(TestImages.heic())).isEqualTo(ImageFiles.Type.HEIC);
        assertThat(ImageFiles.detect("<html><body>not a photo</body></html>".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(ImageFiles.detect(new byte[3])).isNull();
        assertThat(ImageFiles.detect(null)).isNull();
    }

    @Test
    @DisplayName("EXIF DateTimeOriginal 을 읽는다 (II·MM 모두), 없거나 깨졌으면 null")
    void takenAt() {
        assertThat(ImageFiles.takenAt(TestImages.jpegWithExif("2026:11:03 08:10:00", true)))
                .isEqualTo(LocalDateTime.of(2026, 11, 3, 8, 10, 0));
        assertThat(ImageFiles.takenAt(TestImages.jpegWithExif("2026:11:04 17:45:30", false)))
                .isEqualTo(LocalDateTime.of(2026, 11, 4, 17, 45, 30));
        assertThat(ImageFiles.takenAt(TestImages.jpegWithoutExif())).isNull();
        assertThat(ImageFiles.takenAt(TestImages.png())).isNull();
        assertThat(ImageFiles.takenAt(TestImages.jpegWithExif("not a date at all!!", true))).isNull();
        byte[] broken = TestImages.jpegWithExif("2026:11:03 08:10:00", true);
        broken[20] = (byte) 0x7F;   // IFD 오프셋 깨뜨림
        assertThat(ImageFiles.takenAt(broken)).isNull();
    }

    @Test
    @DisplayName("원래 파일 이름은 경로를 떼고, 없으면 photo.{확장자}")
    void fileName() {
        assertThat(AttachmentService.fileName("C:\\Users\\me\\Pictures\\tbm 1.jpg", ImageFiles.Type.JPEG)).isEqualTo("tbm 1.jpg");
        assertThat(AttachmentService.fileName("../../etc/passwd.png", ImageFiles.Type.PNG)).isEqualTo("passwd.png");
        assertThat(AttachmentService.fileName(null, ImageFiles.Type.HEIC)).isEqualTo("photo.heic");
        assertThat(AttachmentService.sha256(new byte[0]))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }
}
