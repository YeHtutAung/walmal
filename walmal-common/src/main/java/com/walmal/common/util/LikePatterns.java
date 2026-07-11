package com.walmal.common.util;

/**
 * Escaping for user-supplied text bound into JPQL LIKE patterns.
 * Callers' queries must declare {@code ESCAPE '\'} on the LIKE predicate.
 * Order matters: backslash must be doubled first.
 */
public final class LikePatterns {
    private LikePatterns() {}

    /**
     * @throws NullPointerException if {@code s} is null — callers must pass a non-null value
     */
    public static String escape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
