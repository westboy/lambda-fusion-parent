package com.lambda.fusion.form.runtime.model;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.fusion.core.pagination.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * 动态表单分页查询（AST 条件 + 白名单排序）
 *
 * <p>条件与排序列名由引擎按 {@code FormModel} 白名单解析，本类不含物理列名。
 *
 * @author Zx
 */
@Getter
@Setter
@Schema(description = "动态表单分页查询")
public class FormDataPageQuery extends PageQuery<Map<String, Object>> {

    @Schema(description = "查询条件列表")
    private List<AstCondition> conditions = new ArrayList<>();

    @Schema(description = "排序列表")
    private List<SortItem> sorts = new ArrayList<>();

    @Override
    public LambdaQueryWrapper<Map<String, Object>> getLambdaQueryWrapper() {
        throw new UnsupportedOperationException("动态表单查询经引擎 AST 构建，不走 LambdaQueryWrapper");
    }

    /**
     * 排序项（field 为字段标识，非物理列名）
     *
     * @param field 字段标识
     * @param asc 是否升序
     */
    @Schema(description = "排序项")
    public record SortItem(String field, boolean asc) {}
}
