package com.example.backend.attachment.service;

import com.example.backend.attachment.entity.AttachmentOwner;
import com.example.backend.attachment.entity.PhotoCheckResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * TBM 사진 확인 (11/10 결정). 결과는 알리기만 하고 실시 완료·확인 요청을 막지 않는다.
 * <ul>
 *   <li>촬영 날짜(JPEG EXIF)가 TBM 날짜와 같으면 통과, 다르면 FAIL, 날짜 정보가 없으면 UNSURE.
 *       날짜 정보가 없을 때 작성자가 "TBM 당일 사진"으로 확인하면 통과</li>
 *   <li>같은 현장에 똑같은 파일(SHA-256)이 이미 있으면 FAIL. 같은 TBM 안에서 다시 올린 것도 FAIL</li>
 *   <li>인원·안전모: 인식 모델이나 작성자가 적은 값. 안전모 미착용이 있으면 FAIL, 모르면 판단하지 않음 (안전화는 사진으로 구별이 어려워 보지 않음).
 *       11/13: 인식 모델이 찾은 미착용은 정확도를 우리 사진으로 확인하기 전까지 UNSURE (app.photo.helmet-missing-fails=false).
 *       작성자가 고친 값의 미착용은 그대로 FAIL</li>
 * </ul>
 * 같은 파일 판단은 파일 내용이 한 바이트도 다르지 않을 때만이다. 같은 장면을 다시 찍었거나, 크기를 줄였거나,
 * 캡처한 사진은 다른 파일로 본다. 촬영 날짜·같은 파일은 파일에서 나온 사실이라 작성자가 고칠 수 없다.
 */
public final class TbmPhotoRules {

    public static final String MODEL = "RULE:v1";
    public static final String HUMAN = "HUMAN";

    /** 같은 파일로 찾은 이전 첨부 (그 첨부가 붙은 기록) */
    public record Duplicate(Long attachmentId, AttachmentOwner ownerType, Long ownerId) {
    }

    /**
     * 사진 속 내용 (인식 모델 또는 작성자가 고친 값). 모르면 null.
     * dateConfirmedFor: 작성자가 "이 날짜의 TBM 사진이 맞다"고 확인한 TBM 날짜 (TBM 날짜가 바뀌면 다시 확인해야 함)
     */
    public record Observations(Integer people, Integer helmetMissing, LocalDate dateConfirmedFor) {
        public static final Observations NONE = new Observations(null, null, null);
    }

    /** 확인 결과: 결과, 사진의 촬영 날짜(없으면 null), 같은 파일 첨부 id(없으면 null), 화면에 보여 줄 안내 */
    public record Decision(PhotoCheckResult result, LocalDate detectedDate, Long duplicateOf, List<String> messages) {
    }

    private TbmPhotoRules() {
    }

    public static Decision check(Long tbmId, LocalDate tbmDate, LocalDateTime takenAt, Duplicate duplicate) {
        return check(tbmId, tbmDate, takenAt, duplicate, Observations.NONE);
    }

    /**
     * @param tbmId     확인하는 사진이 붙은 TBM
     * @param tbmDate   TBM 날짜
     * @param takenAt   사진 파일 안의 촬영 시각 (없으면 null)
     * @param duplicate 같은 현장에서 먼저 올린 똑같은 파일 (없으면 null)
     * @param seen      사진 속 내용 (모르면 Observations.NONE)
     */
    public static Decision check(Long tbmId, LocalDate tbmDate, LocalDateTime takenAt, Duplicate duplicate, Observations seen) {
        return check(tbmId, tbmDate, takenAt, duplicate, seen, true);
    }

    /**
     * @param helmetMissingFails 안전모 미착용이 있을 때 true 면 FAIL, false 면 UNSURE ("미착용으로 보이는 사람, 사진을 확인해 주세요").
     *                           인식 모델 값은 정확도를 확인하기 전까지 false, 작성자가 고친 값은 true
     */
    public static Decision check(Long tbmId, LocalDate tbmDate, LocalDateTime takenAt, Duplicate duplicate, Observations seen,
                                 boolean helmetMissingFails) {
        List<String> messages = new ArrayList<>();
        boolean fail = false;
        boolean unsure = false;

        if (duplicate != null) {
            fail = true;
            boolean sameTbm = duplicate.ownerType() == AttachmentOwner.TBM && duplicate.ownerId().equals(tbmId);
            messages.add(sameTbm
                    ? "이 TBM에 똑같은 사진이 이미 있습니다 (사진 #" + duplicate.attachmentId() + ")."
                    : "다른 기록(" + label(duplicate.ownerType()) + " #" + duplicate.ownerId() + ")에 올린 사진과 똑같은 파일입니다 (사진 #"
                    + duplicate.attachmentId() + ").");
        }

        LocalDate taken = takenAt == null ? null : takenAt.toLocalDate();
        if (taken == null) {
            if (tbmDate.equals(seen.dateConfirmedFor())) {
                messages.add("촬영 날짜 정보는 없지만 작성자가 TBM 당일(" + tbmDate + ") 사진으로 확인했습니다.");
            } else {
                unsure = true;
                messages.add("사진에 촬영 날짜 정보가 없어 날짜를 확인하지 못했습니다. 메신저로 받은 사진은 이 정보가 지워지는 경우가 많습니다.");
            }
        } else if (!taken.equals(tbmDate)) {
            fail = true;
            messages.add("촬영 날짜(" + taken + ")가 TBM 날짜(" + tbmDate + ")와 다릅니다.");
        } else {
            messages.add("촬영 날짜가 TBM 날짜(" + tbmDate + ")와 같습니다.");
        }

        if (seen.people() != null) {
            messages.add("사진 속 인원 " + seen.people() + "명.");
        }
        if (seen.helmetMissing() != null) {
            if (seen.helmetMissing() == 0) {
                messages.add("안전모 모두 착용.");
            } else if (helmetMissingFails) {
                fail = true;
                messages.add("안전모 미착용 " + seen.helmetMissing() + "명.");
            } else {
                unsure = true;
                messages.add("안전모 미착용으로 보이는 사람 " + seen.helmetMissing() + "명. 사진을 확인해 주세요.");
            }
        }

        PhotoCheckResult result = fail ? PhotoCheckResult.FAIL : unsure ? PhotoCheckResult.UNSURE : PhotoCheckResult.PASS;
        return new Decision(result, taken, duplicate == null ? null : duplicate.attachmentId(), List.copyOf(messages));
    }

    /** TBM 전체 인원 비교 결과 (result 가 null 이면 비교하지 않음) */
    public record PeopleDecision(PhotoCheckResult result, String message) {
    }

    /**
     * 사진 속 인원(사진 중 가장 많은 값)과 참석자 명부 인원 비교. 알리기만 한다.
     * 명부보다 tolerance 명까지 적으면 통과 (촬영자 등), 더 적거나 명부보다 많으면 UNSURE.
     */
    public static PeopleDecision people(Integer maxPeople, int roster, int tolerance) {
        if (maxPeople == null) {
            return new PeopleDecision(null, "사진 속 인원을 아직 모릅니다. 사진의 \"결과 고치기\"로 적을 수 있습니다.");
        }
        if (roster == 0) {
            return new PeopleDecision(null, "참석자 명부가 비어 있어 사진 속 인원(" + maxPeople + "명)과 비교하지 않았습니다.");
        }
        if (maxPeople > roster) {
            return new PeopleDecision(PhotoCheckResult.UNSURE, "사진 속 인원(" + maxPeople + "명)이 참석자 명부(" + roster
                    + "명)보다 많습니다. 참석자 등록이 빠졌는지 확인하세요.");
        }
        if (maxPeople < roster - tolerance) {
            return new PeopleDecision(PhotoCheckResult.UNSURE, "사진 속 인원(" + maxPeople + "명)이 참석자 명부(" + roster
                    + "명)보다 적습니다. 빠진 사람이 있는지 확인하세요.");
        }
        return new PeopleDecision(PhotoCheckResult.PASS, "사진 속 인원 " + maxPeople + "명 · 참석자 명부 " + roster + "명"
                + (maxPeople < roster ? " (촬영자 등 " + tolerance + "명까지 차이는 통과)." : "."));
    }

    private static String label(AttachmentOwner type) {
        return switch (type) {
            case TBM -> "TBM";
            case PERMIT -> "작업허가";
            case INSPECTION -> "점검표";
            case NEAR_MISS -> "아차사고";
            default -> type.name();
        };
    }
}
