package com.example.backend.nearmiss.kb;

import java.util.List;
import java.util.Map;

/**
 * 지식 적재 API(A, kb-ingest) 호출. yaml app.kb-ingest.client: none(기본, 적재 안 함) | http.
 * 실패는 예외로 알린다 (호출하는 쪽이 로그만 남기고 업무 저장은 그대로 둔다).
 */
public interface KbIngestClient {

    /** 청크 하나. pointId 는 UUID 문자열, payload 는 Qdrant payload 그대로 */
    record Chunk(String pointId, String text, Map<String, Object> payload) {
    }

    boolean enabled();

    /** POST /ingest/chunks. 적재한 청크 수 */
    int upsert(String collection, List<Chunk> chunks);

    /** POST /ingest/points/delete. 삭제를 요청한 point 수 (없는 id 를 넣어도 성공, 실제로 지운 수가 아님) */
    int delete(String collection, List<String> pointIds);
}
