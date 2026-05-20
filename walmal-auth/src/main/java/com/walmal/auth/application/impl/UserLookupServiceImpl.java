package com.walmal.auth.application.impl;

import com.walmal.auth.application.UserLookupService;
import com.walmal.auth.infrastructure.UserRepository;
import com.walmal.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Resolves user identity queries for downstream modules (Order, POS).
 * Exposes only the minimum data contract required by consumers — ISP compliance.
 */
@Service
public class UserLookupServiceImpl implements UserLookupService {

    private final UserRepository userRepository;

    public UserLookupServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public String findUsernameById(UUID userId) {
        return userRepository.findById(userId)
                .map(u -> u.getUsername())
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }

    @Override
    @Transactional(readOnly = true)
    public String findRoleById(UUID userId) {
        return userRepository.findById(userId)
                .map(u -> u.getRole().name())
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isUserActive(UUID userId) {
        return userRepository.findById(userId)
                .map(u -> u.isActive())
                .orElse(false);
    }
}
