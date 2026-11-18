package com.rentopia.common.security;

import com.rentopia.common.exception.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/** Reads the signed-in user's id (the JWT "sub" claim). */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        throw ApiException.unauthorized("UNAUTHORIZED", "Authentication is required");
    }
}
