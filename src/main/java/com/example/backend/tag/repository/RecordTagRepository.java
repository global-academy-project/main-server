package com.example.backend.tag.repository;

import com.example.backend.tag.entity.RecordTag;
import com.example.backend.tag.entity.RecordType;
import com.example.backend.tag.entity.TagAxis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecordTagRepository extends JpaRepository<RecordTag, Long> {

    List<RecordTag> findByRecordTypeAndRecordIdOrderByAxisAscIdAsc(RecordType recordType, Long recordId);

    /** 축·값으로 기록 찾기 (예: SUBSTANCE 108-88-3 이 붙은 아차사고) */
    List<RecordTag> findByRecordTypeAndAxisAndValue(RecordType recordType, TagAxis axis, String value);

    boolean existsByRecordTypeAndRecordIdAndAxisAndValue(RecordType recordType, Long recordId, TagAxis axis, String value);
}
