package com.example.backend.site.repository;

import com.example.backend.site.entity.Site;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SiteRepository extends JpaRepository<Site, Long> {

    List<Site> findByActiveTrueOrderByNameAsc();
}
