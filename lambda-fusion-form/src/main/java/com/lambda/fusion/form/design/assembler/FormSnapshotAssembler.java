package com.lambda.fusion.form.design.assembler;

import com.lambda.fusion.form.design.entity.FormDefEntity;
import com.lambda.fusion.form.design.entity.FormFieldEntity;
import com.lambda.fusion.form.design.entity.FormRelationEntity;
import com.lambda.fusion.form.design.model.FormDefSaveCommand;
import com.lambda.fusion.form.design.model.FormDefView;
import com.lambda.fusion.form.design.model.FormFieldItem;
import com.lambda.fusion.form.runtime.support.FieldConfigs;
import com.lambda.fusion.form.runtime.support.FormDefSnapshot;
import com.lambda.fusion.form.runtime.support.FormLayout;
import com.lambda.fusion.form.runtime.support.FormLayouts;
import java.util.List;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.ReportingPolicy;
import tools.jackson.databind.ObjectMapper;

/** 发布和三种预览共用的快照映射；JSON 配置统一经 FieldConfigs 与 FormLayouts 解释。 */
@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)
public interface FormSnapshotAssembler {
    @Mapping(target = "formLayout", source = "def.formLayout")
    @Mapping(target = "listConfig", source = "def.listConfig")
    @Mapping(target = "fields", source = "fields")
    @Mapping(target = "relations", source = "relations")
    FormDefSnapshot assemble(
            FormDefEntity def,
            List<FormFieldEntity> fields,
            List<FormRelationEntity> relations,
            @Context ObjectMapper mapper);

    FormDefSnapshot fromCommand(FormDefSaveCommand command, @Context ObjectMapper mapper);

    FormDefSnapshot fromView(FormDefView view, @Context ObjectMapper mapper);

    @Mapping(target = "query", source = "queryConfig")
    @Mapping(target = "list", source = "listConfig")
    @Mapping(target = "form", source = "formConfig")
    FormDefSnapshot.Field field(FormFieldEntity field, @Context ObjectMapper mapper);

    @Mapping(target = "query", source = "queryConfig")
    @Mapping(target = "list", source = "listConfig")
    @Mapping(target = "form", source = "formConfig")
    FormDefSnapshot.Field field(FormFieldItem field, @Context ObjectMapper mapper);

    default FormLayout layout(String json, @Context ObjectMapper mapper) {
        return FormLayouts.read(mapper, json);
    }

    default FormDefSnapshot.ListConfig listConfig(String json, @Context ObjectMapper mapper) {
        return FieldConfigs.toListConfig(mapper, json);
    }

    default FormDefSnapshot.QueryConf query(String json, @Context ObjectMapper mapper) {
        return FieldConfigs.toQueryConf(mapper, json);
    }

    default FormDefSnapshot.ListConf list(String json, @Context ObjectMapper mapper) {
        return FieldConfigs.toListConf(mapper, json);
    }

    default FormDefSnapshot.FormConf form(String json, @Context ObjectMapper mapper) {
        return FieldConfigs.toFormConf(mapper, json);
    }
}
