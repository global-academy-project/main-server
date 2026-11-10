package com.example.backend.stt.service;

import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.stt.client.SttClient;
import com.example.backend.stt.client.SttResult;
import com.example.backend.stt.dto.SttResponse;
import com.example.backend.stt.dto.SttStatusResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 음성 입력 (4번, 11/10 결정): 녹음을 받아 글로 바꿔 돌려준다. 녹음 파일과 글은 저장하지 않는다.
 * 녹음 길이는 화면이 app.stt.max-seconds 로 끊고, 서버는 크기(app.stt.max-bytes)와 형식(파일 앞부분)만 본다.
 * 받는 형식: WebM·Ogg(크롬·안드로이드), MP4/M4A(아이폰 사파리), WAV.
 */
@Service
public class SttService {

    static final int MAX_TEXT = 1000;

    private final SttClient client;
    private final int maxSeconds;
    private final long maxBytes;

    public SttService(SttClient client, @Value("${app.stt.max-seconds}") int maxSeconds,
                      @Value("${app.stt.max-bytes}") long maxBytes) {
        this.client = client;
        this.maxSeconds = maxSeconds;
        this.maxBytes = maxBytes;
    }

    public SttStatusResponse status() {
        return new SttStatusResponse(client.enabled(), maxSeconds, maxBytes);
    }

    public SttResponse transcribe(MultipartFile file, String language) {
        if (!client.enabled()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "음성 입력이 꺼져 있습니다. 직접 적어 주세요.");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "녹음 파일이 필요합니다.");
        }
        if (file.getSize() > maxBytes) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "녹음이 너무 깁니다. " + maxSeconds + "초 안으로 말해 주세요.");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "녹음 파일을 읽지 못했습니다.");
        }
        String mime = audioType(bytes);
        if (mime == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "녹음 형식을 알 수 없습니다 (WebM·Ogg·MP4·WAV).");
        }
        String lang = language == null || !language.matches("[a-z]{2}") ? "ko" : language;
        SttResult r = client.transcribe(bytes, mime, lang)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_STATE, "말을 글로 바꾸지 못했습니다. 다시 말하거나 직접 적어 주세요."));
        String text = r.text() == null ? "" : r.text().strip().replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "알아들은 말이 없습니다. 조금 더 크게 다시 말해 주세요.");
        }
        return new SttResponse(text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text, r.model());
    }

    /** 파일 앞부분으로 녹음 형식 판별 (모르면 null) */
    static String audioType(byte[] b) {
        if (b.length >= 4 && (b[0] & 0xff) == 0x1A && (b[1] & 0xff) == 0x45 && (b[2] & 0xff) == 0xDF && (b[3] & 0xff) == 0xA3) {
            return "audio/webm";
        }
        if (b.length >= 4 && b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S') {
            return "audio/ogg";
        }
        if (b.length >= 8 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
            return "audio/mp4";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'A' && b[10] == 'V' && b[11] == 'E') {
            return "audio/wav";
        }
        return null;
    }
}
