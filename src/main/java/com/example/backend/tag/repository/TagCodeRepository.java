package com.example.backend.tag.repository;

import com.example.backend.tag.entity.TagAxis;
import com.example.backend.tag.entity.TagCode;
import com.example.backend.tag.entity.TagCodeId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TagCodeRepository extends JpaRepository<TagCode, TagCodeId> {

    /** 화면 선택지·AI 분류 프롬프트에 넣을 코드 목록 */
    List<TagCode> findByAxisAndActiveTrueOrderBySortOrderAsc(TagAxis axis);

    List<TagCode> findByActiveTrueOrderByAxisAscSortOrderAsc();

    /** AI가 돌려준 코드가 코드표에 있는지 (저장 전 확인) */
    boolean existsByAxisAndCodeAndActiveTrue(TagAxis axis, String code);
}
