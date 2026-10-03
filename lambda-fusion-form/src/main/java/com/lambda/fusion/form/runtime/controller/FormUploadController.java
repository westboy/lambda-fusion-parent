package com.lambda.fusion.form.runtime.controller;

import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.upload.model.AttachmentView;
import com.lambda.fusion.upload.service.AttachmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 动态表单文件上传（FILE 字段）
 *
 * <p>复用 OSS 附件体系存储，引擎只做权限入口与分组归置：
 * 权限为设计预览或目标表单新增/编辑（任一，沿用统一开发者豁免）；
 * 附件归置到 form-{formKey} 分组（设计端为 form-design）。
 * 字段值由前端组装为 JSON 数组存入 FILE 文本列，本端点不读写业务表。
 *
 * @author Zx
 */
@RestController
@RequestMapping("/v1/form")
@Tag(name = "动态表单文件上传")
@RequiredArgsConstructor
public class FormUploadController {

    private final AttachmentService attachmentService;

    @PostMapping("/upload")
    @Operation(summary = "上传表单附件", description = "返回附件 id/名称/地址；字段值由前端组装为 JSON 数组存入 FILE 文本列")
    public Map<String, String> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) @Parameter(description = "目标表单标识（设计预览可缺省）") String formKey) {
        String normalized = null;
        if (StringUtils.hasText(formKey)) {
            if (!FormConstants.isIdentifier(formKey)) {
                throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "表单标识非法：" + formKey);
            }
            normalized = formKey;
        }
        requireUploadPermission(normalized);
        String groupId = normalized == null ? "form-design" : "form-" + normalized;
        AttachmentView view = attachmentService.upload(file, groupId, null);
        Map<String, String> result = new LinkedHashMap<>();
        result.put("id", view.getId());
        result.put("name", view.getFileName());
        result.put("url", view.getFileUrl());
        return result;
    }

    /**
     * 权限入口：设计预览，或目标表单的新增/编辑（任一，沿用统一开发者豁免）。
     * 全部未命中时按目标权限码抛统一未授权异常。
     */
    private void requireUploadPermission(String formKey) {
        if (AuthUtils.hasPermission("form:design:preview")) {
            return;
        }
        if (StringUtils.hasText(formKey)
                && (AuthUtils.hasPermission("form:" + formKey + ":add")
                        || AuthUtils.hasPermission("form:" + formKey + ":edit"))) {
            return;
        }
        AuthUtils.checkPermission(StringUtils.hasText(formKey) ? "form:" + formKey + ":add" : "form:design:preview");
    }
}
