package com.lambda.fusion.ai.skill.service;

import com.lambda.fusion.ai.skill.model.CreateSkill;
import com.lambda.fusion.ai.skill.model.SkillPackageImportResult;
import com.lambda.fusion.ai.skill.model.SkillPackageInspection;
import com.lambda.fusion.ai.skill.model.SkillView;
import com.lambda.fusion.ai.skill.model.UpdateSkill;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

/**
 * 技能市场服务：经配置选定的 {@code AgentSkillRepository} 做 CRUD（list/get/create/update/delete）。
 * 仓库未配置 -> 抛配置错误；只读仓库 -> 写操作抛不支持。
 *
 * @author Jin
 */
public interface SkillService {

    List<SkillView> list(boolean includeResources);

    SkillView get(String name);

    SkillView create(CreateSkill dto);

    SkillPackageInspection inspectPackage(MultipartFile file);

    SkillPackageImportResult importPackage(MultipartFile file, List<String> skillNames, boolean overwrite);

    void update(String name, UpdateSkill dto);

    void delete(String name);
}
