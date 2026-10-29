package com.example.backend.form.repository;

import com.example.backend.form.entity.DocumentTarget;
import com.example.backend.form.entity.FormType;
import com.example.backend.form.entity.GeneratedDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** 추가·조회만 쓴다 (수정·삭제는 DB 트리거가 막음) */
public interface GeneratedDocumentRepository extends JpaRepository<GeneratedDocument, Long> {

    List<GeneratedDocument> findByTargetTypeAndTargetIdOrderByCreatedAtDescIdDesc(DocumentTarget targetType,
                                                                                  Long targetId);

    /** 다음 출력 순번 = 이 값 + 1 (없으면 0) */
    @Query("""
            select coalesce(max(d.seq), 0) from GeneratedDocument d
             where d.targetType = :targetType and d.targetId = :targetId and d.formType = :formType
            """)
    int findMaxSeq(@Param("targetType") DocumentTarget targetType, @Param("targetId") Long targetId,
                   @Param("formType") FormType formType);
}
