package com.lambda.fusion.form.design.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record FormRouteCommand(
        @NotBlank @Size(max = 64) String parentId,
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 200) @Pattern(regexp = "^(/[A-Za-z0-9_][A-Za-z0-9_-]*)+/?$") String path,
        @Size(max = 128) String icon,
        @Min(1) @Max(10000) int orderNo) {}
