package com.example.backend.attachment.service;

import com.example.backend.attachment.dto.AttachmentResponse;
import com.example.backend.attachment.entity.Attachment;
import com.example.backend.attachment.entity.AttachmentKind;
import com.example.backend.attachment.entity.AttachmentOwner;
import com.example.backend.attachment.repository.AttachmentRepository;
import com.example.backend.attachment.repository.PhotoCheckRepository;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.history.entity.HistoryAction;
import com.example.backend.history.entity.HistoryTarget;
import com.example.backend.history.entity.RecordHistory;
import com.example.backend.history.repository.RecordHistoryRepository;
import com.example.backend.site.service.SiteAccessService;
import com.example.backend.site.service.SiteGuard;
import com.example.backend.user.entity.User;
import com.example.backend.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 사진 첨부: 올리기·목록·내려받기·지우기 (계획서 11장 파일 저장, D 안전일지 요청서 7번 TBM 사진).
 * 사진 내용 인식(인원·날짜)은 하지 않는다 (3주차 사진 인식이 photo_checks 에 남긴다).
 *  - 형식은 파일 앞부분으로 판별 (JPEG·PNG·HEIC), 크기·기록당 장수는 yaml app.attachment
 *  - 같은 현장에 똑같은 파일이 있으면 duplicateOfId 로 알려 주되 저장은 막지 않는다
 *  - 파일을 먼저 쓰고 DB 를 저장한다. 트랜잭션이 되돌려지면 쓴 파일을 지운다 (지울 때는 커밋 뒤에 파일을 지움)
 */
@Service
public class AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    /** 내려받기 응답에 쓰는 파일 정보 */
    public record FileDownload(Path path, String mimeType, String fileName, long size) {
    }

    private final AttachmentRepository attachmentRepository;
    private final PhotoCheckRepository photoCheckRepository;
    private final AttachmentOwners owners;
    private final FileStorage storage;
    private final RecordHistoryRepository historyRepository;
    private final UserRepository userRepository;
    private final SiteGuard siteGuard;
    private final SiteAccessService siteAccessService;
    private final JsonMapper jsonMapper;
    private final EntityManager em;
    private final Clock clock;
    private final long maxBytes;
    private final int maxPerOwner;

    public AttachmentService(AttachmentRepository attachmentRepository, PhotoCheckRepository photoCheckRepository,
                             AttachmentOwners owners, FileStorage storage, RecordHistoryRepository historyRepository,
                             UserRepository userRepository, SiteGuard siteGuard, SiteAccessService siteAccessService,
                             JsonMapper jsonMapper, EntityManager em, Clock clock,
                             @Value("${app.attachment.max-bytes}") long maxBytes,
                             @Value("${app.attachment.max-per-owner}") int maxPerOwner) {
        this.attachmentRepository = attachmentRepository;
        this.photoCheckRepository = photoCheckRepository;
        this.owners = owners;
        this.storage = storage;
        this.historyRepository = historyRepository;
        this.userRepository = userRepository;
        this.siteGuard = siteGuard;
        this.siteAccessService = siteAccessService;
        this.jsonMapper = jsonMapper;
        this.em = em;
        this.clock = clock;
        this.maxBytes = maxBytes;
        this.maxPerOwner = maxPerOwner;
    }

    @Transactional
    public AttachmentResponse upload(Long userId, AttachmentOwner ownerType, Long ownerId, MultipartFile file) {
        AttachmentOwners.Owner owner = owners.find(ownerType, ownerId);
        siteGuard.requireAccess(userId, owner.siteId());
        if (!owner.editable()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "승인 요청·승인된 " + owner.label() + "에는 사진을 더할 수 없습니다.");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "사진 파일이 필요합니다.");
        }
        if (file.getSize() > maxBytes) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "사진은 " + (maxBytes / 1024 / 1024) + "MB 까지 올릴 수 있습니다.");
        }
        if (attachmentRepository.findByOwnerTypeAndOwnerIdOrderByIdAsc(ownerType, ownerId).size() >= maxPerOwner) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "기록 하나에 사진은 " + maxPerOwner + "장까지 올릴 수 있습니다.");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "사진 파일을 읽지 못했습니다.");
        }
        ImageFiles.Type type = ImageFiles.detect(bytes);
        if (type == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "JPEG·PNG·HEIC 사진만 올릴 수 있습니다.");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        String stored = storage.store(owner.siteId(), now.toLocalDate(), type.extension(), bytes);
        deleteFileIfRolledBack(stored);
        Attachment saved;
        try {
            saved = attachmentRepository.saveAndFlush(new Attachment(owner.siteId(), ownerType, ownerId, AttachmentKind.PHOTO,
                    fileName(file.getOriginalFilename(), type), stored, type.mimeType(), (long) bytes.length,
                    sha256(bytes), ImageFiles.takenAt(bytes), userId));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, e.getMessage());
        }
        em.refresh(saved);   // created_at 은 DB 가 채운다
        history(saved, HistoryAction.UPDATE, userId, "attachment.add");
        return response(saved);
    }

    @Transactional(readOnly = true)
    public List<AttachmentResponse> list(Long userId, AttachmentOwner ownerType, Long ownerId) {
        AttachmentOwners.Owner owner = owners.find(ownerType, ownerId);
        siteGuard.requireAccess(userId, owner.siteId());
        return attachmentRepository.findByOwnerTypeAndOwnerIdOrderByIdAsc(ownerType, ownerId).stream()
                .map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public FileDownload file(Long userId, Long attachmentId) {
        Attachment a = load(userId, attachmentId);
        Path path = storage.existing(a.getStoredPath());
        if (path == null) {
            log.warn("첨부 파일이 저장소에 없습니다. attachmentId={}", a.getId());
            throw new BusinessException(ErrorCode.NOT_FOUND, "첨부 파일을 찾을 수 없습니다.");
        }
        return new FileDownload(path, a.getMimeType() == null ? "application/octet-stream" : a.getMimeType(),
                a.getOriginalName(), a.getFileSize() == null ? -1 : a.getFileSize());
    }

    /** 잘못 올린 사진 지우기: 기록을 바꿀 수 있을 때만, 올린 사람 본인이나 현장 관리자 이상. 사진 확인 결과가 붙은 것은 못 지움 */
    @Transactional
    public void delete(Long userId, Long attachmentId) {
        Attachment a = load(userId, attachmentId);
        AttachmentOwners.Owner owner = owners.find(a.getOwnerType(), a.getOwnerId());
        if (!owner.editable()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "승인 요청·승인된 " + owner.label() + "의 사진은 지울 수 없습니다.");
        }
        if (!a.getUploadedBy().equals(userId)) {
            User user = userRepository.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
            String role = siteAccessService.roleAt(user, a.getSiteId()).orElse(null);
            if (!"SITE_ADMIN".equals(role) && !"ADMIN".equals(role)) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "올린 사람이나 현장 관리자만 지울 수 있습니다.");
            }
        }
        if (photoCheckRepository.findTopByAttachmentIdOrderByIdDesc(a.getId()).isPresent()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "사진 확인 결과가 남아 있는 사진은 지울 수 없습니다.");
        }
        history(a, HistoryAction.UPDATE, userId, "attachment.delete");
        attachmentRepository.delete(a);
        attachmentRepository.flush();
        deleteFileAfterCommit(a.getStoredPath());
    }

    // ------------------------------------------------------------------ 내부

    private Attachment load(Long userId, Long attachmentId) {
        Attachment a = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "첨부를 찾을 수 없습니다."));
        siteGuard.requireAccess(userId, a.getSiteId());
        return a;
    }

    private AttachmentResponse response(Attachment a) {
        Long duplicateOf = a.getSha256() == null ? null : attachmentRepository
                .findFirstBySiteIdAndSha256AndIdNotOrderByIdAsc(a.getSiteId(), a.getSha256(), a.getId())
                .filter(other -> other.getId() < a.getId()).map(Attachment::getId).orElse(null);
        String uploader = userRepository.findById(a.getUploadedBy()).map(User::getName).orElse(null);
        return new AttachmentResponse(a.getId(), a.getSiteId(), a.getOwnerType(), a.getOwnerId(), a.getKind(),
                a.getOriginalName(), a.getMimeType(), a.getFileSize(), a.getTakenAt(), a.getUploadedBy(), uploader,
                a.getCreatedAt(), "/api/v1/attachments/" + a.getId() + "/file", duplicateOf);
    }

    private void history(Attachment a, HistoryAction action, Long userId, String change) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("change", change);
        after.put("attachmentId", a.getId());
        after.put("fileName", a.getOriginalName());
        historyRepository.save(RecordHistory.byUser(a.getSiteId(), HistoryTarget.valueOf(a.getOwnerType().name()),
                a.getOwnerId(), action, userId, null, jsonMapper.writeValueAsString(after)));
    }

    /** 원래 이름에서 경로를 떼고 255자로 자른다. 없으면 photo.{확장자} */
    static String fileName(String original, ImageFiles.Type type) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isEmpty()) {
            name = "photo." + type.extension();
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void deleteFileIfRolledBack(String stored) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    safeDelete(stored);
                }
            }
        });
    }

    private void deleteFileAfterCommit(String stored) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safeDelete(stored);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safeDelete(stored);
            }
        });
    }

    private void safeDelete(String stored) {
        try {
            storage.delete(stored);
        } catch (RuntimeException e) {
            log.warn("첨부 파일을 지우지 못했습니다 (나중에 정리). path={}", stored);
        }
    }
}
