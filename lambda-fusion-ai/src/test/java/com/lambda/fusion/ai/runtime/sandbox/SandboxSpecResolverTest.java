package com.lambda.fusion.ai.runtime.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.AiConstants.SandboxBackend;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SandboxSpecResolverTest {

    @Test
    void hostBackendHasNoSandboxSpec() {
        AppEntity app = app("HOST");
        assertThat(new SandboxSpecResolver(List.of()).resolve(app, Path.of("workspace")))
                .isEmpty();
    }

    @Test
    void explicitSandboxWithoutExtensionFailsClosed() {
        AppEntity app = app("DOCKER");
        assertThatThrownBy(() -> new SandboxSpecResolver(List.of()).resolve(app, Path.of("workspace")))
                .isInstanceOfSatisfying(AiBusinessException.class, error -> assertThat(error.getCode())
                        .isEqualTo(AiErrorCode.CONFIGURATION_ERROR.getCode()));
    }

    @Test
    void explicitSandboxCreationFailureDoesNotFallbackToHost() {
        SandboxBackendProvider provider = mock(SandboxBackendProvider.class);
        when(provider.backend()).thenReturn(SandboxBackend.DOCKER);
        AppEntity app = app("DOCKER");
        when(provider.create(app, Path.of("workspace"))).thenThrow(new IllegalStateException("docker unavailable"));

        assertThatThrownBy(() -> new SandboxSpecResolver(List.of(provider)).resolve(app, Path.of("workspace")))
                .isInstanceOfSatisfying(AiBusinessException.class, error -> assertThat(error.getCode())
                        .isEqualTo(AiErrorCode.CONFIGURATION_ERROR.getCode()));
    }

    private static AppEntity app(String backend) {
        AppEntity app = new AppEntity();
        app.setSandboxBackend(backend);
        return app;
    }
}
