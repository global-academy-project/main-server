package com.example.backend.form.repository;

import com.example.backend.form.entity.FormTemplate;
import com.example.backend.form.entity.FormTemplateStatus;
import com.example.backend.form.entity.FormType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FormTemplateRepository extends JpaRepository<FormTemplate, Long> {

    /** 현장 전용 켜진 판 (없으면 findActiveCompanyWide) */
    Optional<FormTemplate> findByFormTypeAndSiteIdAndStatus(FormType formType, Long siteId, FormTemplateStatus status);

    /** 회사 전체 공통 켜진 판 */
    Optional<FormTemplate> findByFormTypeAndSiteIdIsNullAndStatus(FormType formType, FormTemplateStatus status);

    /** 판 번호로 찾기 (업무 기록의 form_schema_version) */
    Optional<FormTemplate> findByFormTypeAndVersionAndSiteIdIsNull(FormType formType, String version);

    List<FormTemplate> findByFormTypeOrderByIdDesc(FormType formType);
}
