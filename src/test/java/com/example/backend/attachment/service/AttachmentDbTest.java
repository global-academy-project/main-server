package com.example.backend.attachment.service;

import com.example.backend.attachment.dto.AttachmentResponse;
import com.example.backend.attachment.entity.AttachmentOwner;
import com.example.backend.attachment.repository.AttachmentRepository;
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

        String stored = attachmentRepository.findById(a.attachmentId()).orElseThrow().getStoredPath();
        assertThat(stored).matches("attachments/" + siteId + "/\\d{4}/\\d{2}/[0-9a-f-]{36}\\.jpg");
        assertThat(Files.readAllBytes(STORAGE.resolve(stored))).isEqualTo(jpeg);

        // 같은 파일을 다시 올리면 저장은 되고 앞 첨부를 알려 준다
        AttachmentResponse again = service.upload(writer, AttachmentOwner.TBM, tbmId, file("다시.jpg", jpeg));
        assertThat(again.duplicateOfId()).isEqualTo(a.attachmentId());

        expectCode(() -> service.upload(writer, AttachmentOwner.TBM, tbmId, file("a.txt", "hello world text".getBytes())),
                ErrorCode.INVALID_REQUEST);
        expectCode(() -> service.upload(writer, AttachmentOwner.TBM, tbmId, file("empty.jpg", new byte[0])), ErrorCode.INVALID_REQUEST);
        expectCode(() -> service.upload(writer, AttachmentOwner.TBM, tbmId, null), ErrorCode.INVALID_REQUEST);
        expectCode(() -> service.upload(stranger, AttachmentOwner.TBM, tbmId, file("x.png", TestImages.png())),
                ErrorCode.SITE_NOT_ALLOWED);
        expectCode(() -> service.upload(writer, AttachmentOwner.WORK, 1L, file("x.png", TestImages.png())), ErrorCode.INVALID_REQUEST);
        expectCode(() -> service.upload(writer, AttachmentOwner.TBM, 999_999_999L, file("x.png", TestImages.png())), ErrorCode.NOT_FOUND);

        service.upload(writer, AttachmentOwner.TBM, tbmId, file("p.png", TestImages.png()));   // 3장째
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
    }
}
