package com.walmal.auth.application.impl;

import com.walmal.auth.application.StaffNotificationQueryService;
import com.walmal.auth.domain.Role;
import com.walmal.auth.infrastructure.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class StaffNotificationQueryServiceImpl implements StaffNotificationQueryService {

    private final UserRepository userRepository;

    public StaffNotificationQueryServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public List<UUID> findActiveUserIdsByRole(String role) {
        Role domainRole = Role.valueOf(role.toUpperCase());
        return userRepository.findByRoleAndIsActiveTrue(domainRole)
                .stream()
                .map(user -> user.getId())
                .toList();
    }
}
