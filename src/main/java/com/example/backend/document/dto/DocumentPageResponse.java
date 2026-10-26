package com.example.backend.document.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/** 목록 응답 (6): content, page(0부터), size, totalElements, totalPages, hasNext. */
public record DocumentPageResponse(
        List<DocumentListItem> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {
    public static DocumentPageResponse from(Page<DocumentListItem> p) {
        return new DocumentPageResponse(p.getContent(), p.getNumber(), p.getSize(),
                p.getTotalElements(), p.getTotalPages(), p.hasNext());
    }
}
