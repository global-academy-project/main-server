package com.example.backend.attachment.service;

import com.example.backend.attachment.dto.CorrectPhotoCheckRequest;
import com.example.backend.attachment.dto.PhotoCheckView;
import com.example.backend.attachment.entity.Attachment;
import com.example.backend.attachment.entity.PhotoCheck;
import com.example.backend.attachment.entity.PhotoCheckType;
import com.example.backend.attachment.repository.AttachmentRepository;
import com.example.backend.attachment.repository.PhotoCheckRepository;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.tbm.entity.TbmRecord;
import com.example.backend.tbm.repository.TbmRecordRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TBM 사진 확인 결과를 photo_checks 에 남기고 읽는다 (규칙은 TbmPhotoRules).
 * 확인할 때마다 행을 새로 더한다 (이력). 화면은 사진마다 마지막 행만 본다.
 *  - 올릴 때·다시 확인할 때: 파일 정보로 확인. 앞서 적힌 사진 속 내용(인원·안전모·당일 확인)은 이어받는다
 *  - 작성자가 고칠 때: 고친 값으로 새 행 (model HUMAN). 앞 행(인식 모델 값)은 그대로 남아 학습 데이터가 된다
 * detail(JSON): {"tbmDate", "messages", "helmetMissing", "dateConfirmedFor", "reason", "correctedBy", "basedOn"}
 */
@Component
public class TbmPhotoChecks {

    private final PhotoCheckRepository photoCheckRepository;
    private final AttachmentRepository attachmentRepository;
    private final TbmRecordRepository tbmRepository;
    private final JsonMapper jsonMapper;
    private final EntityManager em;

    public TbmPhotoChecks(PhotoCheckRepository photoCheckRepository, AttachmentRepository attachmentRepository,
                          TbmRecordRepository tbmRepository, JsonMapper jsonMapper, EntityManager em) {
        this.photoCheckRepository = photoCheckRepository;
        this.attachmentRepository = attachmentRepository;
        this.tbmRepository = tbmRepository;
        this.jsonMapper = jsonMapper;
        this.em = em;
    }

    /** 앞 행의 사진 속 내용과 고친 정보 */
    private record Prior(Long checkId, String model, TbmPhotoRules.Observations seen, String reason, Long correctedBy) {
    }

    /** TBM 사진 한 장을 확인하고 결과 행을 더한다 (앞서 적힌 사진 속 내용은 이어받음) */
    public PhotoCheck check(Attachment photo) {
        Prior prior = prior(photo.getId());
        return save(photo, tbm(photo), prior == null ? TbmPhotoRules.Observations.NONE : prior.seen(),
                prior != null && TbmPhotoRules.HUMAN.equals(prior.model()) ? TbmPhotoRules.HUMAN : TbmPhotoRules.MODEL,
                prior == null ? null : prior.reason(), prior == null ? null : prior.correctedBy(),
                prior == null ? null : prior.checkId());
    }

    /** 작성자가 사진을 보고 고친 값으로 확인 결과 행을 더한다 */
    public PhotoCheck correct(Attachment photo, CorrectPhotoCheckRequest r, Long userId) {
        TbmRecord tbm = tbm(photo);
        if (Boolean.TRUE.equals(r.dateConfirmed()) && photo.getTakenAt() != null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "촬영 날짜 정보가 있는 사진은 당일 확인을 할 수 없습니다. 날짜가 다르면 다시 찍어 올려 주세요.");
        }
        Prior prior = prior(photo.getId());
        TbmPhotoRules.Observations seen = new TbmPhotoRules.Observations(r.people(), r.helmetMissing(),
                Boolean.TRUE.equals(r.dateConfirmed()) ? tbm.getTbmDate() : null);
        return save(photo, tbm, seen, TbmPhotoRules.HUMAN, r.reason().strip(), userId, prior == null ? null : prior.checkId());
    }

    /** 사진의 마지막 확인 결과 (없으면 null) */
    public PhotoCheckView latest(Long attachmentId) {
        return photoCheckRepository.findTopByAttachmentIdOrderByIdDesc(attachmentId).map(this::view).orElse(null);
    }

    // ------------------------------------------------------------------ 내부

    private TbmRecord tbm(Attachment photo) {
        return tbmRepository.findById(photo.getOwnerId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "TBM을 찾을 수 없습니다."));
    }

    private PhotoCheck save(Attachment photo, TbmRecord tbm, TbmPhotoRules.Observations seen, String model,
                            String reason, Long correctedBy, Long basedOn) {
        TbmPhotoRules.Duplicate duplicate = photo.getSha256() == null ? null : attachmentRepository
                .findFirstBySiteIdAndSha256AndIdNotOrderByIdAsc(photo.getSiteId(), photo.getSha256(), photo.getId())
                .filter(other -> other.getId() < photo.getId())
                .map(other -> new TbmPhotoRules.Duplicate(other.getId(), other.getOwnerType(), other.getOwnerId()))
                .orElse(null);
        TbmPhotoRules.Decision d = TbmPhotoRules.check(tbm.getId(), tbm.getTbmDate(), photo.getTakenAt(), duplicate, seen);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("tbmDate", tbm.getTbmDate().toString());
        detail.put("messages", d.messages());
        detail.put("helmetMissing", seen.helmetMissing());
        detail.put("dateConfirmedFor", seen.dateConfirmedFor() == null ? null : seen.dateConfirmedFor().toString());
        detail.put("reason", reason);
        detail.put("correctedBy", correctedBy);
        detail.put("basedOn", basedOn);
        PhotoCheck saved = photoCheckRepository.saveAndFlush(new PhotoCheck(photo.getId(), PhotoCheckType.TBM, seen.people(),
                d.detectedDate(), null, d.duplicateOf(), d.result(), jsonMapper.writeValueAsString(detail), model, null));
        em.refresh(saved);   // created_at 은 DB 가 채운다
        return saved;
    }

    private Prior prior(Long attachmentId) {
        return photoCheckRepository.findTopByAttachmentIdOrderByIdDesc(attachmentId).map(c -> {
            JsonNode node = detail(c);
            return new Prior(c.getId(), c.getModel(), new TbmPhotoRules.Observations(c.getDetectedPeople(),
                    integer(node, "helmetMissing"), date(node, "dateConfirmedFor")), text(node, "reason"), number(node, "correctedBy"));
        }).orElse(null);
    }

    private PhotoCheckView view(PhotoCheck c) {
        JsonNode node = detail(c);
        List<String> messages = new ArrayList<>();
        node.path("messages").forEach(m -> messages.add(m.asString()));
        LocalDate checkedTbmDate = date(node, "tbmDate");
        LocalDate confirmedFor = date(node, "dateConfirmedFor");
        return new PhotoCheckView(c.getId(), c.getCheckType(), c.getResult(), c.getDetectedDate(), c.getDetectedPeople(),
                integer(node, "helmetMissing"), confirmedFor != null && confirmedFor.equals(checkedTbmDate),
                c.getDuplicateOf(), List.copyOf(messages), c.getModel(), text(node, "reason"), checkedTbmDate, c.getCreatedAt());
    }

    private JsonNode detail(PhotoCheck c) {
        if (c.getDetail() == null) {
            return jsonMapper.createObjectNode();
        }
        try {
            return jsonMapper.readTree(c.getDetail());
        } catch (JacksonException e) {
            return jsonMapper.createObjectNode();   // 예전 형식이면 안내 없이 결과만
        }
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isIntegralNumber() ? v.asInt() : null;
    }

    private static Long number(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isIntegralNumber() ? v.asLong() : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isString() ? v.asString() : null;
    }

    private static LocalDate date(JsonNode node, String field) {
        String s = text(node, field);
        try {
            return s == null ? null : LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
