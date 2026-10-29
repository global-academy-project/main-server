package com.example.backend.inspection.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 점검 항목 한 줄. 테이블 규칙은 V4 inspection_items.
 * 점검표 안에서 순번·항목 코드는 한 번씩. 불량(NG)이면 내용 필수 (ck_ii_ng_note).
 * 점검표가 DRAFT 일 때만 추가·수정·삭제 (V9 trg_ii_lock_*) → 서비스에서 점검표 상태를 먼저 확인.
 */
@Entity
@Table(name = "inspection_items")
public class InspectionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "inspection_id", nullable = false, updatable = false)
    private Long inspectionId;

    @Column(nullable = false)
    private int seq;

    /** 예: GRINDER_GUARD, EXTINGUISHER */
    @Column(name = "item_code", nullable = false, length = 50, updatable = false)
    private String itemCode;

    @Column(nullable = false, length = 300)
    private String question;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 5)
    private ItemResult result;

    @Column(length = 300)
    private String note;

    protected InspectionItem() {
    }

    public InspectionItem(Long inspectionId, int seq, String itemCode, String question) {
        if (inspectionId == null) {
            throw new IllegalArgumentException("점검표가 필요합니다.");
        }
        if (seq < 1) {
            throw new IllegalArgumentException("순번은 1부터입니다.");
        }
        if (itemCode == null || itemCode.isBlank() || itemCode.length() > 50) {
            throw new IllegalArgumentException("항목 코드가 필요합니다 (50자 이내).");
        }
        if (question == null || question.isBlank() || question.length() > 300) {
            throw new IllegalArgumentException("점검 내용이 필요합니다 (300자 이내).");
        }
        this.inspectionId = inspectionId;
        this.seq = seq;
        this.itemCode = itemCode.trim();
        this.question = question.trim();
    }

    /** 결과 기록. NG 면 내용 필수 */
    public void record(ItemResult result, String note) {
        String n = note == null || note.isBlank() ? null : note.trim();
        if (result == ItemResult.NG && n == null) {
            throw new IllegalArgumentException("불량이면 내용을 적어야 합니다.");
        }
        if (n != null && n.length() > 300) {
            throw new IllegalArgumentException("내용은 300자 이내입니다.");
        }
        this.result = result;
        this.note = n;
    }

    public Long getId() { return id; }
    public Long getInspectionId() { return inspectionId; }
    public int getSeq() { return seq; }
    public String getItemCode() { return itemCode; }
    public String getQuestion() { return question; }
    public ItemResult getResult() { return result; }
    public String getNote() { return note; }
}
