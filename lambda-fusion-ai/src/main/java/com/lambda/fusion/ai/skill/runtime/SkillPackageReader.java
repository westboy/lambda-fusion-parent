package com.lambda.fusion.ai.skill.runtime;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.SkillUtil;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** 读取单技能包或包含多个技能目录的集合包，并交给 AgentScope 解析每个技能。 */
public final class SkillPackageReader {

    public static final long MAX_ARCHIVE_SIZE_BYTES = 20L * 1024 * 1024;

    private static final long MAX_UNCOMPRESSED_SIZE_BYTES = 50L * 1024 * 1024;
    private static final int MAX_ENTRY_COUNT = 1000;
    private static final int MAX_ENTRY_SIZE_BYTES = 5 * 1024 * 1024;
    private static final int BUFFER_SIZE = 8192;
    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final String REPACK_ROOT = "skill";

    private SkillPackageReader() {}

    public static ParsedPackage read(InputStream input, long compressedSize, String source) throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("技能包内容不能为空");
        }
        if (compressedSize <= 0) {
            throw new IllegalArgumentException("技能包内容不能为空");
        }
        if (compressedSize > MAX_ARCHIVE_SIZE_BYTES) {
            throw new IllegalArgumentException("技能包不能超过 20 MB");
        }

        Map<String, ArchiveFile> files = readArchive(input);
        List<String> skillFiles = files.keySet().stream()
                .filter(SkillPackageReader::isSkillFile)
                .sorted()
                .toList();
        if (skillFiles.isEmpty()) {
            boolean wrongCase =
                    files.keySet().stream().anyMatch(path -> fileName(path).equalsIgnoreCase(SKILL_FILE_NAME));
            if (wrongCase) {
                throw new IllegalArgumentException("技能入口文件必须严格命名为 SKILL.md");
            }
            throw new IllegalArgumentException("压缩包中未找到 SKILL.md");
        }

        List<String> roots =
                skillFiles.stream().map(SkillPackageReader::parentPath).toList();
        validateNoNestedSkillRoots(roots);

        List<ParsedSkill> skills = new ArrayList<>(roots.size());
        Set<String> skillNames = new HashSet<>();
        for (String root : roots) {
            ParsedSkill parsed = parseSkill(root, files, source);
            String foldedName = parsed.skill().getName().toLowerCase(Locale.ROOT);
            if (!skillNames.add(foldedName)) {
                throw new IllegalArgumentException(
                        "技能包内存在重复技能名称: " + parsed.skill().getName());
            }
            skills.add(parsed);
        }
        skills.sort(Comparator.comparing(ParsedSkill::rootPath));
        return new ParsedPackage(List.copyOf(skills));
    }

    private static Map<String, ArchiveFile> readArchive(InputStream input) throws IOException {
        Map<String, ArchiveFile> files = new LinkedHashMap<>();
        Set<String> foldedPaths = new HashSet<>();
        long totalSize = 0;
        int entryCount = 0;
        try (ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            while (true) {
                ZipEntry entry;
                try {
                    entry = zip.getNextEntry();
                } catch (java.lang.IllegalArgumentException invalidZipName) {
                    throw new IllegalArgumentException("技能包文件名必须使用 UTF-8 编码", invalidZipName);
                }
                if (entry == null) {
                    break;
                }
                entryCount++;
                if (entryCount > MAX_ENTRY_COUNT) {
                    throw new IllegalArgumentException("技能包文件数量不能超过 " + MAX_ENTRY_COUNT);
                }
                String path = normalizePath(entry.getName());
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                if (path.isEmpty()) {
                    throw new IllegalArgumentException("技能包包含空文件路径");
                }
                String foldedPath = path.toLowerCase(Locale.ROOT);
                if (!foldedPaths.add(foldedPath)) {
                    throw new IllegalArgumentException("技能包包含重复文件路径: " + path);
                }
                byte[] content = readEntry(zip, entry, totalSize);
                totalSize += content.length;
                if (totalSize > MAX_UNCOMPRESSED_SIZE_BYTES) {
                    throw new IllegalArgumentException("技能包解压后不能超过 50 MB");
                }
                boolean encodedResource = requiresBase64Encoding(content);
                if (isSkillFile(path) && encodedResource) {
                    throw new IllegalArgumentException("SKILL.md 必须是 UTF-8 文本文件: " + path);
                }
                files.put(path, new ArchiveFile(path, content, encodedResource));
                zip.closeEntry();
            }
        }
        if (files.isEmpty()) {
            throw new IllegalArgumentException("技能包不包含任何文件");
        }
        return files;
    }

    private static byte[] readEntry(ZipInputStream zip, ZipEntry entry, long currentTotal) throws IOException {
        long declaredSize = entry.getSize();
        if (declaredSize > MAX_ENTRY_SIZE_BYTES) {
            throw new IllegalArgumentException("单个技能资源不能超过 5 MB: " + entry.getName());
        }
        int initialSize = declaredSize > 0 ? (int) Math.min(declaredSize, BUFFER_SIZE) : BUFFER_SIZE;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(initialSize)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            int entrySize = 0;
            while ((read = zip.read(buffer)) != -1) {
                entrySize += read;
                if (entrySize > MAX_ENTRY_SIZE_BYTES) {
                    throw new IllegalArgumentException("单个技能资源不能超过 5 MB: " + entry.getName());
                }
                if (currentTotal + entrySize > MAX_UNCOMPRESSED_SIZE_BYTES) {
                    throw new IllegalArgumentException("技能包解压后不能超过 50 MB");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static boolean requiresBase64Encoding(byte[] content) {
        String text;
        try {
            text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException invalidText) {
            return true;
        }
        for (byte value : content) {
            if (value == 0) {
                return true;
            }
        }
        return text.startsWith("base64:");
    }

    private static ParsedSkill parseSkill(String root, Map<String, ArchiveFile> files, String source)
            throws IOException {
        String rootPrefix = root.isEmpty() ? "" : root + "/";
        String skillPath = rootPrefix + SKILL_FILE_NAME;
        Map<String, ArchiveFile> skillFiles = new LinkedHashMap<>();
        long uncompressedSize = 0;
        int binaryResourceCount = 0;
        for (ArchiveFile file : files.values()) {
            if (file.path().equals(skillPath) || file.path().startsWith(rootPrefix)) {
                String relativePath = root.isEmpty() ? file.path() : file.path().substring(rootPrefix.length());
                skillFiles.put(relativePath, file);
                uncompressedSize += file.content().length;
                if (!relativePath.equals(SKILL_FILE_NAME) && file.encodedResource()) {
                    binaryResourceCount++;
                }
            }
        }

        byte[] normalizedZip = createSingleSkillZip(skillFiles);
        AgentSkill skill;
        try {
            skill = SkillUtil.createFromZip(normalizedZip, source, StandardCharsets.UTF_8);
        } catch (RuntimeException invalidSkill) {
            String displayRoot = root.isEmpty() ? "." : root;
            throw new IllegalArgumentException(
                    "技能目录 " + displayRoot + " 解析失败: " + invalidSkill.getMessage(), invalidSkill);
        }
        List<String> resourcePaths = skill.getResourcePaths().stream().sorted().toList();
        return new ParsedSkill(
                root.isEmpty() ? "." : root, skill, resourcePaths, uncompressedSize, binaryResourceCount);
    }

    private static byte[] createSingleSkillZip(Map<String, ArchiveFile> files) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, ArchiveFile> file : files.entrySet()) {
                ZipEntry entry = new ZipEntry(REPACK_ROOT + "/" + file.getKey());
                zip.putNextEntry(entry);
                zip.write(contentForAgentScope(file.getValue()));
                zip.closeEntry();
            }
            zip.finish();
            return output.toByteArray();
        }
    }

    private static byte[] contentForAgentScope(ArchiveFile file) {
        if (!file.encodedResource()) {
            return file.content();
        }
        String encoded = "base64:" + Base64.getEncoder().encodeToString(file.content());
        return encoded.getBytes(StandardCharsets.UTF_8);
    }

    private static void validateNoNestedSkillRoots(List<String> roots) {
        Set<String> uniqueRoots = new LinkedHashSet<>(roots);
        for (String parent : uniqueRoots) {
            for (String child : uniqueRoots) {
                if (parent.equals(child)) {
                    continue;
                }
                if (parent.isEmpty() || child.startsWith(parent + "/")) {
                    String displayParent = parent.isEmpty() ? "." : parent;
                    throw new IllegalArgumentException("技能目录不能嵌套: " + displayParent + " 与 " + child);
                }
            }
        }
    }

    private static String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("技能包包含空文件路径");
        }
        String path = rawPath.replace('\\', '/');
        if (path.startsWith("/") || path.matches("^[A-Za-z]:/.*")) {
            throw new IllegalArgumentException("技能包文件必须使用相对路径: " + rawPath);
        }
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("技能包文件路径不能包含 ..: " + rawPath);
            }
            if (segment.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("技能包文件路径包含非法字符");
            }
            segments.add(segment);
        }
        return String.join("/", segments);
    }

    private static boolean isSkillFile(String path) {
        return fileName(path).equals(SKILL_FILE_NAME);
    }

    private static String fileName(String path) {
        int separator = path.lastIndexOf('/');
        return separator < 0 ? path : path.substring(separator + 1);
    }

    private static String parentPath(String path) {
        int separator = path.lastIndexOf('/');
        return separator < 0 ? "" : path.substring(0, separator);
    }

    private record ArchiveFile(String path, byte[] content, boolean encodedResource) {}

    public record ParsedPackage(List<ParsedSkill> skills) {}

    public record ParsedSkill(
            String rootPath,
            AgentSkill skill,
            List<String> resourcePaths,
            long uncompressedSize,
            int binaryResourceCount) {}
}
