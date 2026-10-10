package com.lambda.fusion.dict.model;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

/**
 * @author Jin
 */
@SuppressFBWarnings({"EI_EXPOSE_REP"})
@Data
@Schema(description = "数据字典简略信息")
public class DictType {

    @Schema(description = "id", hidden = true)
    private String id;

    @Size(max = 30, message = "字典编码长度不能超过30")
    @Schema(description = "字典类型")
    private String dictType;

    @Schema(description = "字典描述", example = "民族")
    private String dictName;

    @Schema(description = " 数据类型 0/null 静态类型 1 url  2 sql 3 enum")
    private Integer dataType;

    @Schema(description = " 类型参数, 最大长度512")
    private String dataTypeValue;

    @Schema(description = "子节点数据", hidden = true)
    private List<DictInfo> data;
}
