package com.lambda.fusion.ai.runtime.workspace;

import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Workspace 脚手架：WORKSPACE 型应用创建时初始化目录结构与默认文件，
 *
 * @author Zx
 */
@Slf4j
@Component
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class WorkspaceScaffolder {

    /**
     * 脚手架 workspace。已存在的文件不覆盖。
     */
    public void scaffold(Path workspace, AppEntity app) throws IOException {
        boolean changed = createDirectoriesIfAbsent(workspace);
        changed |= createDirectoriesIfAbsent(workspace.resolve("outputs"));
        changed |= writeIfAbsent(workspace.resolve("outputs").resolve(".keep"));
        if (changed) {
            log.info("workspace 脚手架完成: app={}, path={}", app.getId(), workspace);
        }
    }

    private boolean createDirectoriesIfAbsent(Path directory) throws IOException {
        boolean absent = Files.notExists(directory);
        Files.createDirectories(directory);
        return absent;
    }

    private boolean writeIfAbsent(Path file) throws IOException {
        if (!Files.exists(file)) {
            Files.writeString(file, "", StandardCharsets.UTF_8);
            return true;
        }
        return false;
    }
}
