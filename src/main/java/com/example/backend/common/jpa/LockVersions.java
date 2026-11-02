package com.example.backend.common.jpa;

import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Component;

import java.util.function.IntSupplier;

/**
 * 저장할 때마다 lockVersion 을 한 번 올린다.
 * <p>
 * 위험요인·참석자·연결 작업처럼 자식 행만 바뀌면 부모 행은 그대로라 Hibernate 가 @Version 을 올리지 않는다.
 * 그러면 같은 버전으로 두 번 저장해도 막히지 않으므로, 부모 행이 안 바뀐 경우 직접 올린다.
 * 그 사이 다른 사람이 저장했으면(버전이 다르면) 409. 올린 뒤 엔티티를 DB 값으로 다시 읽는다.
 * 대상 엔티티는 {@code id} 와 {@code lockVersion} 필드를 가져야 한다.
 */
@Component
public class LockVersions {

    private final EntityManager entityManager;

    public LockVersions(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * @param requested 요청에 실려 온(이미 확인한) 버전
     * @param current   엔티티의 지금 버전 (flush 뒤 다시 읽음)
     */
    public void bumpOnce(Object entity, Long id, int requested, IntSupplier current) {
        entityManager.flush();
        if (current.getAsInt() != requested) {
            return;   // 부모 행이 바뀌어 Hibernate 가 이미 올렸다
        }
        String name = entityManager.getMetamodel().entity(Hibernate.getClass(entity)).getName();
        int updated = entityManager.createQuery("update " + name + " e set e.lockVersion = e.lockVersion + 1"
                        + " where e.id = :id and e.lockVersion = :v")
                .setParameter("id", id).setParameter("v", requested).executeUpdate();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.CONFLICT_MODIFIED);
        }
        entityManager.refresh(entity);
    }
}
