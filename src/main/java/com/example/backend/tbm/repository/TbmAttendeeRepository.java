package com.example.backend.tbm.repository;

import com.example.backend.tbm.entity.TbmAttendee;
import com.example.backend.tbm.entity.TbmAttendeeId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TbmAttendeeRepository extends JpaRepository<TbmAttendee, TbmAttendeeId> {

    List<TbmAttendee> findByTbmId(Long tbmId);

    int countByTbmId(Long tbmId);
}
