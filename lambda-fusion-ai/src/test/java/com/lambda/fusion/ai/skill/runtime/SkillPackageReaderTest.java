package com.lambda.fusion.ai.skill.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class SkillPackageReaderTest {

    @Test
    void readsSingleSkillAndPreservesNestedResourcePaths() throws Exception {
        byte[] archive = zip(Map.of(
                "pptx/SKILL.md",
                skillMarkdown("pptx", "处理演示文稿", "MIT"),
                "pptx/scripts/office/validate.py",
                "print('ok')"));

        SkillPackageReader.ParsedPackage parsed =
                SkillPackageReader.read(new ByteArrayInputStream(archive), archive.length, "marketplace");

        assertThat(parsed.skills()).hasSize(1);
        SkillPackageReader.ParsedSkill item = parsed.skills().getFirst();
        assertThat(item.rootPath()).isEqualTo("pptx");
        assertThat(item.skill().getName()).isEqualTo("pptx");
        assertThat(item.skill().getDescription()).isEqualTo("处理演示文稿");
        assertThat(item.skill().getMetadataValue("license")).isEqualTo("MIT");
        assertThat(item.skill().getResource("scripts/office/validate.py")).isEqualTo("print('ok')");
        assertThat(item.resourcePaths()).containsExactly("scripts/office/validate.py");
    }

    @Test
    void readsCollectionBelowArbitraryWrapperDirectories() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("skills-main/README.md", "repository readme");
        files.put("skills-main/skills/pdf/SKILL.md", skillMarkdown("pdf", "处理 PDF", null));
        files.put("skills-main/skills/pptx/SKILL.md", skillMarkdown("pptx", "处理 PPTX", null));
        files.put("skills-main/skills/pptx/scripts/render.py", "print('render')");
        byte[] archive = zip(files);

        SkillPackageReader.ParsedPackage parsed =
                SkillPackageReader.read(new ByteArrayInputStream(archive), archive.length, "marketplace");

        assertThat(parsed.skills())
                .extracting(SkillPackageReader.ParsedSkill::rootPath)
                .containsExactly("skills-main/skills/pdf", "skills-main/skills/pptx");
        assertThat(parsed.skills()).extracting(item -> item.skill().getName()).containsExactly("pdf", "pptx");
        assertThat(parsed.skills().get(1).resourcePaths()).containsExactly("scripts/render.py");
    }

    @Test
    void encodesBinaryResourcesUsingAgentScopeConvention() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("pptx/SKILL.md", skillMarkdown("pptx", "处理 PPTX", null).getBytes(StandardCharsets.UTF_8));
        files.put("pptx/assets/font.ttf", new byte[] {0, 1, (byte) 0xff, 2});
        byte[] archive = zipBytes(files);

        SkillPackageReader.ParsedSkill parsed = SkillPackageReader.read(
                        new ByteArrayInputStream(archive), archive.length, "marketplace")
                .skills()
                .getFirst();

        String resource = parsed.skill().getResource("assets/font.ttf");
        assertThat(resource).startsWith("base64:");
        assertThat(Base64.getDecoder().decode(resource.substring("base64:".length())))
                .containsExactly(0, 1, (byte) 0xff, 2);
        assertThat(parsed.binaryResourceCount()).isEqualTo(1);
    }

    @Test
    void rejectsLowercaseSkillEntryName() throws Exception {
        byte[] archive = zip(Map.of("pptx/skill.md", skillMarkdown("pptx", "处理 PPTX", null)));

        assertThatThrownBy(
                        () -> SkillPackageReader.read(new ByteArrayInputStream(archive), archive.length, "marketplace"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKILL.md");
    }

    @Test
    void rejectsNestedSkillRoots() throws Exception {
        byte[] archive = zip(Map.of(
                "skills/SKILL.md", skillMarkdown("parent", "父技能", null),
                "skills/pptx/SKILL.md", skillMarkdown("pptx", "子技能", null)));

        assertThatThrownBy(
                        () -> SkillPackageReader.read(new ByteArrayInputStream(archive), archive.length, "marketplace"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能嵌套");
    }

    @Test
    void rejectsDuplicateSkillNamesIgnoringCase() throws Exception {
        byte[] archive = zip(Map.of(
                "skills/one/SKILL.md", skillMarkdown("pptx", "技能一", null),
                "skills/two/SKILL.md", skillMarkdown("PPTX", "技能二", null)));

        assertThatThrownBy(
                        () -> SkillPackageReader.read(new ByteArrayInputStream(archive), archive.length, "marketplace"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复技能名称");
    }

    @Test
    void rejectsParentDirectorySegments() throws Exception {
        byte[] archive =
                zip(Map.of("pptx/SKILL.md", skillMarkdown("pptx", "处理 PPTX", null), "pptx/../secret.txt", "secret"));

        assertThatThrownBy(
                        () -> SkillPackageReader.read(new ByteArrayInputStream(archive), archive.length, "marketplace"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能包含 ..");
    }

    private static String skillMarkdown(String name, String description, String license) {
        String licenseLine = license == null ? "" : "license: " + license + "\n";
        return "---\nname: " + name + "\ndescription: " + description + "\n" + licenseLine + "---\n# 使用说明\n";
    }

    private static byte[] zip(Map<String, String> files) throws Exception {
        Map<String, byte[]> encoded = new LinkedHashMap<>();
        files.forEach((path, content) -> encoded.put(path, content.getBytes(StandardCharsets.UTF_8)));
        return zipBytes(encoded);
    }

    private static byte[] zipBytes(Map<String, byte[]> files) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
            zip.finish();
            return output.toByteArray();
        }
    }
}
