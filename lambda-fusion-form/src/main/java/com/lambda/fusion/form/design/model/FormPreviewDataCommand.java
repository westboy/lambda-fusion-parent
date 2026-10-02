package com.lambda.fusion.form.design.model;

import com.lambda.fusion.form.runtime.model.FormDataPageQuery;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

@Schema(description = "预览数据请求，分页复用正式运行时查询协议")
@SuppressFBWarnings("EI_EXPOSE_REP")
public record FormPreviewDataCommand(
        @NotNull @Valid FormPreviewSource source, @Valid FormDataPageQuery query, Map<String, Object> data) {}
