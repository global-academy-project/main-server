package com.example.backend.nearmiss.kb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 사람이 태그를 저장한 아차사고를 커밋 뒤 비동기로 kb_accidents 에 적재한다 (같은 point_id 로 덮어씀).
 * 실패해도 업무 저장은 그대로이고 로그만 남긴다. 다음 태그 저장 때 다시 보낸다.
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
        ingest(event.nearMissId());
    }

    /** 적재 (설정이 꺼져 있으면 아무것도 하지 않음). 성공하면 true */
    public boolean ingest(Long nearMissId) {
        if (!client.enabled()) {
            return false;
        }
        try {
            KbIngestClient.Chunk chunk = documents.document(nearMissId).orElse(null);
            if (chunk == null) {
                return false;
            }
            int indexed = client.upsert(NearMissKbDocuments.COLLECTION, List.of(chunk));
            log.info("아차사고 RAG 적재. nearMissId={} indexed={}", nearMissId, indexed);
            return indexed > 0;
        } catch (RuntimeException e) {
            log.warn("아차사고 RAG 적재 실패 (업무 저장은 그대로). nearMissId={} reason={}", nearMissId, e.getMessage());
            return false;
        }
    }
}
