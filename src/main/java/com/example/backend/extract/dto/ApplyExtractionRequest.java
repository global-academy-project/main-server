package com.example.backend.extract.dto;

import com.example.backend.permit.entity.MeasurementItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 사용자가 확인한 추출값을 허가서에 넣기 (POST /api/v1/permits/{permitId}/extraction).
 *  - text: 추출에 쓴 원문 (스냅샷에 그대로 남는다)
 *  - measurements: 확인(필요하면 고친) 측정값. 작업 전 측정값으로 추가된다
 *  - form: 확인한 양식 값. 그 허가 종류 규칙의 form 항목만 받는다 (watcher_name 은 감시자 칸으로)
 *  - lockVersion: 허가서 최신 버전
 *  - texts: (선택, 11/12) 문장을 나눠 채웠을 때 보낸 문장들. 있으면 문장마다 미리 보기 결과를 합쳐 기록하고, text 는 이들을 줄바꿈으로 이은 값.
 *    없으면 text 한 문장 (이전과 같음)
 */
public record ApplyExtractionRequest(@NotNull Integer lockVersion,
                                     @NotBlank @Size(max = MAX_TEXTS * (MAX_TEXT + 1)) String text,
                                     @Size(max = 7) List<@Valid @NotNull Measurement> measurements,
                                     @Size(max = 20) Map<String, Object> form,
                                     @Size(max = MAX_TEXTS) List<@NotBlank @Size(max = MAX_TEXT) String> texts) {

    /** 문장 하나의 최대 길이 (미리 보기 ExtractRequest 와 같게) */
    public static final int MAX_TEXT = 2000;
    /** 나눠 보낼 수 있는 문장 수 */
    public static final int MAX_TEXTS = 10;

    /** 문장 하나만 보낼 때 (이전 요청 모양) */
    public ApplyExtractionRequest(Integer lockVersion, String text, List<Measurement> measurements, Map<String, Object> form) {
        this(lockVersion, text, measurements, form, null);
    }

    /** texts 없이 한 문장만 보낼 때는 이전처럼 MAX_TEXT 자까지 */
    @AssertTrue(message = "문장은 2000자까지입니다.")
    public boolean isSingleTextWithinLimit() {
        return (texts != null && !texts.isEmpty()) || text == null || text.length() <= MAX_TEXT;
    }

    /** 추출·기록에 쓸 문장들: texts 가 있으면 그것, 없으면 text 하나 */
    public List<String> sentences() {
        return texts == null || texts.isEmpty() ? List.of(text) : texts;
    }

    public record Measurement(@NotNull MeasurementItem item, @NotNull @DecimalMin("0") BigDecimal value) {
    }
}
