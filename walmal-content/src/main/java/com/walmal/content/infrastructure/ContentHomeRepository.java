package com.walmal.content.infrastructure;

import com.walmal.content.domain.ContentHome;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for the {@code content_home} table. Keyed by lifecycle status
 * (DRAFT / PUBLISHED). Internal to the walmal-content module — never exposed
 * outside it (module boundary rule).
 */
public interface ContentHomeRepository extends JpaRepository<ContentHome, String> {}
