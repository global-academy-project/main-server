-- 감사 대응 보고서 구성기 응답 원본 (11/20).
-- AI 서버 /api/v1/agent/audit-report 가 구성기 응답(formType=AUDIT_REPORT, schemaVersion=v1)을 주면, 메인 서버가
-- 보고서 번호·현장이 맞는지 확인한 뒤 응답 data 를 그대로 저장한다 (묶음·상태값·서술문·종합 내역, 아차사고 항목 포함).
-- 옛 응답(항목별 요약 형식)·AI 실패·이 칼럼 전에 만든 보고서는 NULL. 숫자는 다시 계산하지 않는다 (집계 원본은 data_snapshot).
-- 사람이 쓰는 칸(묶음별 응답자 의견·요청 조치)과 이 원본을 보이는 화면은 다음 단계에서 붙인다.
ALTER TABLE audit_reports ADD COLUMN compose_json LONGTEXT NULL AFTER data_snapshot;
