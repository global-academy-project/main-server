package com.example.backend.worker.dto;

import com.example.backend.worker.entity.Team;
import com.example.backend.worker.entity.TeamType;

public record TeamResponse(Long teamId, String name, TeamType teamType, String businessNo, boolean active) {

    public static TeamResponse from(Team t) {
        return new TeamResponse(t.getId(), t.getName(), t.getTeamType(), t.getBusinessNo(), t.isActive());
    }
}
