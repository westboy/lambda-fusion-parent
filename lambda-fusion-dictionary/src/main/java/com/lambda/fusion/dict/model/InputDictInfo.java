package com.lambda.fusion.dict.model;

import static com.lambda.fusion.dict.DictConstants.*;

import com.lambda.cloud.core.annotation.AutoConverter;
import com.lambda.cloud.core.shared.BaseDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

/**
 * @author Jin
 */
@AutoConverter(target = DictInfo.class)
@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "更新数据字典对象")
public class InputDictInfo extends BaseDTO<DictInfo> {

    @NotBlank
    String id;

    @NotBlank(message = MSG_DICT_FIELD_NAME_NOT_EMPTY)
    @Size(max = 200, message = "字典项名长度不能超过200")
    String fieldName;

    @NotNull(message = MSG_DICT_SORT_NUMBER_NOT_EMPTY)
    Integer sort;

    @NotNull(message = MSG_DICT_ENABLED_NOT_EMPTY)
    Integer enableState;

    @NotNull
    Integer selectable;

    @NotBlank
    String fieldType;

    String remarks;

    @NotBlank
    @Size(max = 30, message = "字典编码长度不能超过30")
    private String dictType;

    private Additional additional;

    @Getter
    @Setter
    public static class Additional {
        private Map<String, Object> parameters;
    }
}
