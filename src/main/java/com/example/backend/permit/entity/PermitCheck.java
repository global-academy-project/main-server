package com.example.backend.permit.entity;

import jakarta.persistence.*;

/**
 * 허가서 체크 항목 (예: 소화기 비치, 불티 비산 방지). 테이블 규칙은 V4 permit_checks.
 * 허가서마다 항목 코드는 한 번만 (uk_pc_item). 허가가 DRAFT·CHECKING·BLOCKED 일 때만 추가·수정·삭제 (V9 trg_pc_lock_*)
 * → 서비스에서 허가 상태를 먼저 확인한다 (PermitStatus.isEditable).
 * 규칙 판정 입력(form.extinguisher_ready 등)과 같은 코드를 쓰면 화면 체크가 그대로 판정 입력이 된다.
 */
@Entity
@Table(name = "permit_checks")
public class PermitCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "permit_id", nullable = false, updatable = false)
    private Long permitId;

    /** 예: extinguisher_ready, spark_prevention, flammable_vapor_removed */
    @Column(name = "item_code", nullable = false, length = 50, updatable = false)
    private String itemCode;

    @Column(nullable = false, length = 200)
    private String label;

    @Column(nullable = false)
    private boolean checked;

    @Column(length = 300)
    private String note;

    protected PermitCheck() {
    }

    public PermitCheck(Long permitId, String itemCode, String label) {
        if (permitId == null) {
            throw new IllegalArgumentException("허가서가 필요합니다.");
        }
        if (itemCode == null || itemCode.isBlank() || itemCode.length() > 50) {
            throw new IllegalArgumentException("항목 코드가 필요합니다 (50자 이내).");
        }
        if (label == null || label.isBlank() || label.length() > 200) {
            throw new IllegalArgumentException("항목 이름이 필요합니다 (200자 이내).");
        }
        this.permitId = permitId;
        this.itemCode = itemCode.trim();
        this.label = label.trim();
    }

    /** 체크·해제와 메모 */
    public void mark(boolean checked, String note) {
        String n = note == null || note.isBlank() ? null : note.trim();
        if (n != null && n.length() > 300) {
            throw new IllegalArgumentException("메모는 300자 이내입니다.");
        }
        this.checked = checked;
        this.note = n;
    }

    public Long getId() { return id; }
    public Long getPermitId() { return permitId; }
    public String getItemCode() { return itemCode; }
    public String getLabel() { return label; }
    public boolean isChecked() { return checked; }
    public String getNote() { return note; }
}
