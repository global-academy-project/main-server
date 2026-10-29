package com.example.backend.signature.repository;

import com.example.backend.signature.entity.SignaturePurpose;
import com.example.backend.signature.entity.SignatureTarget;
import com.example.backend.signature.entity.Signature;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 추가·조회만 쓴다 (수정·삭제는 DB 트리거가 막음) */
public interface SignatureRepository extends JpaRepository<Signature, Long> {

    List<Signature> findByTargetTypeAndTargetIdOrderBySignedAtAscIdAsc(SignatureTarget targetType, Long targetId);

    boolean existsByTargetTypeAndTargetIdAndPurposeAndUserId(SignatureTarget targetType, Long targetId,
                                                            SignaturePurpose purpose, Long userId);

    boolean existsByTargetTypeAndTargetIdAndPurposeAndWorkerId(SignatureTarget targetType, Long targetId,
                                                              SignaturePurpose purpose, Long workerId);
}
