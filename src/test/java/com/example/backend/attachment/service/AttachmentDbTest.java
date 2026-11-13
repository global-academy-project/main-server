package com.example.backend.attachment.service;

import com.example.backend.attachment.dto.AttachmentResponse;
import com.example.backend.attachment.dto.CorrectPhotoCheckRequest;
import com.example.backend.attachment.dto.TbmPhotoSummaryResponse;
import com.example.backend.attachment.vision.PhotoVision;
import com.example.backend.attachment.entity.AttachmentOwner;
import com.example.backend.attachment.entity.PhotoCheckResult;
import com.example.backend.attachment.repository.AttachmentRepository;
import com.example.backend.attachment.repository.PhotoCheckRepository;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMember;
import com.example.backend.site.entity.SiteMode;
import com.example.backend.site.entity.SiteRole;
import com.example.backend.site.repository.SiteMemberRepository;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.tbm.entity.TbmRecord;
import com.example.backend.tbm.repository.TbmRecordRepository;
import com.example.backend.user.entity.EmailVerifiedVia;
import com.example.backend.user.entity.User;
import com.example.backend.user.entity.UserRole;
import com.example.backend.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.example.backend.support.TestFixtures.expectCode;
import static org.assertj.core.api.Assertions.assertThat;

/** 사진 첨부 올리기·목록·내려받기·지우기 (실제 DB, 임시 저장 폴더, 끝나면 롤백) */
@SpringBootTest
@Transactional
@DisplayName("사진 첨부 API (실제 DB)")
class AttachmentDbTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 3);
    private static final Path STORAGE;

    static {
        try {
            STORAGE = Files.createTempDirectory("attachment-test-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("app.storage.dir", STORAGE::toString);
        registry.add("app.attachment.max-per-owner", () -> "3");
    }

    @Autowired AttachmentService service;
    @Autowired AttachmentRepository attachmentRepository;
    @Autowired PhotoCheckRepository photoCheckRepository;
    @Autowired TbmRecordRepository tbmRepository;
    @Autowired UserRepository userRepository;
    @Autowired SiteRepository siteRepository;
    @Autowired SiteMemberRepository siteMemberRepository;
    @Autowired EntityManager em;

    private Long adminId;
    private Long siteId;
    private Long writer;
    private Long otherWriter;
    private Long stranger;
    private Long tbmId;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        adminId = userRepository.save(User.bootstrapAdmin("att-admin-" + tag + "@example.com", "HASH", LocalDateTime.now())).getId();
        siteId = siteRepository.save(new Site("첨부현장-" + tag, SiteMode.GUIDE)).getId();
        writer = member("작성자", SiteRole.WRITER);
        otherWriter = member("다른작성자", SiteRole.WRITER);
        stranger = member("다른현장", null);
        tbmId = tbmRepository.save(new TbmRecord(siteId, DAY, null, null, writer)).getId();
    }

    private Long member(String name, SiteRole role) {
        User u = new User("att-" + UUID.randomUUID() + "@example.com", "HASH", name);
        u.verifyEmail(EmailVerifiedVia.ADMIN, LocalDateTime.now());
        u.approve(UserRole.MEMBER);
        Long id = userRepository.save(u).getId();
        if (role != null) {
            siteMemberRepository.save(new SiteMember(id, siteId, role, adminId));
        }
        return id;
    }

    private static MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "application/octet-stream", bytes);
    }

    @Test
    @DisplayName("TBM 사진: 저장 경로·촬영 시각·같은 사진 탐지, 형식·빈 파일·장수 제한, 다른 현장 막음, 내려받기")
    void uploadAndList() throws IOException {
        byte[] jpeg = TestImages.jpegWithExif("2026:11:03 08:10:00", true);
        AttachmentResponse a = service.upload(writer, AttachmentOwner.TBM, tbmId, file("C:\\사진\\tbm.jpg", jpeg));
        assertThat(a.ownerType()).isEqualTo(AttachmentOwner.TBM);
        assertThat(a.mimeType()).isEqualTo("image/jpeg");
        assertThat(a.fileName()).isEqualTo("tbm.jpg");
        assertThat(a.fileSize()).isEqualTo(jpeg.length);
        assertThat(a.takenAt()).isEqualTo(LocalDateTime.of(2026, 11, 3, 8, 10));
        assertThat(a.uploadedByName()).isEqualTo("작성자");
        assertThat(a.createdAt()).isNotNull();
        assertThat(a.url()).isEqualTo("/api/v1/attachments/" + a.attachmentId() + "/file");
        assertThat(a.duplicateOfId()).isNull();
        assertThat(a.check().result()).isEqualTo(PhotoCheckResult.PASS);   // 11/03 촬영 = TBM 날짜
        assertThat(a.check().detectedDate()).isEqualTo(DAY);
        assertThat(a.check().checkedTbmDate()).isEqualTo(DAY);
        assertThat(a.check().model()).isEqualTo("RULE:v1");
        assertThat(a.check().checkedAt()).isNotNull();

        String stored = attachmentRepository.findById(a.attachmentId()).orElseThrow().getStoredPath();
        assertThat(stored).matches("attachments/" + siteId + "/\\d{4}/\\d{2}/[0-9a-f-]{36}\\.jpg");
        assertThat(Files.readAllBytes(STORAGE.resolve(stored))).isEqualTo(jpeg);

        // 같은 파일을 다시 올리면 저장은 되고 앞 첨부를 알려 준다
        AttachmentResponse again = service.upload(writer, AttachmentOwner.TBM, tbmId, file("다시.jpg", jpeg));
        assertThat(again.duplicateOfId()).isEqualTo(a.attachmentId());
        assertThat(again.check().result()).isEqualTo(PhotoCheckResult.FAIL);   // 같은 TBM 에 똑같은 사진
        assertThat(again.check().duplicateOfId()).isEqualTo(a.attachmentId());

        expectCode(() -> service.upload(writer, AttachmentOwner.TBM, tbmId, file("a.txt", "hello world text".getBytes())),
                ErrorCode.INVALID_REQUEST);
        expectCode(() -> service.upload(writer, AttachmentOwner.TBM, tbmId, file("empty.jpg", new byte[0])), ErrorCode.INVALID_REQUEST);
        expectCode(() -> service.upload(writer, AttachmentOwner.TBM, tbmId, null), ErrorCode.INVALID_REQUEST);
        expectCode(() -> service.upload(stranger, AttachmentOwner.TBM, tbmId, file("x.png", TestImages.png())),
                ErrorCode.SITE_NOT_ALLOWED);
        expectCode(() -> service.upload(writer, AttachmentOwner.WORK, 1L, file("x.png", TestImages.png())), ErrorCode.INVALID_REQUEST);
        expectCode(() -> service.upload(writer, AttachmentOwner.TBM, 999_999_999L, file("x.png", TestImages.png())), ErrorCode.NOT_FOUND);

        AttachmentResponse png = service.upload(writer, AttachmentOwner.TBM, tbmId, file("p.png", TestImages.png()));   // 3장째
        assertThat(png.check().result()).isEqualTo(PhotoCheckResult.UNSURE);   // 촬영 날짜 정보 없음
        expectCode(() -> service.upload(writer, AttachmentOwner.TBM, tbmId, file("h.heic", TestImages.heic())),
                ErrorCode.INVALID_REQUEST);   // 기록당 3장 (테스트 설정)

        assertThat(service.list(writer, AttachmentOwner.TBM, tbmId)).extracting(AttachmentResponse::mimeType)
                .containsExactly("image/jpeg", "image/jpeg", "image/png");
        expectCode(() -> service.list(stranger, AttachmentOwner.TBM, tbmId), ErrorCode.SITE_NOT_ALLOWED);

        AttachmentService.FileDownload d = service.file(writer, a.attachmentId());
        assertThat(d.mimeType()).isEqualTo("image/jpeg");
        assertThat(d.fileName()).isEqualTo("tbm.jpg");
        assertThat(Files.readAllBytes(d.path())).isEqualTo(jpeg);
        expectCode(() -> service.file(stranger, a.attachmentId()), ErrorCode.SITE_NOT_ALLOWED);
    }

    @Test
    @DisplayName("지우기: 올린 사람·현장 관리자만, 승인된 TBM 은 더하거나 지울 수 없음")
    void deleteAndLock() {
        AttachmentResponse a = service.upload(writer, AttachmentOwner.TBM, tbmId, file("a.png", TestImages.png()));
        AttachmentResponse b = service.upload(writer, AttachmentOwner.TBM, tbmId, file("b.png", TestImages.heic()));

        expectCode(() -> service.delete(otherWriter, a.attachmentId()), ErrorCode.FORBIDDEN);
        AttachmentResponse copy = service.upload(writer, AttachmentOwner.TBM, tbmId, file("a2.png", TestImages.png()));
        expectCode(() -> service.delete(writer, a.attachmentId()), ErrorCode.INVALID_STATE);   // 뒤의 같은 사진이 가리킴
        service.delete(writer, copy.attachmentId());   // 확인 결과가 있어도 작성 중이면 지움
        assertThat(photoCheckRepository.findByAttachmentIdOrderByIdDesc(copy.attachmentId())).isEmpty();
        service.delete(writer, a.attachmentId());
        em.flush();
        assertThat(attachmentRepository.findById(a.attachmentId())).isEmpty();
        service.delete(adminId, b.attachmentId());   // 회사 관리자는 남의 사진도 지움
        assertThat(service.list(writer, AttachmentOwner.TBM, tbmId)).isEmpty();

        AttachmentResponse c = service.upload(writer, AttachmentOwner.TBM, tbmId, file("c.png", TestImages.png()));
        TbmRecord tbm = tbmRepository.findById(tbmId).orElseThrow();
        tbm.complete(LocalDateTime.of(2026, 11, 3, 8, 0), 5);
        tbm.approve();
        tbmRepository.saveAndFlush(tbm);
        expectCode(() -> service.upload(writer, AttachmentOwner.TBM, tbmId, file("d.png", TestImages.png())), ErrorCode.INVALID_STATE);
        expectCode(() -> service.delete(writer, c.attachmentId()), ErrorCode.INVALID_STATE);
        expectCode(() -> service.recheckTbmPhotos(writer, tbmId), ErrorCode.INVALID_STATE);
    }

    @Test
    @DisplayName("TBM 날짜를 바꾼 뒤 다시 확인: 행을 새로 더하고 최신 결과로 보여 줌")
    void recheck() {
        AttachmentResponse a = service.upload(writer, AttachmentOwner.TBM, tbmId,
                file("a.jpg", TestImages.jpegWithExif("2026:11:03 08:10:00", true)));
        assertThat(a.check().result()).isEqualTo(PhotoCheckResult.PASS);

        TbmRecord tbm = tbmRepository.findById(tbmId).orElseThrow();
        tbm.updateContent(null, null, DAY.plusDays(1), null, null, null);
        tbmRepository.saveAndFlush(tbm);

        AttachmentResponse after = service.recheckTbmPhotos(writer, tbmId).get(0);
        assertThat(after.check().result()).isEqualTo(PhotoCheckResult.FAIL);
        assertThat(after.check().checkedTbmDate()).isEqualTo(DAY.plusDays(1));
        assertThat(photoCheckRepository.findByAttachmentIdOrderByIdDesc(a.attachmentId())).hasSize(2);
        expectCode(() -> service.recheckTbmPhotos(stranger, tbmId), ErrorCode.SITE_NOT_ALLOWED);
    }

    @Test
    @DisplayName("결과 고치기: TBM 작성자·현장 관리자만, 사유 남김, 날짜 정보 있는 사진은 당일 확인 못 함, 다시 확인해도 고친 값 유지")
    void correct() {
        AttachmentResponse png = service.upload(writer, AttachmentOwner.TBM, tbmId, file("kakao.png", TestImages.png()));
        assertThat(png.check().result()).isEqualTo(PhotoCheckResult.UNSURE);

        CorrectPhotoCheckRequest fix = new CorrectPhotoCheckRequest(6, 0, true, "카톡으로 받아 날짜 정보 없음, 6명 모두 안전모 착용");
        expectCode(() -> service.correctTbmPhotoCheck(otherWriter, png.attachmentId(), fix), ErrorCode.FORBIDDEN);
        AttachmentResponse fixed = service.correctTbmPhotoCheck(writer, png.attachmentId(), fix);
        assertThat(fixed.check().result()).isEqualTo(PhotoCheckResult.PASS);
        assertThat(fixed.check().detectedPeople()).isEqualTo(6);
        assertThat(fixed.check().helmetMissing()).isZero();
        assertThat(fixed.check().dateConfirmed()).isTrue();
        assertThat(fixed.check().model()).isEqualTo("HUMAN");
        assertThat(fixed.check().reason()).startsWith("카톡으로 받아");
        assertThat(photoCheckRepository.findByAttachmentIdOrderByIdDesc(png.attachmentId())).hasSize(2);   // 앞 행 그대로

        AttachmentResponse rechecked = service.recheckTbmPhotos(writer, tbmId).get(0);
        assertThat(rechecked.check().result()).isEqualTo(PhotoCheckResult.PASS);
        assertThat(rechecked.check().detectedPeople()).isEqualTo(6);

        AttachmentResponse jpg = service.upload(writer, AttachmentOwner.TBM, tbmId,
                file("a.jpg", TestImages.jpegWithExif("2026:11:03 08:10:00", true)));
        expectCode(() -> service.correctTbmPhotoCheck(writer, jpg.attachmentId(), fix), ErrorCode.INVALID_REQUEST);
        AttachmentResponse helmet = service.correctTbmPhotoCheck(adminId, jpg.attachmentId(),
                new CorrectPhotoCheckRequest(5, 1, null, "뒤쪽 1명 안전모 없음"));
        assertThat(helmet.check().result()).isEqualTo(PhotoCheckResult.FAIL);
    }

    @Test
    @DisplayName("사진 인식 반영: 인원·위치를 남기고, 작성자가 고친 사진은 덮지 않음, 인원 요약")
    void vision() {
        AttachmentResponse a = service.upload(writer, AttachmentOwner.TBM, tbmId,
                file("a.jpg", TestImages.jpegWithExif("2026:11:03 08:10:00", true)));
        PhotoVision v = new PhotoVision("YOLOX-Nano", 4, null,
                List.of(new PhotoVision.Box("person", 0.9, 0.1, 0.2, 0.2, 0.6), new PhotoVision.Box("person", 0.8, 0.5, 0.2, 1.4, 0.6)));
        AttachmentResponse seen = service.applyVision(a.attachmentId(), v);
        assertThat(seen.check().model()).isEqualTo("YOLOX-Nano");
        assertThat(seen.check().detectedPeople()).isEqualTo(4);
        assertThat(seen.check().result()).isEqualTo(PhotoCheckResult.PASS);
        assertThat(seen.check().boxes()).hasSize(1);   // 0~1 밖 위치는 버림

        TbmPhotoSummaryResponse summary = service.tbmPhotoSummary(writer, tbmId);
        assertThat(summary.maxPeople()).isEqualTo(4);
        assertThat(summary.rosterCount()).isZero();
        assertThat(summary.result()).isNull();   // 명부가 비어 비교 안 함

        service.correctTbmPhotoCheck(writer, a.attachmentId(), new CorrectPhotoCheckRequest(5, 0, null, "뒤쪽 1명 가려짐"));
        AttachmentResponse kept = service.applyVision(a.attachmentId(), v);
        assertThat(kept.check().model()).isEqualTo("HUMAN");
        assertThat(kept.check().detectedPeople()).isEqualTo(5);
        assertThat(kept.check().boxes()).hasSize(1);   // 고친 행에도 위치는 이어 둠
    }

    @Test
    @DisplayName("인식 모델의 안전모 미착용은 UNSURE(확인 필요), 작성자가 미착용으로 고치면 FAIL (11/13)")
    void helmetFromVisionIsUnsure() {
        AttachmentResponse a = service.upload(writer, AttachmentOwner.TBM, tbmId,
                file("h.jpg", TestImages.jpegWithExif("2026:11:03 08:20:00", true)));
        PhotoVision v = new PhotoVision("YOLOX-Nano+helmet", 3, 1,
                List.of(new PhotoVision.Box("helmet", 0.9, 0.1, 0.1, 0.1, 0.1), new PhotoVision.Box("no_helmet", 0.8, 0.5, 0.1, 0.1, 0.1)));
        AttachmentResponse seen = service.applyVision(a.attachmentId(), v);
        assertThat(seen.check().result()).isEqualTo(PhotoCheckResult.UNSURE);
        assertThat(seen.check().messages()).anyMatch(m -> m.startsWith("안전모 미착용으로 보이는 사람 1명"));
        assertThat(seen.check().boxes()).extracting("label").containsExactly("helmet", "no_helmet");

        AttachmentResponse rechecked = service.recheckTbmPhotos(writer, tbmId).stream()
                .filter(r -> r.attachmentId().equals(a.attachmentId())).findFirst().orElseThrow();
        assertThat(rechecked.check().result()).isEqualTo(PhotoCheckResult.UNSURE);   // 다시 확인해도 인식 값이면 그대로

        AttachmentResponse fixed = service.correctTbmPhotoCheck(writer, a.attachmentId(), new CorrectPhotoCheckRequest(3, 1, null, "실제로 미착용"));
        assertThat(fixed.check().result()).isEqualTo(PhotoCheckResult.FAIL);
    }
}
