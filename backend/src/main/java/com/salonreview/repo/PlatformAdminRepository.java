package com.salonreview.repo;

import com.salonreview.domain.PlatformAdmin;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformAdminRepository extends JpaRepository<PlatformAdmin, Long> {
}
