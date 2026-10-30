package com.example.backend.approval.service;

import com.example.backend.approval.entity.ApproverRole;
import com.example.backend.site.entity.SiteRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 현장 역할 서열: 높은 역할은 낮은 역할을 포함한다 (계획서 7-5장) */
@DisplayName("결재 역할 서열")
class ApprovalRankTest {

    @Test
    @DisplayName("작성자 < 승인자 < 현장 관리자 < 회사 관리자, 모르는 값·없음은 -1")
    void order() {
        assertThat(ApprovalService.rankOf("WRITER")).isLessThan(ApprovalService.rankOf("APPROVER"));
        assertThat(ApprovalService.rankOf("APPROVER")).isLessThan(ApprovalService.rankOf("SITE_ADMIN"));
        assertThat(ApprovalService.rankOf("SITE_ADMIN")).isLessThan(ApprovalService.rankOf("ADMIN"));
        assertThat(ApprovalService.rankOf((String) null)).isEqualTo(-1);
        assertThat(ApprovalService.rankOf("GUEST")).isEqualTo(-1);
    }

    @Test
    @DisplayName("현장 역할·결재 역할 enum 값이 모두 서열에 있다 (값을 추가하면 서열도 추가)")
    void allEnumValuesRanked() {
        for (SiteRole r : SiteRole.values()) {
            assertThat(ApprovalService.rankOf(r.name())).as(r.name()).isGreaterThanOrEqualTo(0);
        }
        for (ApproverRole r : ApproverRole.values()) {
            assertThat(ApprovalService.rankOf(r)).as(r.name()).isGreaterThanOrEqualTo(1);
        }
    }
}
