package com.ltplatform.security.web;

import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return Map.of("authenticated", false);
        }
        return Map.of(
                "authenticated", true,
                "username", authentication.getName(),
                "roles", authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList()
        );
    }

    @PostMapping("/login")
    public Map<String, Object> login(Authentication authentication) {
        return me(authentication);
    }
}
