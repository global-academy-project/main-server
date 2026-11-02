package com.example.backend.extract.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 문장 → 규칙 입력 추출 사전 (yaml app.extract.dictionary 의 JSON). 표현·단위 정리 규칙을 코드 밖에 둔다.
 *  - measurements: 측정 항목별 부르는 말과 받을 단위 (규칙 입력 measurement.{item} 과 같은 이름)
 *  - units: 단위 코드별 표기 (긴 표기부터 맞춘다)
 *  - workTypes: 작업 종류별 단서 말
 */
public record ExtractDictionary(String version, List<Measurement> measurements, Map<String, List<String>> units,
                                List<String> notMeasurementSuffixes, Map<String, List<String>> workTypes,
                                BigDecimal heightMinMeters, List<String> watcherWords, List<String> notNames,
                                List<String> nameStopFollowers) {

    public record Measurement(String item, List<String> names, List<String> units, BigDecimal max) {
    }
}
