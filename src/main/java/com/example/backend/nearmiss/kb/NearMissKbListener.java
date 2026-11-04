package com.example.backend.nearmiss.kb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 사람이 태그를 저장한 아차사고를 커밋 뒤 비동기로 kb_accidents 와 맞춘다.
 *  - 태그가 있으면 적재 (같은 point_id 로 덮어씀)
 *  - 태그를 모두 지웠으면 point 삭제 (취소한 분류가 근거로 남지 않게. 올린 적 없는 id 여도 kb-ingest 는 성공)
 * 커밋 뒤의 DB 상태로 정하므로 이벤트에 태그 유무를 싣지 않는다.
 * 실패해도 업무 저장은 그대로이고 로그만 남긴다. 다음 태그 저장 때 다시 맞춘다.
 * 사내 아차사고가 에이전트 검색에 나오려면 ai-model 검색이 실행 현장(site_id)을 넘겨야 한다 (B·A 요청, 11/04).
 */
@Component
public class NearMissKbListener {

    private static final Logger log = LoggerFactory.getLogger(NearMissKbListener.class);

    private final KbIngestClient client;
    private final NearMissKbDocuments documents;

    public NearMissKbListener(KbIngestClient client, NearMissKbDocuments documents) {
        this.client = client;
        this.documents = documents;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTagsSaved(NearMissTagsSavedEvent event) {
        sync(event.nearMissId());
    }

    /** 적재 또는 삭제 (설정이 꺼져 있으면 아무것도 하지 않음). kb-ingest 호출이 성공하면 true */
    public boolean sync(Long nearMissId) {
        if (!client.enabled()) {
            return false;
        }
        try {
            KbIngestClient.Chunk chunk = documents.document(nearMissId).orElse(null);
            if (chunk == null) {
                client.delete(NearMissKbDocuments.COLLECTION, List.of(NearMissKbDocuments.pointId(nearMissId)));
                log.info("아차사고 RAG 삭제 (태그 없음). nearMissId={}", nearMissId);
                return true;
            }
            int indexed = client.upsert(NearMissKbDocuments.COLLECTION, List.of(chunk));
            log.info("아차사고 RAG 적재. nearMissId={} indexed={}", nearMissId, indexed);
            return indexed > 0;
        } catch (RuntimeException e) {
            log.warn("아차사고 RAG 맞추기 실패 (업무 저장은 그대로). nearMissId={} reason={}", nearMissId, e.getMessage());
            return false;
        }
    }
}
