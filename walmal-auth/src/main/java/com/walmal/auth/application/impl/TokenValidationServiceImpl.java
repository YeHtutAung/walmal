package com.walmal.auth.application.impl;

import com.walmal.auth.application.TokenValidationService;
import com.walmal.auth.infrastructure.JwtTokenProvider;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Delegates all JWT operations to the {@link JwtTokenProvider} interface.
 * Business logic has zero direct JJWT dependency — only this interface is referenced.
 */
@Service
public class TokenValidationServiceImpl implements TokenValidationService {

    private final JwtTokenProvider jwtTokenProvider;

    public TokenValidationServiceImpl(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public boolean isValid(String token) {
        return jwtTokenProvider.validateToken(token);
    }

    @Override
    public UUID extractUserId(String token) {
        return jwtTokenProvider.extractUserId(token);
    }

    @Override
    public String extractRole(String token) {
        return jwtTokenProvider.extractRole(token);
    }

    @Override
    public String extractUsername(String token) {
        return jwtTokenProvider.extractUsername(token);
    }
}
