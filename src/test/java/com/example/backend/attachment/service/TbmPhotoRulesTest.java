package com.example.backend.attachment.service;

import com.example.backend.attachment.entity.AttachmentOwner;
import com.example.backend.attachment.entity.PhotoCheckResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TBM 사진 규칙 확인 (촬영 날짜·같은 파일)")
class TbmPhotoRulesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 3);

    @Test
    @DisplayName("촬영 날짜가 TBM 날짜와 같으면 PASS")
    void sameDay() {
        TbmPhotoRules.Decision d = TbmPhotoRules.check(10L, DAY, LocalDateTime.of(2026, 11, 3, 7, 50), null);
        assertThat(d.result()).isEqualTo(PhotoCheckResult.PASS);
        assertThat(d.detectedDate()).isEqualTo(DAY);
        assertThat(d.duplicateOf()).isNull();
    }

    @Test
    @DisplayName("촬영 날짜가 다르면 FAIL, 날짜 정보가 없으면 UNSURE")
    void otherDayOrNoDate() {
        TbmPhotoRules.Decision other = TbmPhotoRules.check(10L, DAY, LocalDateTime.of(2026, 11, 2, 23, 59), null);
        assertThat(other.result()).isEqualTo(PhotoCheckResult.FAIL);
        assertThat(other.messages()).anyMatch(m -> m.contains("2026-11-02") && m.contains("다릅니다"));

        TbmPhotoRules.Decision none = TbmPhotoRules.check(10L, DAY, null, null);
        assertThat(none.result()).isEqualTo(PhotoCheckResult.UNSURE);
        assertThat(none.detectedDate()).isNull();
    }

    @Test
    @DisplayName("같은 파일: 같은 TBM 이든 다른 기록이든 FAIL, 날짜가 없어도 FAIL 이 먼저")
    void duplicate() {
        TbmPhotoRules.Decision sameTbm = TbmPhotoRules.check(10L, DAY, LocalDateTime.of(2026, 11, 3, 8, 0),
                new TbmPhotoRules.Duplicate(5L, AttachmentOwner.TBM, 10L));
        assertThat(sameTbm.result()).isEqualTo(PhotoCheckResult.FAIL);
        assertThat(sameTbm.duplicateOf()).isEqualTo(5L);
        assertThat(sameTbm.messages()).anyMatch(m -> m.startsWith("이 TBM에 똑같은 사진이 이미 있습니다 (사진 #5)"));

        TbmPhotoRules.Decision otherTbm = TbmPhotoRules.check(10L, DAY, null,
                new TbmPhotoRules.Duplicate(3L, AttachmentOwner.TBM, 7L));
        assertThat(otherTbm.result()).isEqualTo(PhotoCheckResult.FAIL);
        assertThat(otherTbm.messages()).anyMatch(m -> m.startsWith("다른 기록(TBM #7)에 올린 사진과 똑같은 파일입니다"));
    }

    @Test
    @DisplayName("사진 속 내용: 안전모 미착용이 있으면 FAIL, 날짜 정보 없는 사진은 당일 확인하면 PASS (TBM 날짜가 바뀌면 다시 UNSURE)")
    void observations() {
        TbmPhotoRules.Decision helmet = TbmPhotoRules.check(10L, DAY, LocalDateTime.of(2026, 11, 3, 8, 0), null,
                new TbmPhotoRules.Observations(5, 1, null));
        assertThat(helmet.result()).isEqualTo(PhotoCheckResult.FAIL);
        assertThat(helmet.messages()).contains("사진 속 인원 5명.", "안전모 미착용 1명.");

        TbmPhotoRules.Decision allWorn = TbmPhotoRules.check(10L, DAY, null, null, new TbmPhotoRules.Observations(5, 0, DAY));
        assertThat(allWorn.result()).isEqualTo(PhotoCheckResult.PASS);
        assertThat(allWorn.messages()).anyMatch(m -> m.contains("작성자가 TBM 당일")).contains("안전모 모두 착용.");

        TbmPhotoRules.Decision moved = TbmPhotoRules.check(10L, DAY.plusDays(1), null, null, new TbmPhotoRules.Observations(5, 0, DAY));
        assertThat(moved.result()).isEqualTo(PhotoCheckResult.UNSURE);
    }

    @Test
    @DisplayName("인원 비교: 명부보다 1명까지 적으면 통과, 더 적거나 많으면 UNSURE, 모르거나 명부가 비면 비교 안 함")
    void people() {
        assertThat(TbmPhotoRules.people(6, 6, 1).result()).isEqualTo(PhotoCheckResult.PASS);
        assertThat(TbmPhotoRules.people(5, 6, 1).result()).isEqualTo(PhotoCheckResult.PASS);
        assertThat(TbmPhotoRules.people(4, 6, 1).result()).isEqualTo(PhotoCheckResult.UNSURE);
        assertThat(TbmPhotoRules.people(7, 6, 1).message()).contains("명부(6명)보다 많습니다");
        assertThat(TbmPhotoRules.people(null, 6, 1).result()).isNull();
        assertThat(TbmPhotoRules.people(3, 0, 1).result()).isNull();
    }
}
