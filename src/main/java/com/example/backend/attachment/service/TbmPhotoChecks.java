package com.example.backend.attachment.service;

import com.example.backend.attachment.dto.CorrectPhotoCheckRequest;
import com.example.backend.attachment.dto.PhotoCheckView;
import com.example.backend.attachment.entity.Attachment;
import com.example.backend.attachment.entity.PhotoCheck;
import com.example.backend.attachment.entity.PhotoCheckType;
import com.example.backend.attachment.repository.AttachmentRepository;
import com.example.backend.attachment.repository.PhotoCheckRepository;
import com.example.backend.attachment.vision.PhotoVision;
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
 *  - 사진 인식이 끝나면: 인식 값으로 새 행 (model = 인식 모델 이름). 작성자가 이미 고쳤으면 덮지 않는다
 *  - 작성자가 고칠 때: 고친 값으로 새 행 (model HUMAN). 앞 행(인식 모델 값)은 그대로 남아 학습 데이터가 된다
 * detail(JSON): {"tbmDate", "messages", "helmetMissing", "dateConfirmedFor", "reason", "correctedBy", "basedOn", "boxes"}
 * boxes(인식 모델이 찾은 위치)는 다시 확인·고치기 행에도 이어 둔다 (화면이 사진 위에 그림)
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
    private record Prior(Long checkId, String model, TbmPhotoRules.Observations seen, String reason, Long correctedBy,
                         JsonNode boxes) {
    }

    /** TBM 사진 한 장을 확인하고 결과 행을 더한다 (앞서 적힌 사진 속 내용은 이어받음) */
    public PhotoCheck check(Attachment photo) {
        Prior prior = prior(photo.getId());
        if (prior == null) {
            return save(photo, tbm(photo), TbmPhotoRules.Observations.NONE, TbmPhotoRules.MODEL, null, null, null, null);
        }
        // 앞 행이 인식·고친 값이면 그 이름을 이어 쓴다 (다시 확인은 파일 정보만 다시 봄)
        return save(photo, tbm(photo), prior.seen(), prior.model(), prior.reason(), prior.correctedBy(), prior.checkId(), prior.boxes());
    }

    /** 사진 인식 결과로 행을 더한다. 작성자가 이미 고친 사진이면 덮지 않고 null */
    public PhotoCheck applyVision(Attachment photo, PhotoVision vision) {
        Prior prior = prior(photo.getId());
        if (prior != null && TbmPhotoRules.HUMAN.equals(prior.model())) {
            return null;
        }
        String model = vision.model() == null || vision.model().isBlank() ? "VISION" : vision.model().strip();
        TbmPhotoRules.Observations seen = new TbmPhotoRules.Observations(valid(vision.people()), valid(vision.helmetMissing()),
                prior == null ? null : prior.seen().dateConfirmedFor());
        return save(photo, tbm(photo), seen, model.length() > 50 ? model.substring(0, 50) : model, null, null,
                prior == null ? null : prior.checkId(), boxes(vision));
    }

    private static Integer valid(Integer n) {
        return n == null || n < 0 || n > 500 ? null : n;
    }

    /** 인식 위치: 0~1 비율만, 최대 100개 */
    private JsonNode boxes(PhotoVision vision) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (vision.boxes() != null) {
            vision.boxes().stream().filter(b -> b != null && b.label() != null && in01(b.x()) && in01(b.y()) && in01(b.w()) && in01(b.h()))
                    .limit(100).forEach(b -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("label", b.label().length() > 20 ? b.label().substring(0, 20) : b.label());
                        m.put("score", b.score());
                        m.put("x", b.x());
                        m.put("y", b.y());
                        m.put("w", b.w());
                        m.put("h", b.h());
                        out.add(m);
                    });
        }
        return jsonMapper.valueToTree(out);
    }

    private static boolean in01(Double v) {
        return v != null && v >= 0 && v <= 1;
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
        return save(photo, tbm, seen, TbmPhotoRules.HUMAN, r.reason().strip(), userId, prior == null ? null : prior.checkId(),
                prior == null ? null : prior.boxes());
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
                            String reason, Long correctedBy, Long basedOn, JsonNode boxes) {
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
        detail.put("boxes", boxes == null || boxes.isNull() || boxes.isMissingNode() ? List.of() : boxes);
        PhotoCheck saved = photoCheckRepository.saveAndFlush(new PhotoCheck(photo.getId(), PhotoCheckType.TBM, seen.people(),
                d.detectedDate(), null, d.duplicateOf(), d.result(), jsonMapper.writeValueAsString(detail), model, null));
        em.refresh(saved);   // created_at 은 DB 가 채운다
        return saved;
    }

    private Prior prior(Long attachmentId) {
        return photoCheckRepository.findTopByAttachmentIdOrderByIdDesc(attachmentId).map(c -> {
            JsonNode node = detail(c);
            return new Prior(c.getId(), c.getModel(), new TbmPhotoRules.Observations(c.getDetectedPeople(),
                    integer(node, "helmetMissing"), date(node, "dateConfirmedFor")), text(node, "reason"), number(node, "correctedBy"),
                    node.path("boxes").isArray() ? node.path("boxes") : null);
        }).orElse(null);
    }

    private PhotoCheckView view(PhotoCheck c) {
        JsonNode node = detail(c);
        List<String> messages = new ArrayList<>();
        node.path("messages").forEach(m -> messages.add(m.asString()));
        LocalDate checkedTbmDate = date(node, "tbmDate");
        LocalDate confirmedFor = date(node, "dateConfirmedFor");
        List<PhotoCheckView.Box> boxes = new ArrayList<>();
        node.path("boxes").forEach(b -> boxes.add(new PhotoCheckView.Box(String.valueOf(text(b, "label")),
                b.path("score").isNumber() ? b.path("score").asDouble() : null,
                b.path("x").asDouble(), b.path("y").asDouble(), b.path("w").asDouble(), b.path("h").asDouble())));
        return new PhotoCheckView(c.getId(), c.getCheckType(), c.getResult(), c.getDetectedDate(), c.getDetectedPeople(),
                integer(node, "helmetMissing"), confirmedFor != null && confirmedFor.equals(checkedTbmDate),
                c.getDuplicateOf(), List.copyOf(messages), c.getModel(), text(node, "reason"), checkedTbmDate, c.getCreatedAt(),
                List.copyOf(boxes));
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
