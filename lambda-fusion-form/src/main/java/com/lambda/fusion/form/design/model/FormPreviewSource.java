package com.lambda.fusion.form.design.model;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 预览来源：草稿 ID、指定发布版本，或当前未保存设计。 */
@Schema(description = "预览模型来源")
@SuppressFBWarnings("EI_EXPOSE_REP")
public record FormPreviewSource(
        @Size(min = 1, max = 64) String formId,
        @Positive Long version,
        @PositiveOrZero Long draftVersion,
        @Valid FormDefSaveCommand design) {}
