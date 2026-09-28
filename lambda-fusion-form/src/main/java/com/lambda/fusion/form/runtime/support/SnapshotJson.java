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
}
