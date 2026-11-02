package com.example.backend.worker.service;

import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.history.entity.HistoryAction;
import com.example.backend.history.entity.HistoryTarget;
import com.example.backend.history.entity.RecordHistory;
import com.example.backend.history.repository.RecordHistoryRepository;
import com.example.backend.worker.dto.TeamRequest;
import com.example.backend.worker.dto.TeamResponse;
import com.example.backend.worker.entity.Team;
import com.example.backend.worker.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/** 팀·협력업체 (회사 공통, 현장에 속하지 않음). 조회는 로그인한 사람, 등록·수정은 회사 관리자 (/api/v1/admin/**) */
@Service
public class TeamService {

    private final TeamRepository teamRepository;
    private final RecordHistoryRepository historyRepository;
    private final JsonMapper jsonMapper;

    public TeamService(TeamRepository teamRepository, RecordHistoryRepository historyRepository, JsonMapper jsonMapper) {
        this.teamRepository = teamRepository;
        this.historyRepository = historyRepository;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(readOnly = true)
    public List<TeamResponse> list(boolean includeInactive) {
        return (includeInactive ? teamRepository.findAllByOrderByNameAsc() : teamRepository.findByActiveTrueOrderByNameAsc())
                .stream().map(TeamResponse::from).toList();
    }

    @Transactional
    public TeamResponse create(Long adminId, TeamRequest r) {
        Team team = guard(() -> new Team(r.name(), r.teamType(), r.businessNo()));
        checkUnique(team.getName(), team.getBusinessNo(), null);
        teamRepository.saveAndFlush(team);
        history(team, HistoryAction.CREATE, adminId);
        return TeamResponse.from(team);
    }

    @Transactional
    public TeamResponse update(Long adminId, Long teamId, TeamRequest r) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "팀을 찾을 수 없습니다."));
        String oldName = team.getName();
        String oldNo = team.getBusinessNo();
        guard(() -> {
            team.update(r.name(), r.teamType(), r.businessNo());
            return team;
        });
        checkUnique(team.getName().equals(oldName) ? null : team.getName(),
                team.getBusinessNo() == null || team.getBusinessNo().equals(oldNo) ? null : team.getBusinessNo(), teamId);
        if (Boolean.FALSE.equals(r.active())) team.deactivate();
        if (Boolean.TRUE.equals(r.active())) team.activate();
        teamRepository.saveAndFlush(team);
        history(team, HistoryAction.UPDATE, adminId);
        return TeamResponse.from(team);
    }

    private void checkUnique(String name, String businessNo, Long selfId) {
        if (name != null && teamRepository.existsByName(name)) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "같은 이름의 팀이 있습니다: " + name);
        }
        if (businessNo != null && teamRepository.existsByBusinessNo(businessNo)) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "같은 사업자등록번호의 업체가 있습니다.");
        }
    }

    private void history(Team team, HistoryAction action, Long userId) {
        historyRepository.save(RecordHistory.byUser(null, HistoryTarget.TEAM, team.getId(), action, userId, null,
                jsonMapper.writeValueAsString(Map.of("name", team.getName(), "teamType", team.getTeamType().name(),
                        "active", team.isActive()))));
    }

    private static <T> T guard(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, e.getMessage());
        }
    }
}
