package com.example.backend.form.repository;

import com.example.backend.form.entity.DraftTarget;
import com.example.backend.form.entity.FormDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FormDraftRepository extends JpaRepository<FormDraft, Long> {

    /** 내 작성 중 목록 */
    List<FormDraft> findByUserIdOrderByUpdatedAtDesc(Long userId);

    /** 이 기록을 내가 고치는 중인 임시저장 (사람당 1개) */
    Optional<FormDraft> findByUserIdAndTargetTypeAndTargetId(Long userId, DraftTarget targetType, Long targetId);

    /** "다른 사람이 편집 중" 표시: 이 기록의 최근 임시저장 */
    List<FormDraft> findByTargetTypeAndTargetIdAndUpdatedAtAfterOrderByUpdatedAtDesc(DraftTarget targetType,
                                                                                    Long targetId,
                                                                                    LocalDateTime after);

    /**
     * 자동 저장 (조건부). 순번이 더 클 때만 덮어써서 늦게 도착한 옛 요청이 새 내용을 덮지 않는다.
     * 크기·JSON 확인은 호출 전에 서비스가 한다. 0 이면 무시된 요청.
     */
    @Modifying
    @Query("""
            update FormDraft d set d.clientSeq = :seq, d.title = :title, d.draftJson = :json, d.expiresAt = :expiresAt
             where d.id = :id and d.userId = :userId and d.clientSeq < :seq
            """)
    int autosave(@Param("id") Long id, @Param("userId") Long userId, @Param("seq") int seq,
                 @Param("title") String title, @Param("json") String json,
                 @Param("expiresAt") LocalDateTime expiresAt);

    /** 정리 배치 */
    @Modifying
    @Query("delete from FormDraft d where d.expiresAt <= :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
