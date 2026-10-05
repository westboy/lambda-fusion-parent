package com.lambda.fusion.form.runtime.controller;

import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.FormProperties;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.support.FormModel;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import com.lambda.fusion.upload.model.AttachmentView;
import com.lambda.fusion.upload.service.AttachmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.Locale;
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
 * <p>复用 OSS 附件体系存储（不归置业务分组），引擎负责入口校验：
 * 目标表单已发布且配置了 FILE 字段；权限为表单新增/编辑（设计预览缺省 formKey 时为设计预览权限）；
 * 文件大小与扩展名走 {@code lambda.fusion.form.upload-*} 白名单。
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
    private final FormModelProvider modelProvider;
    private final FormProperties properties;

    @PostMapping("/upload")
    @Operation(summary = "上传表单附件", description = "返回附件 id/名称/地址；字段值由前端组装为 JSON 数组存入 FILE 文本列")
    public Map<String, String> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) @Parameter(description = "目标表单标识（设计预览可缺省）") String formKey) {
        if (file == null || file.isEmpty()) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "上传文件不能为空");
        }
        validateFile(file);
        if (StringUtils.hasText(formKey)) {
            if (!FormConstants.isIdentifier(formKey)) {
                throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "表单标识非法：" + formKey);
            }
            // 表单必须已发布且配置 FILE 字段；权限只认该表单的新增/编辑（设计预览权限不能越权传业务附件）
            FormModel model = modelProvider.require(formKey);
            boolean hasFileField = model.fields().stream().anyMatch(field -> "FILE".equals(field.type()));
            if (!hasFileField) {
                throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "该表单未配置文件字段：" + formKey);
            }
            if (!AuthUtils.hasPermission("form:" + formKey + ":add")
                    && !AuthUtils.hasPermission("form:" + formKey + ":edit")) {
                AuthUtils.checkPermission("form:" + formKey + ":add");
            }
        } else {
            AuthUtils.checkPermission("form:design:preview");
        }
        AttachmentView view = attachmentService.upload(file, null, null);
        Map<String, String> result = new LinkedHashMap<>();
        result.put("id", view.getId());
        result.put("name", view.getFileName());
        result.put("url", view.getFileUrl());
        return result;
    }

    /** 大小与扩展名白名单（扩展名取原始文件名最后一段，小写比对） */
    private void validateFile(MultipartFile file) {
        if (file.getSize() > properties.getUploadMaxSizeMb() * 1024 * 1024) {
            throw new FormBusinessException(
                    FormErrorCode.INVALID_PARAMETER, "文件超过大小上限（" + properties.getUploadMaxSizeMb() + "MB）");
        }
        String original = StringUtils.getFilenameExtension(file.getOriginalFilename());
        String extension = original == null ? "" : original.toLowerCase(Locale.ROOT);
        if (!properties.getUploadAllowedExtensions().contains(extension)) {
            throw new FormBusinessException(
                    FormErrorCode.INVALID_PARAMETER, "文件类型不允许：" + (extension.isEmpty() ? "无扩展名" : extension));
        }
    }
}
