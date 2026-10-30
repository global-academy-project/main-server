package com.example.backend.preview.dto;

/**
 * 미리보기 표시 문구 (계획서 7-5장 문서 미리보기 표). 승인된 정식 문서는 표시 없음(null).
 * 미리보기와 정식 출력은 같은 양식 판·같은 템플릿·같은 데이터로 만들고, 다른 점은 이 표시와 저장 여부뿐이다.
 */
public enum PreviewMark {
    DRAFT("작성 중 · 정식 문서 아님"),
    PENDING("승인 전 미리보기"),
    REJECTED("반려됨 · 정식 문서 아님"),
    SUPERSEDED("이전 판 · 정식 문서 아님");

    private final String text;

    PreviewMark(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }
}
