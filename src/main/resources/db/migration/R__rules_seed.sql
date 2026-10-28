-- R: 판단 규칙 데이터 (반복 마이그레이션: 이 파일 내용이 바뀌면 다음 기동 때 다시 실행)
-- 담당: C
-- 원칙: 규칙마다 근거 조항을 반드시 적는다. 조항을 원문으로 확인하지 못한 규칙은 넣지 않는다.
-- 규칙 내용을 바꾸면 version을 올린 새 행을 추가하고 이전 행은 enabled=FALSE로 둔다 (판정 이력 보존).
--
-- 입력 이름 (check_rules input)
--   measurement.{O2, CO2, LEL, H2S, CO, NH3, CL2}  단위 고정 (O2·CO2 %, LEL %LEL, 나머지 ppm)
--   form.{허가서 양식 필드명}                       예/아니오 항목은 true/false 로 보낸다

INSERT INTO rules
 (rule_code, version, work_type, category, target, operator,
  min_value, min_inclusive, max_value, max_inclusive, allowed, unit,
  severity, message, action, legal_basis, source_url)
VALUES
 -- ---------------------------------------------------------------- 밀폐공간
 -- 적정공기 기준 (산업안전보건기준에 관한 규칙 제618조 정의)
 ('CONFINED_O2_RANGE', 1, 'CONFINED', 'MEASUREMENT', 'measurement.O2', 'RANGE',
  18.000, TRUE, 23.500, FALSE, NULL, 'PCT',
  'BLOCK', '산소농도가 적정공기 기준(18% 이상 23.5% 미만)을 벗어났습니다.', '환기 후 재측정',
  '산업안전보건기준에 관한 규칙 제618조', 'https://www.law.go.kr/법령/산업안전보건기준에관한규칙'),
 ('CONFINED_CO2_MAX', 1, 'CONFINED', 'MEASUREMENT', 'measurement.CO2', 'LT',
  NULL, TRUE, 1.500, FALSE, NULL, 'PCT',
  'BLOCK', '탄산가스 농도가 기준(1.5% 미만)을 넘었습니다.', '환기 후 재측정',
  '산업안전보건기준에 관한 규칙 제618조', 'https://www.law.go.kr/법령/산업안전보건기준에관한규칙'),
 ('CONFINED_CO_MAX', 1, 'CONFINED', 'MEASUREMENT', 'measurement.CO', 'LT',
  NULL, TRUE, 30.000, FALSE, NULL, 'PPM',
  'BLOCK', '일산화탄소 농도가 기준(30ppm 미만)을 넘었습니다.', '환기 후 재측정',
  '산업안전보건기준에 관한 규칙 제618조', 'https://www.law.go.kr/법령/산업안전보건기준에관한규칙'),
 ('CONFINED_H2S_MAX', 1, 'CONFINED', 'MEASUREMENT', 'measurement.H2S', 'LT',
  NULL, TRUE, 10.000, FALSE, NULL, 'PPM',
  'BLOCK', '황화수소 농도가 기준(10ppm 미만)을 넘었습니다.', '환기 후 재측정',
  '산업안전보건기준에 관한 규칙 제618조', 'https://www.law.go.kr/법령/산업안전보건기준에관한규칙'),
 -- 가연성 가스: 기준규칙 제618조 적정공기 정의에는 없고, KOSHA GUIDE H-80-2021 8.5 <표 2> 판정 기준농도와 [별지 1] 출입허가 기준에 있다
 -- (참고: P-94-2021 별지 양식은 "HC: 0%"로 더 엄격하게 표기. 판정은 H-80 기준을 쓴다)
 ('CONFINED_LEL_MAX', 1, 'CONFINED', 'MEASUREMENT', 'measurement.LEL', 'LT',
  NULL, TRUE, 10.000, FALSE, NULL, 'PCT_LEL',
  'BLOCK', '가연성 가스 농도가 기준(폭발하한의 10% 미만)을 넘었습니다.', '환기 후 재측정',
  'KOSHA GUIDE H-80-2021 8.5 <표 2>', NULL),

 -- ---------------------------------------------------------------- 화기 (화재위험작업)
 -- 근거: 산업안전보건기준에 관한 규칙 제241조·제241조의2, KOSHA GUIDE P-94-2021 6.1(3), A-G-14-2026,
 --       공단 「소규모 사업장의 화기작업 안전대책 및 수칙」(2016-교육미디어-946, 작성 기준 KOSHA GUIDE P-35-2012)
 -- 주의: 제241조제2항은 "가연성물질이 있는 장소", 제241조의2는 "작업반경 11m 이내 가연성물질" 등 조건이 붙는다.
 --       지금 엔진은 조건부 규칙이 없어 화기작업이면 모두 적용한다. 예외(상시·반복 작업 장소 등)는 3주차 "규칙 예외 보완"에서.
 -- 가연성 가스: 위 2016 자료 "폭발하한 기준의 10%에 도달할 경우 화기작업 중단" → 10% 미만이어야 작업 가능 (값이 없으면 엔진이 위반 처리)
 ('HOT_LEL_MAX', 1, 'HOT', 'MEASUREMENT', 'measurement.LEL', 'LT',
  NULL, TRUE, 10.000, FALSE, NULL, 'PCT_LEL',
  'BLOCK', '가연성 가스 농도가 폭발하한의 10%에 도달했습니다. 화기작업을 할 수 없습니다.', '화기작업 중단, 환기 후 재측정',
  '공단 소규모 사업장의 화기작업 안전대책 및 수칙(2016-교육미디어-946, KOSHA GUIDE P-35-2012 기준), P-94-2021 6.1(3)(나)', NULL),
 ('HOT_FIRE_WATCHER', 1, 'HOT', 'STAFFING', 'form.watcher_name', 'REQUIRED',
  NULL, TRUE, NULL, FALSE, NULL, NULL,
  'BLOCK', '화재감시자가 지정되지 않았습니다.', '화재감시자 지정 (대피용 방연장비 지급)',
  '산업안전보건기준에 관한 규칙 제241조의2제1항', 'https://www.law.go.kr/법령/산업안전보건기준에관한규칙'),
 ('HOT_EXTINGUISHER', 1, 'HOT', 'REQUIRED_FIELD', 'form.extinguisher_ready', 'IN',
  NULL, TRUE, NULL, FALSE, JSON_ARRAY(TRUE), NULL,
  'BLOCK', '가연성물질 방호조치와 소화기구 비치가 확인되지 않았습니다.', '화재 종류에 맞는 소화기 비치, 인근 가연물 방호',
  '산업안전보건기준에 관한 규칙 제241조제2항제3호, KOSHA GUIDE P-94-2021 6.1(3)(자)', 'https://www.law.go.kr/법령/산업안전보건기준에관한규칙'),
 ('HOT_SPARK_PREVENTION', 1, 'HOT', 'REQUIRED_FIELD', 'form.spark_prevention', 'IN',
  NULL, TRUE, NULL, FALSE, JSON_ARRAY(TRUE), NULL,
  'BLOCK', '불꽃·불티 비산방지조치가 확인되지 않았습니다.', '용접불티 비산방지덮개, 용접방화포 설치',
  '산업안전보건기준에 관한 규칙 제241조제2항제4호, KOSHA GUIDE P-94-2021 6.1(3)(사)', 'https://www.law.go.kr/법령/산업안전보건기준에관한규칙'),
 ('HOT_FLAMMABLE_VAPOR_REMOVED', 1, 'HOT', 'REQUIRED_FIELD', 'form.flammable_vapor_removed', 'IN',
  NULL, TRUE, NULL, FALSE, JSON_ARRAY(TRUE), NULL,
  'BLOCK', '인화성 액체의 증기·인화성 가스 제거(환기 등)가 확인되지 않았습니다.', '환기 등으로 인화성 증기·가스 제거 후 확인',
  '산업안전보건기준에 관한 규칙 제241조제2항제5호', 'https://www.law.go.kr/법령/산업안전보건기준에관한규칙')
AS new
ON DUPLICATE KEY UPDATE
  work_type = new.work_type, category = new.category, target = new.target,
  operator = new.operator, min_value = new.min_value, min_inclusive = new.min_inclusive,
  max_value = new.max_value, max_inclusive = new.max_inclusive, allowed = new.allowed, unit = new.unit,
  severity = new.severity, message = new.message, action = new.action,
  legal_basis = new.legal_basis, source_url = new.source_url;

-- TODO(C): 원문 확인 후 추가
--  - CONFINED: 감시인 배치(REQUIRED), 송기마스크 등 (2주차 밀폐 허가)
--  - CONFINED: 가연성 가스는 "인화성물질 취급 설비 내부"일 때만 측정 대상 (P-94 6.3(1)(마)②) → 조건부 규칙 (3주차)
--  - 작업 중 측정(DURING): 지하작업장·가스도관 굴착은 인화하한계 25% 이상이면 대피·점화원 중지 (기준규칙 제296조제2호)
--  - 공통: 허가 유효시간 (P-94 4.6: 허가기간·일일 정상근무시간 이내, 익일 지속 시 재발급)
--  - HEIGHT: 2m 이상 작업 시 작업발판·안전대 (기준규칙 추락 방지 조항) (2주차 고소 허가)
--  - 공통: 허가 유효시간, 동일 구역 동시작업 충돌(CONFLICT)
