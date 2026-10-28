package com.example.backend.rule.repository;

import com.example.backend.rule.entity.Rule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RuleRepository extends JpaRepository<Rule, Long> {

    /** 켜진 규칙 중 이 작업 종류들(요청한 종류 + ALL)에 해당하는 것 */
    List<Rule> findByEnabledTrueAndWorkTypeInOrderByRuleCodeAsc(Collection<String> workTypes);
}
