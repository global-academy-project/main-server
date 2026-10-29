package com.example.backend.site.repository;

import com.example.backend.site.entity.Equipment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EquipmentRepository extends JpaRepository<Equipment, Long> {

    List<Equipment> findBySiteIdAndActiveTrueOrderByCodeAsc(Long siteId);

    List<Equipment> findBySiteIdAndEquipmentTypeAndActiveTrueOrderByCodeAsc(Long siteId, String equipmentType);

    Optional<Equipment> findByIdAndSiteId(Long id, Long siteId);
}
