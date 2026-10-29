package com.example.backend.master;

import com.example.backend.site.entity.*;
import com.example.backend.site.repository.*;
import com.example.backend.user.entity.User;
import com.example.backend.user.repository.UserRepository;
import com.example.backend.worker.entity.*;
import com.example.backend.worker.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기준 정보 엔티티를 실제 로컬 DB로 확인 (끝나면 롤백).
 * 현장 → 구역 → 설비 → 화학물질 → 팀 → 작업자 → 현장 배정 → 자격.
 * 각 단계가 V2 의 CHECK·외래키·유니크를 통과하고 다시 읽히는지 본다.
 */
@SpringBootTest
@Transactional
@DisplayName("기준 정보 엔티티 DB 통합")
class MasterEntitiesDbTest {

    @Autowired SiteRepository siteRepository;
    @Autowired WorkZoneRepository zoneRepository;
    @Autowired EquipmentRepository equipmentRepository;
    @Autowired SiteChemicalRepository chemicalRepository;
    @Autowired TeamRepository teamRepository;
    @Autowired WorkerRepository workerRepository;
    @Autowired WorkerSiteRepository workerSiteRepository;
    @Autowired WorkerQualificationRepository qualificationRepository;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager em;

    @Test
    @DisplayName("기준 정보 전체가 제약을 통과해 저장되고 다시 읽힌다")
    void saveAndReload() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        LocalDate today = LocalDate.now();
        Long adminId = userRepository.save(User.bootstrapAdmin(
                "master-test-" + tag + "@example.com", "HASH", LocalDateTime.now())).getId();

        // 현장 (설정 포함)
        Site site = new Site("테스트현장-" + tag, SiteMode.GUIDE);
        site.updateProfile("화학", 120, "울산 남구");
        site.updateSettings(LocalTime.of(8, 30), LocalTime.of(17, 30), 30, 60);
        Long siteId = siteRepository.saveAndFlush(site).getId();

        // 구역 · 설비 · 화학물질
        WorkZone tank = zoneRepository.save(new WorkZone(siteId, "T-101", "1번 저장탱크", "TANK", true, "질소 퍼지 후 진입"));
        zoneRepository.save(new WorkZone(siteId, "P-201", "배관랙", "PIPE_RACK", false, null));
        equipmentRepository.save(new Equipment(siteId, tank.getId(), "GD-01", "복합가스측정기", "GAS_DETECTOR", today.minusDays(1)));
        chemicalRepository.save(new SiteChemical(siteId, tank.getId(), "108-88-3", "톨루엔", new BigDecimal("200.500"), ChemicalUnit.L, today));
        chemicalRepository.save(new SiteChemical(siteId, null, null, "혼합 시너", null, null, null));

        // 팀 · 작업자 · 현장 배정 · 자격
        Team team = teamRepository.save(Team.external("협력업체-" + tag, null));
        Worker worker = new Worker(team.getId(), "김용접", "010-1234-5678", "용접", EmploymentType.CONTRACTOR);
        worker.linkUser(adminId);
        worker = workerRepository.save(worker);
        Worker left = workerRepository.save(new Worker(team.getId(), "이배관", null, "배관", EmploymentType.DAILY));
        workerSiteRepository.save(new WorkerSite(worker.getId(), siteId, today.minusDays(30)));
        WorkerSite leftSite = new WorkerSite(left.getId(), siteId, today.minusDays(30));
        leftSite.leave(today.minusDays(1));
        workerSiteRepository.save(leftSite);
        qualificationRepository.save(new WorkerQualification(worker.getId(), QualificationType.TRAINING,
                "CONFINED_SPACE_SPECIAL", "밀폐공간 특별교육", today.minusYears(1), today.plusDays(10),
                "안전보건공단", "KOSHA-001", adminId));
        qualificationRepository.save(new WorkerQualification(worker.getId(), QualificationType.LICENSE,
                "WELDING_LICENSE", "용접기능사", today.minusYears(3), today.minusDays(1), null, null, adminId));

        em.flush();
        em.clear();

        // 다시 읽기
        Site reloaded = siteRepository.findById(siteId).orElseThrow();
        assertThat(reloaded.getMode()).isEqualTo(SiteMode.GUIDE);
        assertThat(reloaded.getTbmDeadline()).isEqualTo(LocalTime.of(8, 30));
        assertThat(reloaded.getCreatedAt()).isNotNull();

        assertThat(zoneRepository.findBySiteIdAndActiveTrueOrderByCodeAsc(siteId))
                .extracting(WorkZone::getCode).containsExactly("P-201", "T-101");
        assertThat(zoneRepository.findByIdAndSiteId(tank.getId(), siteId).orElseThrow().isConfined()).isTrue();
        assertThat(equipmentRepository.findBySiteIdAndActiveTrueOrderByCodeAsc(siteId).get(0).isInspectionOverdue(today)).isTrue();

        assertThat(chemicalRepository.findForZone(siteId, tank.getId()))
                .extracting(SiteChemical::getName).containsExactly("톨루엔", "혼합 시너");
        SiteChemical toluene = chemicalRepository.findForZone(siteId, tank.getId()).get(0);
        assertThat(toluene.getZoneKey()).isEqualTo(tank.getId());
        assertThat(toluene.getMaxQuantity()).isEqualByComparingTo("200.5");

        Worker w = workerRepository.findByUserId(adminId).orElseThrow();
        assertThat(w.getPhone()).isEqualTo("01012345678");
        assertThat(w.getLockVersion()).isZero();
        assertThat(workerRepository.findActiveBySite(siteId)).extracting(Worker::getName).containsExactly("김용접");
        assertThat(workerSiteRepository.existsByWorkerIdAndSiteIdAndActiveTrue(left.getId(), siteId)).isFalse();

        assertThat(qualificationRepository.findValid(w.getId(), "CONFINED_SPACE_SPECIAL", today)).hasSize(1);
        assertThat(qualificationRepository.findValid(w.getId(), "WELDING_LICENSE", today)).isEmpty();
    }

    @Test
    @DisplayName("작업자 수정 시 lock_version 이 오른다")
    void workerVersion() {
        Worker w = workerRepository.saveAndFlush(new Worker(null, "박전기", null, "전기", null));
        w.update("박전기", "0212345678", "전기", EmploymentType.DIRECT);
        workerRepository.saveAndFlush(w);
        assertThat(w.getLockVersion()).isEqualTo(1);
    }
}
