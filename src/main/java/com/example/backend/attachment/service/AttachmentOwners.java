package com.example.backend.attachment.service;

import com.example.backend.attachment.entity.AttachmentOwner;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.inspection.entity.InspectionStatus;
import com.example.backend.inspection.repository.InspectionRepository;
import com.example.backend.nearmiss.repository.NearMissRepository;
import com.example.backend.permit.repository.PermitRepository;
import com.example.backend.tbm.entity.TbmStatus;
import com.example.backend.tbm.repository.TbmRecordRepository;
import org.springframework.stereotype.Component;

/**
 * 첨부를 붙일 수 있는 기록과 그 상태 (다형 참조라 DB 외래키가 없어 여기서 확인).
 * 지금 받는 기록: TBM(작성 중·실시 완료), 작업허가(작성 중·판정 중·차단), 점검표(작성 중), 아차사고(항상).
 * 승인 요청·승인된 기록에는 사진을 더하거나 지울 수 없다 (승인 근거가 바뀌지 않게).
 */
@Component
public class AttachmentOwners {

    /** 기록의 현장과 지금 첨부를 바꿀 수 있는지 */
    public record Owner(Long siteId, boolean editable, String label) {
    }

    private final TbmRecordRepository tbmRepository;
    private final PermitRepository permitRepository;
    private final InspectionRepository inspectionRepository;
    private final NearMissRepository nearMissRepository;

    public AttachmentOwners(TbmRecordRepository tbmRepository, PermitRepository permitRepository,
                            InspectionRepository inspectionRepository, NearMissRepository nearMissRepository) {
        this.tbmRepository = tbmRepository;
        this.permitRepository = permitRepository;
        this.inspectionRepository = inspectionRepository;
        this.nearMissRepository = nearMissRepository;
    }

    public Owner find(AttachmentOwner type, Long id) {
        Owner owner = switch (type) {
            case TBM -> tbmRepository.findById(id)
                    .map(t -> new Owner(t.getSiteId(), t.getStatus() != TbmStatus.APPROVED, "TBM")).orElse(null);
            case PERMIT -> permitRepository.findById(id)
                    .map(p -> new Owner(p.getSiteId(), p.getStatus().isEditable(), "작업허가서")).orElse(null);
            case INSPECTION -> inspectionRepository.findById(id)
                    .map(i -> new Owner(i.getSiteId(), i.getStatus() == InspectionStatus.DRAFT, "점검표")).orElse(null);
            case NEAR_MISS -> nearMissRepository.findById(id)
                    .map(n -> new Owner(n.getSiteId(), true, "아차사고")).orElse(null);
            default -> throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "사진을 붙일 수 없는 기록 종류입니다: " + type + " (TBM·PERMIT·INSPECTION·NEAR_MISS)");
        };
        if (owner == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "첨부할 기록을 찾을 수 없습니다.");
        }
        return owner;
    }
}
