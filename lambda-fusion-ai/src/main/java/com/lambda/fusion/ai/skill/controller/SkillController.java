package com.lambda.fusion.ai.skill.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.ai.skill.model.CreateSkill;
import com.lambda.fusion.ai.skill.model.SkillPackageImportResult;
import com.lambda.fusion.ai.skill.model.SkillPackageInspection;
import com.lambda.fusion.ai.skill.model.SkillResourceView;
import com.lambda.fusion.ai.skill.model.SkillView;
import com.lambda.fusion.ai.skill.model.UpdateSkill;
import com.lambda.fusion.ai.skill.service.SkillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@SaCheckRole("ROLE_DEV")
@Tag(name = "技能市场管理")
@RestController
@RequestMapping("/v1/ai/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;

    @Operation(summary = "列出全部技能")
    @GetMapping
    public List<SkillView> list(
            @Parameter(description = "是否返回资源文件内容") @RequestParam(value = "includeResources", defaultValue = "true")
                    boolean includeResources) {
        return skillService.list(includeResources);
    }

    @Operation(summary = "查询技能")
    @GetMapping("/{name}")
    public SkillView get(
            @Parameter(description = "技能名", required = true) @PathVariable String name,
            @Parameter(description = "是否返回全部资源正文") @RequestParam(value = "includeResources", defaultValue = "true")
                    boolean includeResources) {
        return skillService.get(name, includeResources);
    }

    @Operation(summary = "查询单个技能资源")
    @GetMapping("/{name}/resources/content")
    public SkillResourceView getResource(
            @Parameter(description = "技能名", required = true) @PathVariable String name,
            @Parameter(description = "资源路径", required = true) @RequestParam("path") String path) {
        return skillService.getResource(name, path);
    }

    @OperationLog
    @Operation(summary = "新增技能")
    @PostMapping
    public SkillView create(@RequestBody @Valid CreateSkill dto) {
        return skillService.create(dto);
    }

    @Operation(summary = "预检技能包")
    @PostMapping(value = "/packages/inspect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SkillPackageInspection inspectPackage(@RequestParam("file") MultipartFile file) {
        return skillService.inspectPackage(file);
    }

    @OperationLog
    @Operation(summary = "导入技能包")
    @PostMapping(value = "/packages/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SkillPackageImportResult importPackage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "skillNames", required = false) List<String> skillNames,
            @RequestParam(value = "overwrite", defaultValue = "false") boolean overwrite) {
        return skillService.importPackage(file, skillNames, overwrite);
    }

    @OperationLog
    @Operation(summary = "更新技能")
    @PutMapping("/{name}")
    public void update(
            @Parameter(description = "技能名", required = true) @PathVariable String name,
            @RequestBody @Valid UpdateSkill dto) {
        skillService.update(name, dto);
    }

    @OperationLog
    @Operation(summary = "删除技能")
    @DeleteMapping("/{name}")
    public void delete(@Parameter(description = "技能名", required = true) @PathVariable String name) {
        skillService.delete(name);
    }
}
