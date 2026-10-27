-- R: 판단 규칙 데이터 (반복 마이그레이션: 이 파일 내용이 바뀌면 다음 기동 때 다시 실행)
-- 담당: C
-- 원칙: 규칙마다 근거 조항을 반드시 적는다. 조항을 원문으로 확인하지 못한 규칙은 넣지 않는다.
-- 규칙 내용을 바꾸면 version을 올린 새 행을 추가하고 이전 행은 enabled=FALSE로 둔다 (판정 이력 보존).

INSERT INTO rules
 (rule_code, version, work_type, category, target, operator,
  min_value, min_inclusive, max_value, max_inclusive, unit,
  severity, message, action, legal_basis, source_url)
VALUES
 -- 밀폐공간 적정공기 기준 (산업안전보건기준에 관한 규칙 제618조 정의)
 ('CONFINED_O2_RANGE', 1, 'CONFINED', 'MEASUREMENT', 'measurement.O2', 'RANGE',
  18.000, TRUE, 23.500, FALSE, 'PCT',
  'BLOCK', '산소농도가 적정공기 기준(18% 이상 23.5% 미만)을 벗어났습니다.', '환기 후 재측정',
  '산업안전보건기준에 관한 규칙 제618조', 'https://www.law.go.kr/법령/산업안전보건기준에관한규칙'),
 ('CONFINED_CO2_MAX', 1, 'CONFINED', 'MEASUREMENT', 'measurement.CO2', 'LT',
  NULL, TRUE, 1.500, FALSE, 'PCT',
  'BLOCK', '탄산가스 농도가 기준(1.5% 미만)을 넘었습니다.', '환기 후 재측정',
  '산업안전보건기준에 관한 규칙 제618조', 'https://www.law.go.kr/법령/산업안전보건기준에관한규칙'),
 ('CONFINED_CO_MAX', 1, 'CONFINED', 'MEASUREMENT', 'measurement.CO', 'LT',
  NULL, TRUE, 30.000, FALSE, 'PPM',
  'BLOCK', '일산화탄소 농도가 기준(30ppm 미만)을 넘었습니다.', '환기 후 재측정',
  '산업안전보건기준에 관한 규칙 제618조', 'https://www.law.go.kr/법령/산업안전보건기준에관한규칙'),
 ('CONFINED_H2S_MAX', 1, 'CONFINED', 'MEASUREMENT', 'measurement.H2S', 'LT',
  NULL, TRUE, 10.000, FALSE, 'PPM',
  'BLOCK', '황화수소 농도가 기준(10ppm 미만)을 넘었습니다.', '환기 후 재측정',
  '산업안전보건기준에 관한 규칙 제618조', 'https://www.law.go.kr/법령/산업안전보건기준에관한규칙')
AS new
ON DUPLICATE KEY UPDATE
  work_type = new.work_type, category = new.category, target = new.target,
  operator = new.operator, min_value = new.min_value, min_inclusive = new.min_inclusive,
  max_value = new.max_value, max_inclusive = new.max_inclusive, unit = new.unit,
  severity = new.severity, message = new.message, action = new.action,
  legal_basis = new.legal_basis, source_url = new.source_url;

-- TODO(C, 1주차): 원문 확인 후 추가
--  - HOT: 가연성 가스 농도(LEL) 기준, 화재감시자 배치, 소화기·비산방지 조치 (기준규칙 화재위험작업 조항, KOSHA GUIDE 화기작업허가 지침)
--  - CONFINED: 측정값 누락(REQUIRED), 감시인 배치(REQUIRED)
--  - HEIGHT: 2m 이상 작업 시 작업발판·안전대 (기준규칙 추락 방지 조항)
--  - 공통: 허가 유효시간, 동일 구역 동시작업 충돌(CONFLICT)
