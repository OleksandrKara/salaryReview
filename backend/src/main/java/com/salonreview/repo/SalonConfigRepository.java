package com.salonreview.repo;

import com.salonreview.domain.SalonConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalonConfigRepository extends JpaRepository<SalonConfig, Integer> {
}
