package com.salonreview.repo;

import com.salonreview.domain.UserPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface UserPermissionRepository extends JpaRepository<UserPermission, Long> {

    List<UserPermission> findByBusinessId(Long businessId);

    List<UserPermission> findByBusinessIdAndUserId(Long businessId, Long userId);

    boolean existsByBusinessIdAndUserIdAndPermission(Long businessId, Long userId, String permission);

    @Transactional
    void deleteByBusinessIdAndUserIdAndPermission(Long businessId, Long userId, String permission);
}
