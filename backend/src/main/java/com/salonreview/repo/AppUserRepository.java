package com.salonreview.repo;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);

    List<AppUser> findAllByOrderByUsernameAsc();

    /** Active accounts in the given roles — used to build a SOP's acknowledgment roster. */
    List<AppUser> findByRoleInAndActiveTrueOrderByUsernameAsc(Collection<Role> roles);
}
