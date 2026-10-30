package com.example.backend.tbm.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** TBM 교육 요점 읽기: 에이전트 초안(JSON 배열)과 직접 쓴 글 */
@DisplayName("TBM 교육 요점")
class TbmPointsTest {

    private final TbmService service = new TbmService(null, null, null, null, null, null,
            JsonMapper.builder().build(), null);

    @Test
    @DisplayName("JSON 배열은 목록으로(빈 값·문자 아닌 값 제외), 그 밖의 글은 한 줄, 없으면 빈 목록")
    void points() {
        assertThat(service.points("[\"가스측정 확인\", \" \", 3, \"감시자 지정\"]")).containsExactly("가스측정 확인", "감시자 지정");
        assertThat(service.points("오늘은 화기작업 주의")).containsExactly("오늘은 화기작업 주의");
        assertThat(service.points("{\"a\": 1}")).containsExactly("{\"a\": 1}");
        assertThat(service.points(null)).isEmpty();
        assertThat(service.points(" ")).isEmpty();
    }
}
