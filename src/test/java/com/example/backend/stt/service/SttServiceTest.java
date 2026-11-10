package com.example.backend.stt.service;

import com.example.backend.common.exception.ErrorCode;
import com.example.backend.stt.client.MockSttClient;
import com.example.backend.stt.client.NoSttClient;
import com.example.backend.stt.client.SttClient;
import com.example.backend.stt.client.SttResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static com.example.backend.support.TestFixtures.expectCode;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("음성 입력 (녹음 → 글, 저장 안 함)")
class SttServiceTest {

    private static final byte[] WEBM = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0, 0, 0};
    private static final byte[] MP4 = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '};

    private static MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("file", "voice", "application/octet-stream", bytes);
    }

    @Test
    @DisplayName("켜져 있으면 받아 적은 글을 돌려주고, 꺼져 있으면 막는다")
    void transcribe() {
        SttService on = new SttService(new MockSttClient(), 30, 1000);
        assertThat(on.status().enabled()).isTrue();
        assertThat(on.status().maxSeconds()).isEqualTo(30);
        assertThat(on.transcribe(file(WEBM), null).text()).startsWith("산소 20.9");

        SttService off = new SttService(new NoSttClient(), 30, 1000);
        assertThat(off.status().enabled()).isFalse();
        expectCode(() -> off.transcribe(file(WEBM), "ko"), ErrorCode.INVALID_STATE);
    }

    @Test
    @DisplayName("형식은 파일 앞부분으로 판별, 크기 제한, 빈 파일 막음")
    void formatAndSize() {
        assertThat(SttService.audioType(WEBM)).isEqualTo("audio/webm");
        assertThat(SttService.audioType(MP4)).isEqualTo("audio/mp4");
        assertThat(SttService.audioType("OggS....".getBytes(StandardCharsets.US_ASCII))).isEqualTo("audio/ogg");
        assertThat(SttService.audioType("RIFF....WAVEfmt ".getBytes(StandardCharsets.US_ASCII))).isEqualTo("audio/wav");
        assertThat(SttService.audioType("<html>".getBytes(StandardCharsets.US_ASCII))).isNull();

        SttService on = new SttService(new MockSttClient(), 30, 10);
        expectCode(() -> on.transcribe(file("<html>hi".getBytes(StandardCharsets.US_ASCII)), "ko"), ErrorCode.INVALID_REQUEST);
        expectCode(() -> on.transcribe(file(new byte[20]), "ko"), ErrorCode.INVALID_REQUEST);   // 10바이트 초과
        expectCode(() -> on.transcribe(file(new byte[0]), "ko"), ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("모델이 실패하거나 빈 글이면 다시 말해 달라고 알림, 띄어쓰기 정리")
    void emptyOrFail() {
        SttClient blank = fixed(Optional.of(new SttResult("   ", "ko", "whisper-small")));
        expectCode(() -> new SttService(blank, 30, 1000).transcribe(file(WEBM), "ko"), ErrorCode.INVALID_STATE);
        SttClient fail = fixed(Optional.empty());
        expectCode(() -> new SttService(fail, 30, 1000).transcribe(file(WEBM), "ko"), ErrorCode.INVALID_STATE);
        SttClient spaced = fixed(Optional.of(new SttResult("  산소   20.9\n황화수소 3 ", "ko", "whisper-small")));
        assertThat(new SttService(spaced, 30, 1000).transcribe(file(WEBM), "ko").text()).isEqualTo("산소 20.9 황화수소 3");
    }

    private static SttClient fixed(Optional<SttResult> result) {
        return new SttClient() {
            @Override
            public Optional<SttResult> transcribe(byte[] audio, String mimeType, String language) {
                return result;
            }

            @Override
            public boolean enabled() {
                return true;
            }
        };
    }
}
