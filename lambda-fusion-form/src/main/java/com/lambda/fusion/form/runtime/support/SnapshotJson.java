package com.lambda.fusion.form.runtime.support;

import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.support.FormModel.TreeConfig;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 快照 JSON 序列化（发布写入与运行时读取共用，未知字段忽略以兼容结构演进）
 *
 * @author Zx
 */
@Component
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class SnapshotJson {

    private final ObjectMapper objectMapper;

    public String write(FormDefSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JacksonException e) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "快照序列化失败：" + e.getMessage());
        }
    }

    public FormDefSnapshot read(String json) {
        try {
            return objectMapper.readValue(json, FormDefSnapshot.class);
        } catch (JacksonException e) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "快照解析失败：" + e.getMessage());
        }
    }

    public TreeConfig readTreeConfig(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<TreeConfig>() {});
        } catch (JacksonException e) {
            throw new FormBusinessException(FormErrorCode.FORM_TABLE_INVALID, "treeConfig 解析失败：" + e.getMessage());
        }
    }

    /**
     * 详情呈现配置解析（detailConfig JSON → 归一化；mode/width/columns 白名单收敛）。
     * 非法 JSON 按请求参数非法拒绝（保存期同样预校验，不拖到发布期）。
     */
    public FormModel.DetailConfig readDetailConfig(String json) {
        try {
            if (json == null || json.isBlank()) {
                return FormModel.DetailConfig.DEFAULT;
            }
            var node = objectMapper.readTree(json);
            String mode = node.path("mode").asText("dialog");
            String width = node.path("width").asText("medium");
            int columns = node.path("columns").isIntegralNumber()
                    ? node.path("columns").asInt(2)
                    : 2;
            boolean collapsed = node.path("systemCollapsed").asBoolean(false);
            if (!java.util.Set.of("dialog", "drawer", "full").contains(mode)) {
                mode = "dialog";
            }
            if (!java.util.Set.of("small", "medium", "large").contains(width)) {
                width = "medium";
            }
            if (columns < 1 || columns > 3) {
                columns = 2;
            }
            return new FormModel.DetailConfig(mode, width, columns, collapsed);
        } catch (JacksonException e) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "detailConfig 解析失败：" + e.getMessage());
        }
    }

    /** 编译期拦截被篡改快照中的子表列表配置（与保存期同一校验规则） */
    public void validateSubTableConfig(String json) {
        SubTableConfigs.columnFields(objectMapper, json);
        SubTableConfigs.rowButtons(objectMapper, json);
    }
}
