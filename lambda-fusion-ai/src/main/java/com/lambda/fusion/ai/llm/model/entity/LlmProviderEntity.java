package com.lambda.fusion.ai.llm.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lambda.fusion.ai.AiConstants.ModelType;
import com.lambda.fusion.ai.AiConstants.ProviderType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.Data;

@Data
@TableName("ai_llm_provider")
@Schema(description = "LLM 提供方配置")
public class LlmProviderEntity {

    @TableId("id")
    @Schema(description = "主键")
    private String id;

    @TableField("name")
    @Schema(description = "提供方名称")
    private String name;

    @TableField("provider_type")
    @Schema(description = "AgentScope 提供方类型")
    private String providerType;

    @TableField("base_url")
    @Schema(description = "服务地址")
    private String baseUrl;

    @TableField("api_key_encrypted")
    @JsonIgnore
    @Schema(description = "API Key 密文(AES-GCM)")
    private String apiKeyEncrypted;

    @TableField("enabled")
    @Schema(description = "是否启用")
    private Boolean enabled;

    @TableField("enable_media_gen")
    @Schema(description = "该Key是否开通图像/视频等媒体生成能力(由配置者声明;运行时据此挂载生成工具)")
    private Boolean enableMediaGen;

    @TableField("remark")
    @Schema(description = "备注")
    private String remark;

    @TableField("created_at")
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @Schema(description = "该厂商可配置的模型类型")
    public Set<ModelType> getSupportedModelTypes() {
        ProviderType type = ProviderType.of(providerType);
        return type == null ? Set.of() : type.getSupportedModelTypes();
    }

    @Schema(description = "该提供方当前已由 AgentScope 运行时接通的模型类型")
    public Set<ModelType> getRuntimeModelTypes() {
        ProviderType type = ProviderType.of(providerType);
        return type == null ? Set.of() : type.getRuntimeModelTypes();
    }
}
