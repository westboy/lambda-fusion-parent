package com.lambda.fusion.ai.storage;

import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;

/** LOCAL AI 文件存储。 */
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class LocalAiFileStorage implements AiFileStorage {

    public static final String TYPE = "LOCAL";

    private final AiProperties aiProperties;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String store(Path source, String relativeName, String contentType) {
        Path target = resolveStoragePath(relativeName);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return relativeName;
        } catch (IOException e) {
            throw new AiBusinessException(AiErrorCode.DOCUMENT_STORAGE_ERROR, e);
        }
    }

    @Override
    public void delete(String storagePath) {
        try {
            Files.deleteIfExists(resolveStoragePath(storagePath));
        } catch (IOException e) {
            throw new AiBusinessException(AiErrorCode.DOCUMENT_STORAGE_ERROR, e);
        }
    }

    @Override
    public AiFileResource open(String storagePath) {
        Path path = resolveStoragePath(storagePath);
        try {
            return new AiFileResource(Files.newInputStream(path), Files.size(path));
        } catch (IOException e) {
            throw new AiBusinessException(AiErrorCode.DOCUMENT_STORAGE_ERROR, e);
        }
    }

    private Path resolveStoragePath(String relativePath) {
        Path root = resolveRoot().toAbsolutePath().normalize();
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            throw new AiBusinessException(AiErrorCode.INVALID_PARAMETER, "非法存储路径");
        }
        return target;
    }

    private Path resolveRoot() {
        String configured =
                aiProperties.getRag().getDocumentStorage().getLocal().getRoot();
        if (StringUtils.isNotBlank(configured)) {
            return Path.of(configured);
        }
        String workspaceRoot = aiProperties.getWorkspace().getRoot();
        if (StringUtils.isNotBlank(workspaceRoot)) {
            return Path.of(workspaceRoot, "knowledge-files");
        }
        return Paths.get(System.getProperty("user.home"), ".agentscope", "fusion", "knowledge-files");
    }
}
