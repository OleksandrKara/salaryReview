package com.salonreview.repo;

import com.salonreview.domain.PmuProcedureRoleService;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PmuProcedureRoleServiceRepository extends JpaRepository<PmuProcedureRoleService, Long> {
    List<PmuProcedureRoleService> findAllByBusinessIdAndRole(Long businessId, PmuProcedureRoleService.Role role);
}
