package com.example.backend.nearmiss.kb;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/** 적재하지 않음 (기본값, kb-ingest 없이 개발할 때) */
@Component
@ConditionalOnProperty(name = "app.kb-ingest.client", havingValue = "none", matchIfMissing = true)
public class NoKbIngestClient implements KbIngestClient {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public int upsert(String collection, List<Chunk> chunks) {
        return 0;
    }
}
