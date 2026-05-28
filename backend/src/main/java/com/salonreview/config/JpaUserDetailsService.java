package com.salonreview.config;

import com.salonreview.repo.AppUserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Loads {@link AppUserPrincipal}s from the {@code app_user} table for authentication. */
@Service
public class JpaUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    public JpaUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return users.findByUsername(username)
                .map(AppUserPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user: " + username));
    }
}
