package com.lambda.fusion.form.runtime.support;

import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.FormProperties;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.model.AstCondition;
import com.lambda.fusion.form.runtime.model.FormDataPageQuery;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 查询 AST 三层白名单校验（安全红线，契约 §16）
 *
 * <p>字段/操作符/排序全部对照 {@link FormModel} 服务端校验，物理列名由本类解析返回，
 * 客户端永远碰不到列名。
 *
 * @author Zx
 */
@Component
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class QueryAstValidator {

    private final FormProperties properties;

    /**
     * 校验条件与排序白名单
     *
     * @param model 表单模型
     * @param query 分页查询
     */
    public void validate(FormModel model, FormDataPageQuery query) {
        List<AstCondition> conditions = query.getConditions() == null ? List.of() : query.getConditions();
        if (conditions.size() > properties.getMaxConditionCount()) {
            throw new FormBusinessException(FormErrorCode.FORM_QUERY_CONDITION_LIMIT);
        }
        Map<String, FormModel.FormField> queryable = model.fields().stream()
                .filter(FormModel.FormField::queryable)
                .collect(Collectors.toMap(FormModel.FormField::name, Function.identity()));
        for (AstCondition condition : conditions) {
            if (condition.getField() == null || !queryable.containsKey(condition.getField())) {
                throw new FormBusinessException(FormErrorCode.FORM_QUERY_FIELD_FORBIDDEN, condition.getField());
            }
            if (condition.getOp() == null || !FormConstants.QUERY_OPERATORS.contains(condition.getOp())) {
                throw new FormBusinessException(FormErrorCode.FORM_QUERY_OPERATOR_UNSUPPORTED, condition.getOp());
            }
        }
        List<FormDataPageQuery.SortItem> sorts = query.getSorts() == null ? List.of() : query.getSorts();
        Map<String, FormModel.FormField> sortable = model.fields().stream()
                .filter(FormModel.FormField::listVisible)
                .collect(Collectors.toMap(FormModel.FormField::name, Function.identity()));
        for (FormDataPageQuery.SortItem sort : sorts) {
            if (sort.field() == null || !sortable.containsKey(sort.field())) {
                throw new FormBusinessException(FormErrorCode.FORM_QUERY_FIELD_FORBIDDEN, sort.field());
            }
        }
    }

    /**
     * 解析条件为「物理列 + 操作符 + 值」列表（已通过 {@link #validate} 校验）
     */
    public List<ResolvedCondition> resolve(FormModel model, List<AstCondition> conditions) {
        Map<String, FormModel.FormField> byName =
                model.fields().stream().collect(Collectors.toMap(FormModel.FormField::name, Function.identity()));
        List<ResolvedCondition> resolved = new ArrayList<>();
        for (AstCondition condition : conditions == null ? List.<AstCondition>of() : conditions) {
            FormModel.FormField field = Objects.requireNonNull(byName.get(condition.getField()));
            resolved.add(new ResolvedCondition(field.column(), condition.getOp(), condition.getValue()));
        }
        return resolved;
    }

    /**
     * 解析排序为「物理列 + 方向」列表（已通过 {@link #validate} 校验）
     */
    public List<ResolvedSort> resolveSorts(FormModel model, List<FormDataPageQuery.SortItem> sorts) {
        Map<String, FormModel.FormField> byName =
                model.fields().stream().collect(Collectors.toMap(FormModel.FormField::name, Function.identity()));
        List<ResolvedSort> resolved = new ArrayList<>();
        for (FormDataPageQuery.SortItem sort : sorts == null ? List.<FormDataPageQuery.SortItem>of() : sorts) {
            FormModel.FormField field = Objects.requireNonNull(byName.get(sort.field()));
            resolved.add(new ResolvedSort(field.column(), sort.asc()));
        }
        return resolved;
    }

    /**
     * 解析后的查询条件（物理列名，服务端私有）
     */
    public record ResolvedCondition(String column, String op, Object value) {}

    /**
     * 解析后的排序（物理列名，服务端私有）
     */
    public record ResolvedSort(String column, boolean asc) {}
}
