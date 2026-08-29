package com.lambda.fusion.ai.skill.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.skill.model.SkillPackageImportResult;
import com.lambda.fusion.ai.skill.model.SkillPackageInspection;
import com.lambda.fusion.ai.skill.model.SkillPackageType;
import com.lambda.fusion.ai.skill.model.SkillResourceView;
import com.lambda.fusion.ai.skill.model.UpdateSkill;
import com.lambda.fusion.ai.skill.runtime.SkillRepositoryResolver;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class SkillPackageServiceTest {

    @Test
    void inspectionMarksExistingSkills() throws Exception {
        AgentSkillRepository repository = writableRepository();
        when(repository.skillExists("pptx")).thenReturn(true);
        SkillServiceImpl service = service(repository);

        SkillPackageInspection inspection = service.inspectPackage(packageFile());

        assertThat(inspection.getPackageType()).isEqualTo(SkillPackageType.COLLECTION);
        assertThat(inspection.getSkills()).hasSize(2);
        assertThat(inspection.getSkills())
                .filteredOn(item -> item.getName().equals("pptx"))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.isExisting()).isTrue();
                    assertThat(item.getRootPath()).isEqualTo("skills/pptx");
                    assertThat(item.getResourcePaths()).containsExactly("scripts/render.py");
                });
    }

    @Test
    void importsOnlySelectedSkills() throws Exception {
        AgentSkillRepository repository = writableRepository();
        when(repository.save(anyList(), eq(false))).thenReturn(true);
        SkillServiceImpl service = service(repository);

        SkillPackageImportResult result = service.importPackage(packageFile(), List.of("pptx"), false);

        assertThat(result.getImportedCount()).isEqualTo(1);
        assertThat(result.getSkillNames()).containsExactly("pptx");
        verify(repository)
                .save(
                        argThat(skills -> skills.size() == 1
                                && skills.getFirst().getName().equals("pptx")
                                && skills.getFirst().getResourcePaths().contains("scripts/render.py")),
                        eq(false));
    }

    @Test
    void refusesExistingSkillWithoutOverwrite() throws Exception {
        AgentSkillRepository repository = writableRepository();
        when(repository.skillExists("pptx")).thenReturn(true);
        SkillServiceImpl service = service(repository);

        assertThatThrownBy(() -> service.importPackage(packageFile(), List.of("pptx"), false))
                .isInstanceOfSatisfying(AiBusinessException.class, error -> assertThat(error.getCode())
                        .isEqualTo(AiErrorCode.SKILL_NAME_EXISTS.getCode()));
    }

    @Test
    void rejectsNonZipFileName() throws Exception {
        SkillServiceImpl service = service(writableRepository());
        MockMultipartFile zip = packageFile();
        MockMultipartFile renamed = new MockMultipartFile("file", "skills.skill", "application/zip", zip.getBytes());

        assertThatThrownBy(() -> service.inspectPackage(renamed))
                .isInstanceOfSatisfying(AiBusinessException.class, error -> assertThat(error.getCode())
                        .isEqualTo(AiErrorCode.SKILL_PACKAGE_INVALID.getCode()));
    }

    @Test
    void updatePreservesImportedMetadata() {
        AgentSkillRepository repository = writableRepository();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", "pptx");
        metadata.put("description", "旧描述");
        metadata.put("license", "MIT");
        AgentSkill existing = new AgentSkill(metadata, "instructions", Map.of(), "marketplace");
        when(repository.getSkill("pptx")).thenReturn(existing);
        when(repository.save(anyList(), eq(true))).thenReturn(true);
        SkillServiceImpl service = service(repository);
        UpdateSkill update = new UpdateSkill();
        update.setDescription("新描述");

        service.update("pptx", update);

        verify(repository)
                .save(
                        argThat(skills -> skills.size() == 1
                                && skills.getFirst().getDescription().equals("新描述")
                                && skills.getFirst().getMetadataValue("license").equals("MIT")),
                        eq(true));
    }

    @Test
    void readsSingleResourceOnDemand() {
        AgentSkillRepository repository = writableRepository();
        AgentSkill existing = new AgentSkill("pptx", "desc", "instructions", Map.of("scripts/render.py", "render"));
        when(repository.getSkill("pptx")).thenReturn(existing);
        SkillServiceImpl service = service(repository);

        SkillResourceView resource = service.getResource("pptx", "scripts/render.py");

        assertThat(resource.getPath()).isEqualTo("scripts/render.py");
        assertThat(resource.getContent()).isEqualTo("render");
        assertThat(resource.getContentLength()).isEqualTo(6);
        assertThat(resource.isBinary()).isFalse();
    }

    @Test
    void updateAppliesResourceChangesWithoutDroppingUntouchedFiles() {
        AgentSkillRepository repository = writableRepository();
        AgentSkill existing = new AgentSkill(
                "pptx",
                "desc",
                "instructions",
                Map.of("keep.txt", "keep", "change.txt", "old", "delete.txt", "remove"));
        when(repository.getSkill("pptx")).thenReturn(existing);
        when(repository.save(anyList(), eq(true))).thenReturn(true);
        SkillServiceImpl service = service(repository);
        UpdateSkill update = new UpdateSkill();
        update.setResourceUpserts(Map.of("change.txt", "new", "add.txt", "added"));
        update.setResourceDeletes(List.of("delete.txt"));

        service.update("pptx", update);

        verify(repository)
                .save(
                        argThat(skills -> skills.getFirst()
                                .getResources()
                                .equals(Map.of("keep.txt", "keep", "change.txt", "new", "add.txt", "added"))),
                        eq(true));
    }

    private static SkillServiceImpl service(AgentSkillRepository repository) {
        SkillRepositoryResolver resolver = mock(SkillRepositoryResolver.class);
        when(resolver.resolve()).thenReturn(repository);
        return new SkillServiceImpl(resolver);
    }

    private static AgentSkillRepository writableRepository() {
        AgentSkillRepository repository = mock(AgentSkillRepository.class);
        when(repository.isWriteable()).thenReturn(true);
        return repository;
    }

    private static MockMultipartFile packageFile() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("skills/pdf/SKILL.md", skillMarkdown("pdf", "处理 PDF"));
        files.put("skills/pptx/SKILL.md", skillMarkdown("pptx", "处理 PPTX"));
        files.put("skills/pptx/scripts/render.py", "print('render')");
        return new MockMultipartFile("file", "skills.zip", "application/zip", zip(files));
    }

    private static String skillMarkdown(String name, String description) {
        return "---\nname: " + name + "\ndescription: " + description + "\n---\n# 使用说明\n";
    }

    private static byte[] zip(Map<String, String> files) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
            return output.toByteArray();
        }
    }
}
