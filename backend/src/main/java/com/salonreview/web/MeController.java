package com.salonreview.web;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Returns the authenticated user — used by the frontend to validate a login. */
@RestController
public class MeController {

    @GetMapping("/api/me")
    public Map<String, Object> me(Authentication auth) {
        return Map.of("username", auth.getName(), "roles", auth.getAuthorities());
    }
}
