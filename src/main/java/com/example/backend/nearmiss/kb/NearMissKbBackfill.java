package com.example.backend.nearmiss.kb;

import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.nearmiss.entity.NearMissStatus;
import com.example.backend.nearmiss.repository.NearMissRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 사내 아차사고 RAG 일괄 적재 (백필). 적재는 태그를 저장할 때만 한 건씩 일어나므로, 적재를 켜기 전에
 * 이미 사람이 태그를 저장한 보고나 적재가 실패했던 보고를 한꺼번에 맞춘다.
 *  - 대상: 사람이 태그를 저장한 보고(REPORTED 가 아닌 것). 현장을 주면 그 현장만
 *  - 한 건씩 NearMissKbListener.sync 와 같게: 태그가 있으면 적재(같은 point 덮어씀), 모두 지웠으면 삭제
 *  - 적재가 꺼져 있으면(app.kb-ingest.client=none) 409. 실패한 건은 번호를 돌려준다 (다시 실행하면 됨)
 * 순서(A 권장, 11/18): 공공 자료 재색인 → 이 백필 → 건수 확인.
 */
@Service
public class NearMissKbBackfill {

    private static final Logger log = LoggerFactory.getLogger(NearMissKbBackfill.class);
    static final int MAX_FAILED_IDS = 50;

    private final KbIngestClient client;
    private final NearMissKbListener listener;
    private final NearMissRepository nearMissRepository;

    public NearMissKbBackfill(KbIngestClient client, NearMissKbListener listener, NearMissRepository nearMissRepository) {
        this.client = client;
        this.listener = listener;
        this.nearMissRepository = nearMissRepository;
    }

    /** total 대상 수, synced 맞춘 수, failed 실패 수, failedIds 실패한 보고 번호(앞 50개) */
    public record Result(Long siteId, int total, int synced, int failed, List<Long> failedIds) {
    }

    public Result run(Long siteId) {
        if (!client.enabled()) {
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "아차사고 RAG 적재가 꺼져 있습니다. APP_KB_INGEST_CLIENT=http 로 켠 뒤 실행하세요.");
        }
        Result result = sweep(siteId, listener::sync);
        log.info("아차사고 RAG 일괄 적재. siteId={} total={} synced={} failed={}", siteId, result.total(), result.synced(),
                result.failed());
        return result;
    }

    /** 대상 보고마다 sync 를 부른다 (true 면 성공) */
    Result sweep(Long siteId, Predicate<Long> sync) {
        List<Long> ids = nearMissRepository.findConfirmedIds(siteId, NearMissStatus.REPORTED);
        int synced = 0;
        List<Long> failedIds = new ArrayList<>();
        int failed = 0;
        for (Long id : ids) {
            if (sync.test(id)) {
                synced++;
            } else {
                failed++;
                if (failedIds.size() < MAX_FAILED_IDS) {
                    failedIds.add(id);
                }
            }
        }
        return new Result(siteId, ids.size(), synced, failed, failedIds);
    }
}
