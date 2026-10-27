-- V9: 감사 증거 보호. 이력·판정 결과는 수정·삭제 불가 (추가만)
-- 담당: C
--
-- 주의: MySQL 8.4는 바이너리 로그가 기본으로 켜져 있어, 일반 계정으로 트리거를 만들려면
--       서버 옵션 --log-bin-trust-function-creators=1 이 필요하다 (README 6장 compose 설정).
--       이 옵션 없이 기동하면 이 마이그레이션에서 실패한다.

CREATE TRIGGER trg_hist_no_update BEFORE UPDATE ON record_history
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'record_history는 수정할 수 없습니다 (추가만 가능)';

CREATE TRIGGER trg_hist_no_delete BEFORE DELETE ON record_history
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'record_history는 삭제할 수 없습니다';

CREATE TRIGGER trg_eval_no_update BEFORE UPDATE ON rule_evaluations
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'rule_evaluations는 수정할 수 없습니다 (새 판정을 추가하세요)';

CREATE TRIGGER trg_eval_no_delete BEFORE DELETE ON rule_evaluations
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'rule_evaluations는 삭제할 수 없습니다';

CREATE TRIGGER trg_viol_no_update BEFORE UPDATE ON rule_violations
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'rule_violations는 수정할 수 없습니다';

CREATE TRIGGER trg_viol_no_delete BEFORE DELETE ON rule_violations
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'rule_violations는 삭제할 수 없습니다';

-- 서명·생성 문서·열람 기록도 증거: 수정·삭제 금지
CREATE TRIGGER trg_sig_no_update BEFORE UPDATE ON signatures
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'signatures는 수정할 수 없습니다';

CREATE TRIGGER trg_sig_no_delete BEFORE DELETE ON signatures
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'signatures는 삭제할 수 없습니다';

CREATE TRIGGER trg_gd_no_update BEFORE UPDATE ON generated_documents
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'generated_documents는 수정할 수 없습니다 (다시 출력하면 seq를 올려 새로 추가)';

CREATE TRIGGER trg_gd_no_delete BEFORE DELETE ON generated_documents
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'generated_documents는 삭제할 수 없습니다';

CREATE TRIGGER trg_ral_no_update BEFORE UPDATE ON record_access_logs
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'record_access_logs는 수정할 수 없습니다';

CREATE TRIGGER trg_ral_no_delete BEFORE DELETE ON record_access_logs
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'record_access_logs는 삭제할 수 없습니다';

-- 승인 결정은 되돌릴 수 없음: 결정된(APPROVED/REJECTED/CANCELED) 행은 상태를 바꿀 수 없다
-- 본문에 ;가 들어가는 트리거는 DELIMITER로 감싼다 (Flyway·mysql 클라이언트 모두 지원)
DELIMITER $$
CREATE TRIGGER trg_appr_final BEFORE UPDATE ON approvals
FOR EACH ROW
BEGIN
  IF OLD.status <> 'PENDING'
     AND (NEW.status <> OLD.status
          OR NOT (NEW.decided_at <=> OLD.decided_at)
          OR NOT (NEW.approver_id <=> OLD.approver_id)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '이미 결정된 승인은 바꿀 수 없습니다 (새 승인 요청을 만드세요)';
  END IF;
END$$
DELIMITER ;

-- ─────────────────────────────────────────────────────────────
-- 제출·승인 후 내용 수정 금지
--  * 편집 가능한 상태에서만 내용 컬럼을 바꿀 수 있다. 상태 변경(승인·반려·마감)은 언제나 가능.
--  * 제출 후 고치려면: 승인 요청 취소 → DRAFT로 되돌리고 수정 / 반려됐으면 revision을 올린 새 판 작성
--  * 화면에서 저장 버튼을 숨기는 것만으로는 부족하다 (API 직접 호출, 버그) → DB가 마지막으로 막는다
-- 편집 가능 상태
--   works: DRAFT, PENDING, REJECTED, BLOCKED   (승인·진행·완료·취소 후 잠김)
--   risk_assessments / inspections: DRAFT
--   permits: DRAFT, CHECKING, BLOCKED          (차단 후 재측정·보완은 같은 판에서)
--   tbm_records: DRAFT, DONE                   (승인 후 잠김)
--   daily_safety_logs: DRAFT, PENDING
-- ─────────────────────────────────────────────────────────────
DELIMITER $$

CREATE TRIGGER trg_works_lock BEFORE UPDATE ON works
FOR EACH ROW
BEGIN
  IF OLD.status IN ('APPROVED', 'IN_PROGRESS', 'DONE', 'CANCELED') AND NOT (
       NEW.zone_id <=> OLD.zone_id AND NEW.work_date <=> OLD.work_date AND NEW.title <=> OLD.title
   AND NEW.description <=> OLD.description AND NEW.work_type <=> OLD.work_type AND NEW.is_high_risk <=> OLD.is_high_risk
   AND NEW.start_at <=> OLD.start_at AND NEW.end_at <=> OLD.end_at AND NEW.worker_count <=> OLD.worker_count
   AND NEW.team_id <=> OLD.team_id AND NEW.site_id <=> OLD.site_id) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '승인된 작업은 내용을 고칠 수 없습니다 (변경이 필요하면 작업을 새로 등록해 다시 승인받으세요)';
  END IF;
END$$

CREATE TRIGGER trg_risk_lock BEFORE UPDATE ON risk_assessments
FOR EACH ROW
BEGIN
  IF OLD.status <> 'DRAFT' AND NOT (
       NEW.work_id <=> OLD.work_id AND NEW.revision <=> OLD.revision AND NEW.assess_date <=> OLD.assess_date
   AND NEW.assessment_type <=> OLD.assessment_type AND NEW.zone_id <=> OLD.zone_id AND NEW.title <=> OLD.title
   AND NEW.base_assessment_id <=> OLD.base_assessment_id
   AND NEW.method <=> OLD.method AND NEW.form_schema_version <=> OLD.form_schema_version
   AND NEW.form_data <=> OLD.form_data AND NEW.site_id <=> OLD.site_id) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '제출된 위험성평가는 내용을 고칠 수 없습니다 (요청 취소 후 수정하거나 새 판을 만드세요)';
  END IF;
END$$

CREATE TRIGGER trg_rai_lock_ins BEFORE INSERT ON risk_assessment_items
FOR EACH ROW
BEGIN
  IF (SELECT status FROM risk_assessments WHERE id = NEW.assessment_id) <> 'DRAFT' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '제출된 위험성평가에는 위험요인을 추가할 수 없습니다';
  END IF;
END$$

CREATE TRIGGER trg_rai_lock_upd BEFORE UPDATE ON risk_assessment_items
FOR EACH ROW
BEGIN
  IF (SELECT status FROM risk_assessments WHERE id = OLD.assessment_id) <> 'DRAFT' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '제출된 위험성평가의 위험요인은 고칠 수 없습니다';
  END IF;
END$$

CREATE TRIGGER trg_rai_lock_del BEFORE DELETE ON risk_assessment_items
FOR EACH ROW
BEGIN
  IF (SELECT status FROM risk_assessments WHERE id = OLD.assessment_id) <> 'DRAFT' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '제출된 위험성평가의 위험요인은 지울 수 없습니다';
  END IF;
END$$

CREATE TRIGGER trg_permits_lock BEFORE UPDATE ON permits
FOR EACH ROW
BEGIN
  IF OLD.status NOT IN ('DRAFT', 'CHECKING', 'BLOCKED') AND NOT (
       NEW.work_id <=> OLD.work_id AND NEW.permit_type <=> OLD.permit_type AND NEW.revision <=> OLD.revision
   AND NEW.permit_no <=> OLD.permit_no AND NEW.valid_from <=> OLD.valid_from AND NEW.valid_to <=> OLD.valid_to
   AND NEW.watcher_name <=> OLD.watcher_name AND NEW.supervisor_name <=> OLD.supervisor_name
   AND NEW.form_schema_version <=> OLD.form_schema_version AND NEW.form_data <=> OLD.form_data
   AND NEW.last_evaluation_id <=> OLD.last_evaluation_id AND NEW.site_id <=> OLD.site_id) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '승인 요청된 작업허가는 내용을 고칠 수 없습니다';
  END IF;
END$$

-- 측정값은 증거: 고치거나 지우지 않고 재측정 행을 추가한다. 단계마다 넣을 수 있는 허가 상태가 다르다
CREATE TRIGGER trg_pm_lock_ins BEFORE INSERT ON permit_measurements
FOR EACH ROW
BEGIN
  DECLARE v_status VARCHAR(20);
  SELECT status INTO v_status FROM permits WHERE id = NEW.permit_id;
  IF NEW.phase = 'PRE_WORK' AND v_status NOT IN ('DRAFT', 'CHECKING', 'BLOCKED') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '작업 전 측정값은 허가 신청 단계에서만 넣을 수 있습니다 (작업 중이면 DURING으로)';
  ELSEIF NEW.phase = 'DURING' AND v_status NOT IN ('APPROVED', 'SUSPENDED') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '작업 중 측정값은 승인된(또는 중단된) 허가에만 넣을 수 있습니다';
  ELSEIF NEW.phase = 'POST_WORK' AND v_status <> 'APPROVED' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '작업 후 측정값은 완료 전의 승인된 허가에만 넣을 수 있습니다';
  END IF;
END$$

CREATE TRIGGER trg_pm_no_update BEFORE UPDATE ON permit_measurements
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '측정값은 고칠 수 없습니다 (재측정 값을 새로 입력하세요)'$$

CREATE TRIGGER trg_pm_no_delete BEFORE DELETE ON permit_measurements
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '측정값은 지울 수 없습니다'$$

CREATE TRIGGER trg_pc_lock_ins BEFORE INSERT ON permit_checks
FOR EACH ROW
BEGIN
  IF (SELECT status FROM permits WHERE id = NEW.permit_id) NOT IN ('DRAFT', 'CHECKING', 'BLOCKED') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '승인 요청된 작업허가의 체크 항목은 바꿀 수 없습니다';
  END IF;
END$$

CREATE TRIGGER trg_pc_lock_upd BEFORE UPDATE ON permit_checks
FOR EACH ROW
BEGIN
  IF (SELECT status FROM permits WHERE id = OLD.permit_id) NOT IN ('DRAFT', 'CHECKING', 'BLOCKED') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '승인 요청된 작업허가의 체크 항목은 바꿀 수 없습니다';
  END IF;
END$$

CREATE TRIGGER trg_pc_lock_del BEFORE DELETE ON permit_checks
FOR EACH ROW
BEGIN
  IF (SELECT status FROM permits WHERE id = OLD.permit_id) NOT IN ('DRAFT', 'CHECKING', 'BLOCKED') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '승인 요청된 작업허가의 체크 항목은 지울 수 없습니다';
  END IF;
END$$

CREATE TRIGGER trg_insp_lock BEFORE UPDATE ON inspections
FOR EACH ROW
BEGIN
  IF OLD.status <> 'DRAFT' AND NOT (
       NEW.target_type <=> OLD.target_type AND NEW.equipment_id <=> OLD.equipment_id AND NEW.zone_id <=> OLD.zone_id
   AND NEW.work_id <=> OLD.work_id AND NEW.inspect_date <=> OLD.inspect_date AND NEW.template_code <=> OLD.template_code
   AND NEW.result <=> OLD.result AND NEW.form_data <=> OLD.form_data AND NEW.site_id <=> OLD.site_id) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '제출된 점검표는 내용을 고칠 수 없습니다';
  END IF;
END$$

CREATE TRIGGER trg_ii_lock_ins BEFORE INSERT ON inspection_items
FOR EACH ROW
BEGIN
  IF (SELECT status FROM inspections WHERE id = NEW.inspection_id) <> 'DRAFT' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '제출된 점검표에는 항목을 추가할 수 없습니다';
  END IF;
END$$

CREATE TRIGGER trg_ii_lock_upd BEFORE UPDATE ON inspection_items
FOR EACH ROW
BEGIN
  IF (SELECT status FROM inspections WHERE id = OLD.inspection_id) <> 'DRAFT' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '제출된 점검표의 항목은 고칠 수 없습니다';
  END IF;
END$$

CREATE TRIGGER trg_ii_lock_del BEFORE DELETE ON inspection_items
FOR EACH ROW
BEGIN
  IF (SELECT status FROM inspections WHERE id = OLD.inspection_id) <> 'DRAFT' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '제출된 점검표의 항목은 지울 수 없습니다';
  END IF;
END$$

CREATE TRIGGER trg_tbm_lock BEFORE UPDATE ON tbm_records
FOR EACH ROW
BEGIN
  IF OLD.status = 'APPROVED' AND NOT (
       NEW.team_id <=> OLD.team_id AND NEW.zone_id <=> OLD.zone_id AND NEW.tbm_date <=> OLD.tbm_date AND NEW.held_at <=> OLD.held_at
   AND NEW.leader_name <=> OLD.leader_name AND NEW.attendee_count <=> OLD.attendee_count
   AND NEW.education_points <=> OLD.education_points AND NEW.health_check <=> OLD.health_check
   AND NEW.site_id <=> OLD.site_id AND NEW.status <=> OLD.status) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '승인된 TBM 기록은 고칠 수 없습니다';
  END IF;
END$$

CREATE TRIGGER trg_ta_lock_ins BEFORE INSERT ON tbm_attendees
FOR EACH ROW
BEGIN
  IF (SELECT status FROM tbm_records WHERE id = NEW.tbm_id) = 'APPROVED' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '승인된 TBM에는 참석자를 추가할 수 없습니다';
  END IF;
END$$

CREATE TRIGGER trg_ta_lock_del BEFORE DELETE ON tbm_attendees
FOR EACH ROW
BEGIN
  IF (SELECT status FROM tbm_records WHERE id = OLD.tbm_id) = 'APPROVED' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '승인된 TBM의 참석자는 지울 수 없습니다';
  END IF;
END$$

CREATE TRIGGER trg_tw_lock_ins BEFORE INSERT ON tbm_works
FOR EACH ROW
BEGIN
  IF (SELECT status FROM tbm_records WHERE id = NEW.tbm_id) = 'APPROVED' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '승인된 TBM에는 작업을 추가할 수 없습니다';
  END IF;
END$$

CREATE TRIGGER trg_tw_lock_del BEFORE DELETE ON tbm_works
FOR EACH ROW
BEGIN
  IF (SELECT status FROM tbm_records WHERE id = OLD.tbm_id) = 'APPROVED' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '승인된 TBM의 작업은 뺄 수 없습니다';
  END IF;
END$$

CREATE TRIGGER trg_wm_lock_ins BEFORE INSERT ON work_materials
FOR EACH ROW
BEGIN
  IF (SELECT status FROM works WHERE id = NEW.work_id) IN ('APPROVED', 'IN_PROGRESS', 'DONE', 'CANCELED') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '승인된 작업의 취급 물질은 바꿀 수 없습니다';
  END IF;
END$$

CREATE TRIGGER trg_wm_lock_upd BEFORE UPDATE ON work_materials
FOR EACH ROW
BEGIN
  IF (SELECT status FROM works WHERE id = OLD.work_id) IN ('APPROVED', 'IN_PROGRESS', 'DONE', 'CANCELED') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '승인된 작업의 취급 물질은 바꿀 수 없습니다';
  END IF;
END$$

CREATE TRIGGER trg_wm_lock_del BEFORE DELETE ON work_materials
FOR EACH ROW
BEGIN
  IF (SELECT status FROM works WHERE id = OLD.work_id) IN ('APPROVED', 'IN_PROGRESS', 'DONE', 'CANCELED') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '승인된 작업의 취급 물질은 뺄 수 없습니다';
  END IF;
END$$

-- 허가 이벤트: 허가 상태에 맞는 이벤트만, 연장은 지금 종료 시각보다 뒤로만
CREATE TRIGGER trg_pe_ins BEFORE INSERT ON permit_events
FOR EACH ROW
BEGIN
  DECLARE v_status VARCHAR(20);
  DECLARE v_end DATETIME(6);
  SELECT status, valid_to INTO v_status, v_end FROM permits WHERE id = NEW.permit_id;
  IF NEW.event_type = 'RESUME' AND v_status <> 'SUSPENDED' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '중단된 허가만 재개할 수 있습니다';
  ELSEIF NEW.event_type <> 'RESUME' AND v_status <> 'APPROVED' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '승인된 허가에만 연장·중단·인수인계·완료 확인을 할 수 있습니다';
  END IF;
  IF NEW.event_type = 'EXTEND' THEN
    SELECT GREATEST(v_end, COALESCE(MAX(new_valid_to), v_end)) INTO v_end
      FROM permit_events WHERE permit_id = NEW.permit_id AND event_type = 'EXTEND' AND status = 'APPROVED';
    IF NEW.new_valid_to <= v_end THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '연장 시각은 현재 종료 시각보다 뒤여야 합니다';
    END IF;
  END IF;
END$$

CREATE TRIGGER trg_pe_final BEFORE UPDATE ON permit_events
FOR EACH ROW
BEGIN
  IF OLD.status <> 'REQUESTED' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '결정된 허가 이벤트는 바꿀 수 없습니다';
  END IF;
  IF NOT (NEW.permit_id <=> OLD.permit_id AND NEW.event_type <=> OLD.event_type AND NEW.reason <=> OLD.reason
          AND NEW.new_valid_to <=> OLD.new_valid_to AND NEW.handover_to <=> OLD.handover_to) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '요청한 허가 이벤트의 내용은 바꿀 수 없습니다 (취소 후 다시 요청)';
  END IF;
END$$

CREATE TRIGGER trg_pe_no_delete BEFORE DELETE ON permit_events
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '허가 이벤트는 지울 수 없습니다'$$

-- 결재 단계: 결정되면 되돌릴 수 없음
CREATE TRIGGER trg_aps_final BEFORE UPDATE ON approval_steps
FOR EACH ROW
BEGIN
  IF OLD.status IN ('APPROVED', 'REJECTED', 'SKIPPED') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '결정된 결재 단계는 바꿀 수 없습니다';
  END IF;
END$$

CREATE TRIGGER trg_dsl_lock BEFORE UPDATE ON daily_safety_logs
FOR EACH ROW
BEGIN
  IF OLD.status = 'APPROVED' AND NOT (
       NEW.log_date <=> OLD.log_date AND NEW.summary <=> OLD.summary AND NEW.stats <=> OLD.stats
   AND NEW.site_id <=> OLD.site_id AND NEW.status <=> OLD.status) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '승인된 안전일지는 고칠 수 없습니다';
  END IF;
END$$

DELIMITER ;
