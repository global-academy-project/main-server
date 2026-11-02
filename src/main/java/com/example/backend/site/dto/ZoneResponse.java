package com.example.backend.site.dto;

import com.example.backend.site.entity.WorkZone;

public record ZoneResponse(Long zoneId, Long siteId, String code, String name, String zoneType, boolean confined,
                           String hazardNote, boolean active) {

    public static ZoneResponse from(WorkZone z) {
        return new ZoneResponse(z.getId(), z.getSiteId(), z.getCode(), z.getName(), z.getZoneType(), z.isConfined(),
                z.getHazardNote(), z.isActive());
    }
}
