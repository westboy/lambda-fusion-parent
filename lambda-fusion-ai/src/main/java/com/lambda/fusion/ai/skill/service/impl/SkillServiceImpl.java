package com.lambda.fusion.ai.skill.service.impl;

import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.skill.model.CreateSkill;
import com.lambda.fusion.ai.skill.model.SkillPackageImportResult;
import com.lambda.fusion.ai.skill.model.SkillPackageInspection;
import com.lambda.fusion.ai.skill.model.SkillPackageItem;
import com.lambda.fusion.ai.skill.model.SkillPackageType;
import com.lambda.fusion.ai.skill.model.SkillView;
import com.lambda.fusion.ai.skill.model.UpdateSkill;
import com.lambda.fusion.ai.skill.runtime.SkillPackageReader;
import com.lambda.fusion.ai.skill.runtime.SkillRepositoryResolver;
import com.lambda.fusion.ai.skill.service.SkillService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 技能市场服务实现：CRUD 经 {@link SkillRepositoryResolver} 解析的 {@link AgentSkillRepository}。
 * 仓库未配置 -> {@link AiErrorCode#CONFIGURATION_ERROR}；只读仓库写操作 -> {@link AiErrorCode#OPERATION_NOT_SUPPORTED}。
 *
 * @author Jin
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class SkillServiceImpl implements SkillService {

    private static final String SOURCE = "marketplace";

    private final SkillRepositoryResolver skillRepositoryResolver;

    @Override
    public List<SkillView> list(boolean includeResources) {
        AgentSkillRepository repo = requireRepo();
        return repo.getAllSkills().stream()
                .map(skill -> includeResources ? toView(skill) : toSummary(skill))
                .toList();
    }

    @Override
    public SkillView get(String name) {
        AgentSkillRepository repo = requireRepo();
        AgentSkill skill = repo.getSkill(name);
        if (skill == null) {
            throw new AiBusinessException(AiErrorCode.SKILL_NOT_FOUND, name);
        }
        return toView(skill);
    }

    @Override
    public SkillView create(CreateSkill dto) {
        AgentSkillRepository repo = requireWritable();
        if (repo.skillExists(dto.getName())) {
            throw new AiBusinessException(AiErrorCode.SKILL_NAME_EXISTS, dto.getName());
        }
        AgentSkill skill = toAgentSkill(dto.getName(), dto.getDescription(), dto.getMarkdown(), dto.getResources());
        repo.save(List.of(skill), false);
        return toView(skill);
    }

    @Override
    public SkillPackageInspection inspectPackage(MultipartFile file) {
        AgentSkillRepository repo = requireRepo();
        SkillPackageReader.ParsedPackage parsed = readPackage(file);
        SkillPackageInspection inspection = new SkillPackageInspection();
        inspection.setFileName(file.getOriginalFilename());
        inspection.setCompressedSize(file.getSize());
        inspection.setPackageType(parsed.skills().size() == 1 ? SkillPackageType.SINGLE : SkillPackageType.COLLECTION);
        inspection.setSkills(parsed.skills().stream()
                .map(item -> toPackageItem(item, repo.skillExists(item.skill().getName())))
                .toList());
        return inspection;
    }

    @Override
    public SkillPackageImportResult importPackage(MultipartFile file, List<String> skillNames, boolean overwrite) {
        AgentSkillRepository repo = requireWritable();
        SkillPackageReader.ParsedPackage parsed = readPackage(file);
        List<AgentSkill> selected = selectSkills(parsed.skills(), skillNames);

        if (!overwrite) {
            List<String> conflicts = selected.stream()
                    .map(AgentSkill::getName)
                    .filter(repo::skillExists)
                    .toList();
            if (!conflicts.isEmpty()) {
                throw new AiBusinessException(AiErrorCode.SKILL_NAME_EXISTS, String.join(", ", conflicts));
            }
        }

        try {
            if (!repo.save(selected, overwrite)) {
                throw new AiBusinessException(AiErrorCode.SKILL_PACKAGE_IMPORT_FAILED);
            }
        } catch (AiBusinessException businessException) {
            throw businessException;
        } catch (RuntimeException importFailure) {
            log.warn("技能包导入失败: {}", importFailure.getMessage());
            throw new AiBusinessException(AiErrorCode.SKILL_PACKAGE_IMPORT_FAILED, importFailure);
        }

        SkillPackageImportResult result = new SkillPackageImportResult();
        List<String> importedNames = selected.stream().map(AgentSkill::getName).toList();
        result.setImportedCount(importedNames.size());
        result.setSkillNames(importedNames);
        return result;
    }

    @Override
    public void update(String name, UpdateSkill dto) {
        AgentSkillRepository repo = requireWritable();
        AgentSkill existing = repo.getSkill(name);
        if (existing == null) {
            throw new AiBusinessException(AiErrorCode.SKILL_NOT_FOUND, name);
        }
        AgentSkill.Builder builder = existing.toBuilder();
        if (dto.getDescription() != null) {
            builder.description(dto.getDescription());
        }
        if (dto.getMarkdown() != null) {
            builder.skillContent(dto.getMarkdown());
        }
        if (dto.getResources() != null) {
            builder.resources(dto.getResources());
        }
        repo.save(List.of(builder.build()), true);
    }

    @Override
    public void delete(String name) {
        AgentSkillRepository repo = requireWritable();
        if (!repo.skillExists(name)) {
            throw new AiBusinessException(AiErrorCode.SKILL_NOT_FOUND, name);
        }
        repo.delete(name);
    }

    private AgentSkillRepository requireRepo() {
        AgentSkillRepository repo = skillRepositoryResolver.resolve();
        if (repo == null) {
            throw new AiBusinessException(AiErrorCode.CONFIGURATION_ERROR, "技能仓库未配置");
        }
        return repo;
    }

    private AgentSkillRepository requireWritable() {
        AgentSkillRepository repo = requireRepo();
        if (!repo.isWriteable()) {
            throw new AiBusinessException(AiErrorCode.OPERATION_NOT_SUPPORTED, "技能仓库只读，不支持写操作");
        }
        return repo;
    }

    private SkillPackageReader.ParsedPackage readPackage(MultipartFile file) {
        validatePackageFile(file);
        try (InputStream input = file.getInputStream()) {
            return SkillPackageReader.read(input, file.getSize(), SOURCE);
        } catch (IllegalArgumentException invalidPackage) {
            log.debug("技能包预检失败: {}", invalidPackage.getMessage());
            throw new AiBusinessException(AiErrorCode.SKILL_PACKAGE_INVALID, invalidPackage);
        } catch (IOException readFailure) {
            log.warn("技能包读取失败: {}", readFailure.getMessage());
            throw new AiBusinessException(AiErrorCode.SKILL_PACKAGE_INVALID, readFailure);
        }
    }

    private static void validatePackageFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AiBusinessException(AiErrorCode.SKILL_PACKAGE_INVALID);
        }
        String fileName = file.getOriginalFilename();
        String normalizedName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (!normalizedName.endsWith(".zip")) {
            throw new AiBusinessException(AiErrorCode.SKILL_PACKAGE_INVALID);
        }
        if (file.getSize() > SkillPackageReader.MAX_ARCHIVE_SIZE_BYTES) {
            throw new AiBusinessException(AiErrorCode.SKILL_PACKAGE_INVALID);
        }
    }

    private static List<AgentSkill> selectSkills(
            List<SkillPackageReader.ParsedSkill> packageSkills, List<String> requestedNames) {
        if (requestedNames == null || requestedNames.isEmpty()) {
            return packageSkills.stream()
                    .map(SkillPackageReader.ParsedSkill::skill)
                    .toList();
        }
        Set<String> requested = new LinkedHashSet<>(requestedNames);
        Map<String, AgentSkill> byName = packageSkills.stream()
                .map(SkillPackageReader.ParsedSkill::skill)
                .collect(java.util.stream.Collectors.toMap(
                        AgentSkill::getName, skill -> skill, (left, right) -> left, java.util.LinkedHashMap::new));
        List<String> unknown =
                requested.stream().filter(name -> !byName.containsKey(name)).toList();
        if (!unknown.isEmpty()) {
            throw new AiBusinessException(AiErrorCode.SKILL_PACKAGE_INVALID, String.join(", ", unknown));
        }
        return requested.stream().map(byName::get).toList();
    }

    private static SkillPackageItem toPackageItem(SkillPackageReader.ParsedSkill parsed, boolean existing) {
        AgentSkill skill = parsed.skill();
        SkillPackageItem item = new SkillPackageItem();
        item.setName(skill.getName());
        item.setDescription(skill.getDescription());
        item.setRootPath(parsed.rootPath());
        Object license = skill.getMetadataValue("license");
        item.setLicense(license instanceof String text ? text : null);
        item.setResourcePaths(parsed.resourcePaths());
        item.setResourceCount(parsed.resourcePaths().size());
        item.setBinaryResourceCount(parsed.binaryResourceCount());
        item.setUncompressedSize(parsed.uncompressedSize());
        item.setExisting(existing);
        return item;
    }

    /**
     * @param description 调用方需保证非空
     */
    static AgentSkill toAgentSkill(String name, String description, String markdown, Map<String, String> resources) {
        Map<String, String> res = resources == null ? Map.of() : resources;
        return new AgentSkill(name, description, markdown, res, SOURCE);
    }

    static SkillView toView(AgentSkill skill) {
        SkillView view = new SkillView();
        view.setName(skill.getName());
        view.setDescription(skill.getDescription());
        view.setMarkdown(skill.getSkillContent());
        view.setResources(skill.getResources());
        view.setResourceCount(skill.getResourcePaths().size());
        view.setMetadata(skill.getMetadata());
        return view;
    }

    static SkillView toSummary(AgentSkill skill) {
        SkillView view = new SkillView();
        view.setName(skill.getName());
        view.setDescription(skill.getDescription());
        view.setResourceCount(skill.getResourcePaths().size());
        return view;
    }
}
