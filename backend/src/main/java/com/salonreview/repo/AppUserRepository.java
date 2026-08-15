package com.salonreview.repo;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /** Deliberately NOT business-scoped: Spring Security's {@code UserDetailsService.
     * loadUserByUsername(String)} contract only ever receives a username — the business isn't known
     * yet at login, it's resolved *from* this user's business_membership row afterward (design.md
     * D3). The still-global {@code app_user_username_key} constraint (kept by V87, see its migration
     * comment) is what keeps this correct until a second business actually needs a colliding
     * username, at which point login itself needs a business-picker step, not just a scoped query. */
    Optional<AppUser> findByUsername(String username);

    boolean existsByBusinessIdAndUsername(Long businessId, String username);

    List<AppUser> findAllByBusinessIdOrderByUsernameAsc(Long businessId);

    /** Active accounts in the given roles — used to build a SOP's acknowledgment roster. */
    List<AppUser> findByBusinessIdAndRoleInAndActiveTrueOrderByUsernameAsc(Long businessId, Collection<Role> roles);
}
