package com.example.backend.preview.dto;

import com.example.backend.approval.dto.ApprovalDetail;
import com.example.backend.work.dto.WorkResponse;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * 문서 미리보기 (저장하지 않음, 파일도 만들지 않음). 화면은 formType·formSchemaVersion 으로 양식(스키마 렌더러·인쇄 템플릿)을 고르고
 * content 를 채운 뒤, mark 가 있으면 markText 를 워터마크로 보여 준다.
 *  - targetType: PERMIT · RISK · TBM
 *  - formType: HOT_WORK_PERMIT · CONFINED_SPACE_PERMIT · HEIGHT_WORK_PERMIT · RISK_ASSESSMENT · TBM
 *  - status: 문서 상태 그대로 (예: PENDING)
 *  - mark / markText: 표시 문구. official=true(승인된 정식 문서)면 null
 *  - work: 문서가 붙은 작업 (TBM 은 content.works 에 있어 null)
 *  - content: 문서 상세 응답 그대로 (허가서 상세·위험성평가 상세·TBM 상세와 같은 모양). 위험성평가 formData 는 JSON 으로 풀어 둠
 *  - approval: 이 문서의 가장 최근 결재 (없으면 null). 승인자·승인 시각·자기 승인은 여기서
 */
public record PreviewResponse(String targetType, Long targetId, String formType, String formSchemaVersion,
                              String status, PreviewMark mark, String markText, boolean official, String title,
                              WorkResponse work, JsonNode content, ApprovalDetail approval, LocalDateTime generatedAt) {
}
