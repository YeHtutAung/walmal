package com.walmal.app;

import com.walmal.auth.config.AuthSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard: every HTTP method used by any @RestController must be listed in
 * AuthSecurityConfig.CORS_ALLOWED_METHODS. A method missing from CORS fails
 * browser preflight and surfaces as an opaque "CORS error" in clients
 * (this bit us with PATCH /orders/{id}/status — UAT 2026-07-10).
 */
class CorsMethodCoverageTest {

    @Test
    @DisplayName("CORS allowed methods cover every HTTP method used by controllers")
    void corsAllowsEveryControllerMethod() throws Exception {
        Set<String> used = new TreeSet<>();
        // Note: getDeclaredMethods() below misses mappings inherited from base
        // classes. No walmal controller uses inheritance today; revisit if one does.
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        for (var bd : scanner.findCandidateComponents("com.walmal")) {
            Class<?> clazz = Class.forName(bd.getBeanClassName());
            for (Method m : clazz.getDeclaredMethods()) {
                RequestMapping mapping =
                        AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (mapping == null) continue;
                for (RequestMethod rm : mapping.method()) {
                    used.add(rm.name());
                }
            }
        }

        assertThat(used).as("no controllers found — scan is broken").isNotEmpty();
        assertThat(AuthSecurityConfig.CORS_ALLOWED_METHODS)
                .as("Controllers use HTTP methods missing from CORS config. "
                        + "Add them to AuthSecurityConfig.CORS_ALLOWED_METHODS.")
                .containsAll(used);
    }
}
