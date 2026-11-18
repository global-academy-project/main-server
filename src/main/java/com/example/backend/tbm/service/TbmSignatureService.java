package com.example.backend.tbm.service;

import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.history.entity.HistoryAction;
import com.example.backend.history.entity.HistoryTarget;
import com.example.backend.history.entity.RecordHistory;
import com.example.backend.history.repository.RecordHistoryRepository;
import com.example.backend.signature.entity.Signature;
import com.example.backend.signature.entity.SignaturePurpose;
import com.example.backend.signature.entity.SignatureTarget;
import com.example.backend.signature.repository.SignatureRepository;
import com.example.backend.site.service.SiteGuard;
import com.example.backend.tbm.dto.TbmResponse;
import com.example.backend.tbm.entity.TbmRecord;
import com.example.backend.tbm.entity.TbmStatus;
import com.example.backend.tbm.repository.TbmAttendeeRepository;
import com.example.backend.tbm.repository.TbmRecordRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * TBM 참석자 서명. 실시 완료(DONE)된 TBM 의 등록 참석자만, 한 사람 한 번.
 * 서명은 지울 수 없고(V9 trg_sig_*), 서명 순간의 TBM 내용 해시를 같이 남긴다.
 * 서명 후 되돌려 내용을 고치면 조회 때 "서명 후 내용 변경"으로 드러난다 (TbmResponse.Attendee.signatureChanged).
 */
@Service
public class TbmSignatureService {

    private final TbmRecordRepository tbmRepository;
    private final TbmAttendeeRepository attendeeRepository;
    private final SignatureRepository signatureRepository;
    private final RecordHistoryRepository historyRepository;
    private final TbmService tbmService;
    private final SiteGuard siteGuard;
    private final JsonMapper jsonMapper;
    private final EntityManager em;

    public TbmSignatureService(TbmRecordRepository tbmRepository, TbmAttendeeRepository attendeeRepository,
                               SignatureRepository signatureRepository, RecordHistoryRepository historyRepository,
                               TbmService tbmService, SiteGuard siteGuard, JsonMapper jsonMapper,
                               EntityManager em) {
        this.tbmRepository = tbmRepository;
        this.attendeeRepository = attendeeRepository;
        this.signatureRepository = signatureRepository;
        this.historyRepository = historyRepository;
        this.tbmService = tbmService;
        this.siteGuard = siteGuard;
        this.jsonMapper = jsonMapper;
        this.em = em;
    }

    @Transactional
    public TbmResponse sign(Long userId, Long tbmId, Long workerId, String ip) {
        TbmRecord tbm = tbmRepository.findById(tbmId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "TBM 기록을 찾을 수 없습니다."));
        siteGuard.requireAccess(userId, tbm.getSiteId());
        if (tbm.getStatus() != TbmStatus.DONE) {
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "참석자 서명은 실시 완료한 TBM에서, 확인(승인) 전까지 받을 수 있습니다 (현재 " + tbm.getStatus() + ").");
        }
        boolean attendee = attendeeRepository.findByTbmId(tbmId).stream().anyMatch(a -> a.getWorkerId().equals(workerId));
        if (!attendee) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "이 TBM에 등록된 참석자가 아닙니다: " + workerId);
        }
        if (signatureRepository.existsByTargetTypeAndTargetIdAndPurposeAndWorkerId(SignatureTarget.TBM, tbmId,
                SignaturePurpose.ATTENDEE, workerId)) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "이미 서명한 참석자입니다.");
        }
        Signature sig = signatureRepository.saveAndFlush(Signature.byWorker(tbm.getSiteId(), workerId, SignatureTarget.TBM, tbmId,
                SignaturePurpose.ATTENDEE, tbmService.contentHash(tbm), null, ip));
        em.refresh(sig);   // 서명 시각(signed_at)은 DB 기본값이라 다시 읽어야 응답에 들어간다
        historyRepository.save(RecordHistory.byUser(tbm.getSiteId(), HistoryTarget.TBM, tbmId, HistoryAction.SIGN, userId,
                null, jsonMapper.writeValueAsString(Map.of("workerId", workerId, "purpose", "ATTENDEE"))));
        return tbmService.get(userId, tbmId);
    }
}
