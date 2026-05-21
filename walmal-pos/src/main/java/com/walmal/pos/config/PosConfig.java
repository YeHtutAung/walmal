package com.walmal.pos.config;

import org.springframework.context.annotation.Configuration;

/**
 * Top-level Spring configuration for the walmal-pos module.
 *
 * <p>Module constants:
 * <ul>
 *   <li>{@link #MAX_SYNC_BATCH_SIZE} — maximum number of offline sale payloads per sync request.
 *       Configurable via {@code pos.sync.max-batch-size} (default 100).
 *       Documents the HTTP timeout risk for large batches (ADR-6 Risk 2).</li>
 * </ul>
 * </p>
 *
 * <p>{@code @EnableMethodSecurity} is already declared in {@code AuthSecurityConfig}
 * (walmal-auth) and must NOT be duplicated here.</p>
 */
@Configuration
public class PosConfig {

    /**
     * Maximum number of offline sale payloads allowed in a single sync batch.
     * Enforced by {@link com.walmal.pos.application.impl.PosSyncServiceImpl}.
     * Configurable via {@code pos.sync.max-batch-size}.
     */
    public static final int MAX_SYNC_BATCH_SIZE = 100;
}
