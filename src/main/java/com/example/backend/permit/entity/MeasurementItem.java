package com.example.backend.permit.entity;

/**
 * 가스 측정 항목과 고정 단위 (DB ck_pm_item_unit). 규칙 입력 이름은 measurement.{항목}
 * OTHER 는 단위가 정해지지 않아 지금은 받지 않는다.
 */
public enum MeasurementItem {
    O2("PCT"), CO2("PCT"), LEL("PCT_LEL"), H2S("PPM"), CO("PPM"), NH3("PPM"), CL2("PPM");

    private final String unit;

    MeasurementItem(String unit) {
        this.unit = unit;
    }

    public String unit() { return unit; }
}
