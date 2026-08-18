package com.salonreview.repo;

import com.salonreview.domain.PlatformAdmin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface PlatformAdminRepository extends JpaRepository<PlatformAdmin, Long> {

    /** Safe no-op when the user isn't a platform admin — unlike the inherited
     * {@code deleteById}, a name-derived delete query never throws on zero matches. */
    @Transactional
    void deleteByUserId(Long userId);
}
