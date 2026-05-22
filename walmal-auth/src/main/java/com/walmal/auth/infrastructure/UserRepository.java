package com.walmal.auth.infrastructure;

import com.walmal.auth.domain.Role;
import com.walmal.auth.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository bean owned by walmal-auth. No other module may inject this interface.
 * Cross-module access to user data is provided via {@link com.walmal.auth.application.UserLookupService}.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    List<User> findByRoleAndIsActiveTrue(Role role);

    Page<User> findByRole(Role role, Pageable pageable);

    Page<User> findByIsActive(boolean isActive, Pageable pageable);

    Page<User> findByRoleAndIsActive(Role role, boolean isActive, Pageable pageable);
}
