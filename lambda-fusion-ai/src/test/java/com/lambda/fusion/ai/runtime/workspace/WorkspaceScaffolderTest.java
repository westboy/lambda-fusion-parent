package com.lambda.fusion.ai.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceScaffolderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldScaffoldOutputsWithPlaceholderForProjection() throws Exception {
        Path workspace = tempDir.resolve("ws");
        new WorkspaceScaffolder().scaffold(workspace, app());

        assertThat(workspace.resolve("outputs")).isDirectory();
        assertThat(workspace.resolve("outputs").resolve(".keep")).isEmptyFile();
    }

    @Test
    void shouldNotOverwriteExistingOutputFiles() throws Exception {
        Path workspace = tempDir.resolve("ws");
        Files.createDirectories(workspace.resolve("outputs"));
        Path existing = workspace.resolve("outputs").resolve("report.md");
        Files.writeString(existing, "生成结果");

        new WorkspaceScaffolder().scaffold(workspace, app());

        assertThat(existing).hasContent("生成结果");
        assertThat(workspace.resolve("outputs").resolve(".keep")).isEmptyFile();
    }

    private static AppEntity app() {
        AppEntity app = new AppEntity();
        app.setId("app-1");
        app.setName("测试应用");
        return app;
    }
}
