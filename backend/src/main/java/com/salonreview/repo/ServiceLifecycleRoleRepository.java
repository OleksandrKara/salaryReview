package com.salonreview.repo;

import com.salonreview.domain.ServiceLifecycleRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ServiceLifecycleRoleRepository extends JpaRepository<ServiceLifecycleRole, Long> {
    List<ServiceLifecycleRole> findAllByBusinessIdAndRole(Long businessId, String role);
}
