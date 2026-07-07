package io.github.legacygraph.security;

import io.github.legacygraph.config.SecurityConfig;
import io.github.legacygraph.controller.PluginController;
import io.github.legacygraph.controller.SelfAnalysisController;
import io.github.legacygraph.controller.SystemOverviewIngestController;
import io.github.legacygraph.dto.plugin.ExternalPluginDescriptor;
import io.github.legacygraph.dto.systemoverview.SystemOverviewIngestRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HighRiskEndpointSecurityTest {

    @Test
    void methodSecurityIsEnabledForPreAuthorizeGuards() {
        assertNotNull(SecurityConfig.class.getAnnotation(EnableMethodSecurity.class));
    }

    @Test
    void highRiskWriteEndpointsRequireAdminRole() throws NoSuchMethodException {
        assertAdminOnly(SystemOverviewIngestController.class, "ingest", SystemOverviewIngestRequest.class);
        assertAdminOnly(SystemOverviewIngestController.class, "ingestBuiltins", String.class, String.class);
        assertAdminOnly(PluginController.class, "register", ExternalPluginDescriptor.class);
        assertAdminOnly(SelfAnalysisController.class, "bootstrap", String.class, String.class, String.class);
    }

    private static void assertAdminOnly(Class<?> type, String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = type.getMethod(methodName, parameterTypes);
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertNotNull(preAuthorize, type.getSimpleName() + "." + methodName + " must declare @PreAuthorize");
        assertTrue(preAuthorize.value().contains("ADMIN"),
                type.getSimpleName() + "." + methodName + " must require ADMIN");
    }
}
