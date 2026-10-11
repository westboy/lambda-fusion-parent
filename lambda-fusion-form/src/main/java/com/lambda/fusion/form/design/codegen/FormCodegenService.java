package com.lambda.fusion.form.design.codegen;

import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.design.model.TableColumnView;
import com.lambda.fusion.form.design.support.TableMetadataReader;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.support.FormModel;
import com.lambda.fusion.form.runtime.support.FormModel.FormField;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 表单代码生成器（毕业路径脚手架）：从已发布快照生成符合工程契约的后端 CRUD 代码包
 *
 * <p>生成物为开发者收编用的脚手架（Entity/Mapper/DTO/Service/Controller + README），
 * 以 zip 下载后由开发者放入目标模块并按契约格式化（Spotless）。运行时机制
 * （关联/带入/角色权限/数据权限）不生成对等物，仅在注释中提示自行实现。
 * 仅支持已发布表单；生成器只读快照，不触碰运行时路径。
 *
 * @author Zx
 */
@Service
@RequiredArgsConstructor
public class FormCodegenService {

    /** Java 关键字/字面量：作为字段名时追加后缀转义 */
    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "class",
            "public",
            "private",
            "protected",
            "static",
            "final",
            "void",
            "new",
            "return",
            "if",
            "else",
            "for",
            "while",
            "switch",
            "case",
            "default",
            "try",
            "catch",
            "throw",
            "throws",
            "import",
            "package",
            "extends",
            "implements",
            "interface",
            "enum",
            "this",
            "super",
            "abstract",
            "native",
            "transient",
            "volatile",
            "synchronized",
            "instanceof",
            "do",
            "break",
            "continue",
            "assert",
            "boolean",
            "byte",
            "char",
            "double",
            "float",
            "int",
            "long",
            "short",
            "null",
            "true",
            "false",
            "var",
            "record",
            "yield",
            "sealed");

    private final FormModelProvider modelProvider;
    private final TableMetadataReader metadataReader;

    /**
     * 生成代码包：路径（zip 内相对路径）→ 文本内容
     *
     * @param formKey    已发布表单标识
     * @param packageName 目标包名（如 com.demo.leave）
     */
    public Map<String, String> generate(String formKey, String packageName) {
        if (packageName == null || !packageName.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*")) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "包名非法：须为小写点分标识（如 com.demo.leave）");
        }
        FormModel model = modelProvider.require(formKey);
        String className = classNameOf(model.tableName());
        Map<String, String> files = new LinkedHashMap<>();
        String base = "src/main/java/" + packageName.replace('.', '/');
        String entityName = className;
        files.put(base + "/domain/" + entityName + ".java", renderEntity(model, packageName, entityName));
        files.put(base + "/mapper/" + entityName + "Mapper.java", renderMapper(model, packageName, entityName));
        files.put(base + "/model/" + entityName + "Dto.java", renderDto(model, packageName, entityName));
        files.put(base + "/service/" + entityName + "Service.java", renderService(model, packageName, entityName));
        files.put(
                base + "/service/impl/" + entityName + "ServiceImpl.java",
                renderServiceImpl(model, packageName, entityName));
        files.put(
                base + "/controller/" + entityName + "Controller.java",
                renderController(model, packageName, entityName));
        files.put("README.txt", renderReadme(model, packageName, entityName));
        return files;
    }

    /** zip 打包（路径 → 内容） */
    public void zip(Map<String, String> files, java.io.OutputStream out) throws java.io.IOException {
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    /** 物理表名 → 类名：LA_DEMO_LEAVE → LaDemoLeave */
    private String classNameOf(String tableName) {
        StringBuilder builder = new StringBuilder();
        for (String part : tableName.toLowerCase(Locale.ROOT).split("_")) {
            if (!part.isBlank()) {
                builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        String name = builder.toString();
        if (name.isBlank() || !FormConstants.isIdentifier(name)) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "表名无法推导类名：" + tableName);
        }
        return name;
    }

    /** 实体字段：主表落库字段（运行时机制提示进注释）；audit 列按物理存在显式生成（不依赖 BaseEntity，自包含） */
    private List<FormField> entityFields(FormModel model) {
        List<FormField> fields = new ArrayList<>();
        for (FormField field : model.fields()) {
            if (field.subFormKey() == null && field.persisted()) {
                fields.add(field);
            }
        }
        return fields;
    }

    private List<String> auditColumns(FormModel model) {
        List<String> present = new ArrayList<>();
        Set<String> audit = Set.of("CREATED_BY", "CREATED_AT", "UPDATED_BY", "UPDATED_AT");
        for (TableColumnView column : metadataReader.listColumns(model.datasourceId(), model.tableName())) {
            String name = column.getColumnName().toUpperCase(Locale.ROOT);
            if (audit.contains(name)) {
                present.add(name);
            }
        }
        return present;
    }

    // ---------- 各文件渲染 ----------

    private String renderEntity(FormModel model, String packageName, String entityName) {
        List<FormField> fields = entityFields(model);
        List<String> audit = auditColumns(model);
        StringBuilder builder = new StringBuilder();
        builder.append("package ").append(packageName).append(".domain;\n\n");
        builder.append("import com.baomidou.mybatisplus.annotation.IdType;\n");
        builder.append("import com.baomidou.mybatisplus.annotation.TableField;\n");
        builder.append("import com.baomidou.mybatisplus.annotation.TableId;\n");
        builder.append("import com.baomidou.mybatisplus.annotation.TableName;\n");
        appendImports(builder, fields, audit);
        builder.append("import lombok.Data;\n\n");
        builder.append("/**\n * ").append(model.formName()).append("\n *\n");
        builder.append(" * <p>由动态表单代码生成器从发布快照 v")
                .append(model.version())
                .append(" 生成（表单标识 ")
                .append(model.formKey())
                .append("）。\n");
        builder.append(" * 运行时机制（关联回显/带入源/角色权限/数据权限）不包含在生成物中，按需自行实现。\n");
        builder.append(" */\n");
        builder.append("@Data\n");
        builder.append("@TableName(\"").append(model.tableName()).append("\")\n");
        builder.append("public class ").append(entityName).append(" {\n\n");
        builder.append("    /** 主键（雪花 id，由应用生成） */\n");
        builder.append("    @TableId(value = \"ID\", type = IdType.INPUT)\n");
        builder.append("    private String id;\n");
        for (FormField field : fields) {
            builder.append('\n');
            builder.append("    /** ")
                    .append(field.label())
                    .append(fieldCommentSuffix(field))
                    .append(" */\n");
            builder.append("    @TableField(\"").append(field.column()).append("\")\n");
            builder.append("    private ")
                    .append(javaType(field))
                    .append(' ')
                    .append(javaField(field.name()))
                    .append(";\n");
        }
        for (String column : audit) {
            builder.append('\n');
            builder.append("    /** ").append(auditLabel(column)).append(" */\n");
            String javaName =
                    switch (column) {
                        case "CREATED_BY" -> "createdBy";
                        case "CREATED_AT" -> "createdAt";
                        case "UPDATED_BY" -> "updatedBy";
                        default -> "updatedAt";
                    };
            String javaType = column.endsWith("_AT") ? "java.time.LocalDateTime" : "String";
            builder.append("    @TableField(\"").append(column).append("\")\n");
            builder.append("    private ")
                    .append(javaType)
                    .append(' ')
                    .append(javaName)
                    .append(";\n");
        }
        builder.append("}\n");
        return builder.toString();
    }

    private String renderMapper(FormModel model, String packageName, String entityName) {
        return ("package " + packageName + ".mapper;\n\n"
                + "import com.baomidou.mybatisplus.core.mapper.BaseMapper;\n"
                + "import " + packageName + ".domain." + entityName + ";\n\n"
                + "/** " + model.formName() + " Mapper（MyBatis-Plus） */\n"
                + "public interface " + entityName + "Mapper extends BaseMapper<" + entityName + "> {}\n");
    }

    private String renderDto(FormModel model, String packageName, String entityName) {
        List<FormField> fields = entityFields(model);
        StringBuilder builder = new StringBuilder();
        builder.append("package ").append(packageName).append(".model;\n\n");
        builder.append("import com.lambda.cloud.core.annotation.AutoConverter;\n");
        builder.append("import com.lambda.cloud.core.shared.BaseDTO;\n");
        appendImports(builder, fields, List.of());
        builder.append("import lombok.Data;\n");
        builder.append("import lombok.EqualsAndHashCode;\n\n");
        builder.append("/**\n * ").append(model.formName()).append(" 提交对象\n *\n");
        builder.append(" * <p>反向转换（实体 → DTO）用于查询结果回显；新增/更新走本对象提交。\n");
        builder.append(" */\n");
        builder.append("@AutoConverter(target = ").append(entityName).append(".class, isReverse = true)\n");
        builder.append("@Data\n");
        builder.append("@EqualsAndHashCode(callSuper = true)\n");
        builder.append("public class ")
                .append(entityName)
                .append("Dto extends BaseDTO<")
                .append(entityName)
                .append("> {\n\n");
        for (FormField field : fields) {
            builder.append("    /** ")
                    .append(field.label())
                    .append(fieldCommentSuffix(field))
                    .append(" */\n");
            builder.append("    private ")
                    .append(javaType(field))
                    .append(' ')
                    .append(javaField(field.name()))
                    .append(";\n\n");
        }
        builder.append("}\n");
        return builder.toString();
    }

    private String renderService(FormModel model, String packageName, String entityName) {
        return ("package " + packageName + ".service;\n\n"
                + "import " + packageName + ".domain." + entityName + ";\n\n"
                + "/** " + model.formName() + " 业务接口（按需扩展自定义方法） */\n"
                + "public interface " + entityName + "Service {}\n");
    }

    private String renderServiceImpl(FormModel model, String packageName, String entityName) {
        return ("package " + packageName + ".service.impl;\n\n"
                + "import com.lambda.fusion.core.service.AbstractCrudService;\n"
                + "import " + packageName + ".domain." + entityName + ";\n"
                + "import " + packageName + ".mapper." + entityName + "Mapper;\n"
                + "import " + packageName + ".model." + entityName + "Dto;\n"
                + "import " + packageName + ".service." + entityName + "Service;\n"
                + "import org.springframework.stereotype.Service;\n\n"
                + "/**\n * " + model.formName() + " 业务实现\n *\n"
                + " * <p>CRUD 由 AbstractCrudService 提供（实体/DTO 经 AutoConverter 互转）；\n"
                + " * 分页条件、唯一校验、树/子表等复杂规则按需在此扩展。\n"
                + " */\n"
                + "@Service\n"
                + "public class " + entityName + "ServiceImpl\n"
                + "        extends AbstractCrudService<" + entityName + ", " + entityName + "Dto, "
                + entityName + "Mapper>\n"
                + "        implements " + entityName + "Service {}\n");
    }

    private String renderController(FormModel model, String packageName, String entityName) {
        String route = model.formKey().replace('_', '-');
        String permission = route;
        return ("package " + packageName + ".controller;\n\n"
                + "import cn.dev33.satoken.annotation.SaCheckPermission;\n"
                + "import cn.hutool.core.bean.BeanUtil;\n"
                + "import com.baomidou.mybatisplus.core.metadata.IPage;\n"
                + "import com.baomidou.mybatisplus.extension.plugins.pagination.Page;\n"
                + "import " + packageName + ".domain." + entityName + ";\n"
                + "import " + packageName + ".model." + entityName + "Dto;\n"
                + "import " + packageName + ".service." + entityName + "Service;\n"
                + "import io.swagger.v3.oas.annotations.Operation;\n"
                + "import io.swagger.v3.oas.annotations.tags.Tag;\n"
                + "import jakarta.validation.Valid;\n"
                + "import lombok.RequiredArgsConstructor;\n"
                + "import org.springframework.web.bind.annotation.DeleteMapping;\n"
                + "import org.springframework.web.bind.annotation.GetMapping;\n"
                + "import org.springframework.web.bind.annotation.PathVariable;\n"
                + "import org.springframework.web.bind.annotation.PostMapping;\n"
                + "import org.springframework.web.bind.annotation.PutMapping;\n"
                + "import org.springframework.web.bind.annotation.RequestBody;\n"
                + "import org.springframework.web.bind.annotation.RequestMapping;\n"
                + "import org.springframework.web.bind.annotation.RequestParam;\n"
                + "import org.springframework.web.bind.annotation.RestController;\n\n"
                + "/**\n * " + model.formName() + " API（脚手架：权限码与查询条件按需调整）\n */\n"
                + "@RestController\n"
                + "@RequestMapping(\"/" + route + "\")\n"
                + "@Tag(name = \"" + model.formName() + "\")\n"
                + "@RequiredArgsConstructor\n"
                + "public class " + entityName + "Controller {\n\n"
                + "    private final " + entityName + "Service " + decap(entityName) + "Service;\n\n"
                + "    @SaCheckPermission(\"" + permission + ":page\")\n"
                + "    @GetMapping(\"/page\")\n"
                + "    @Operation(summary = \"分页查询\")\n"
                + "    public IPage<" + entityName + "> page(\n"
                + "            @RequestParam(defaultValue = \"1\") long current,\n"
                + "            @RequestParam(defaultValue = \"10\") long size) {\n"
                + "        return " + decap(entityName) + "Service.page(new Page<>(current, size));\n"
                + "    }\n\n"
                + "    @SaCheckPermission(\"" + permission + ":detail\")\n"
                + "    @GetMapping(\"/{id}\")\n"
                + "    @Operation(summary = \"详情\")\n"
                + "    public " + entityName + " detail(@PathVariable String id) {\n"
                + "        return " + decap(entityName) + "Service.getById(id);\n"
                + "    }\n\n"
                + "    @SaCheckPermission(\"" + permission + ":add\")\n"
                + "    @PostMapping\n"
                + "    @Operation(summary = \"新增\")\n"
                + "    public boolean create(@Valid @RequestBody " + entityName + "Dto dto) {\n"
                + "        return " + decap(entityName) + "Service.save(BeanUtil.toBean(dto, "
                + entityName + ".class));\n"
                + "    }\n\n"
                + "    @SaCheckPermission(\"" + permission + ":edit\")\n"
                + "    @PutMapping(\"/{id}\")\n"
                + "    @Operation(summary = \"编辑\")\n"
                + "    public boolean update(@PathVariable String id, @Valid @RequestBody "
                + entityName + "Dto dto) {\n"
                + "        " + entityName + " entity = BeanUtil.toBean(dto, " + entityName + ".class);\n"
                + "        entity.setId(id);\n"
                + "        return " + decap(entityName) + "Service.updateById(entity);\n"
                + "    }\n\n"
                + "    @SaCheckPermission(\"" + permission + ":delete\")\n"
                + "    @DeleteMapping(\"/{id}\")\n"
                + "    @Operation(summary = \"删除\")\n"
                + "    public boolean remove(@PathVariable String id) {\n"
                + "        return " + decap(entityName) + "Service.removeById(id);\n"
                + "    }\n"
                + "}\n");
    }

    private String renderReadme(FormModel model, String packageName, String entityName) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        return ("表单代码生成包（脚手架）\n"
                + "========================\n\n"
                + "表单标识 : " + model.formKey() + "\n"
                + "表单名称 : " + model.formName() + "\n"
                + "物理表   : " + model.tableName() + "\n"
                + "发布版本 : v" + model.version() + "\n"
                + "生成时间 : " + timestamp + "\n\n"
                + "集成步骤\n"
                + "--------\n"
                + "1. 将 src/main/java 下的文件按包名放入目标模块（或调整 packageName 后重新生成）；\n"
                + "2. 目标模块需已依赖 lambda-fusion-core（AbstractCrudService/AutoConverter）与 MyBatis-Plus；\n"
                + "3. 在 MapperScan 覆盖的包中确认 " + entityName + "Mapper 被扫描；\n"
                + "4. 启动类/配置类开启 MyBatis-Plus 分页插件（分页端点需要）；\n"
                + "5. 权限码（" + model.formKey().replace('_', '-') + ":page/detail/add/edit/delete）\n"
                + "   在权限管理中分配，或改为项目既有权限码；\n"
                + "6. 全部文件放入后执行 mvn spotless:apply（Palantir 格式）并编译；\n"
                + "7. 运行时机制（关联回显/子表带入/角色权限/数据权限/审批锁定）不包含在生成物中，按需自行实现；\n"
                + "   收编后建议停用对应动态表单（避免两套写入路径并存）。\n");
    }

    // ---------- 工具 ----------

    private void appendImports(StringBuilder builder, List<FormField> fields, List<String> audit) {
        Set<String> imports = new java.util.LinkedHashSet<>();
        for (FormField field : fields) {
            String type = javaType(field);
            if (type.contains(".")) {
                imports.add(type);
            }
        }
        for (String column : audit) {
            if (column.endsWith("_AT")) {
                imports.add("java.time.LocalDateTime");
            }
        }
        imports.stream().sorted().distinct().forEach(imp -> builder.append("import ")
                .append(imp)
                .append(";\n"));
        if (!imports.isEmpty()) {
            builder.append('\n');
        }
    }

    private String javaType(FormField field) {
        return switch (field.type()) {
            case "INT" -> "Long";
            case "DECIMAL" -> "java.math.BigDecimal";
            case "DATE" -> "java.time.LocalDate";
            case "DATETIME" -> "java.time.LocalDateTime";
            case "BOOL" -> "Boolean";
            default -> "String";
        };
    }

    private String javaField(String name) {
        return JAVA_KEYWORDS.contains(name) ? name + "Field" : name;
    }

    private String fieldCommentSuffix(FormField field) {
        return switch (field.type()) {
            case "DICT" -> "（字典 " + field.dictType() + "，存字典值）";
            case "LOOKUP" ->
                "（关联 " + field.lookup().targetFormKey() + "，存目标行 id，展示回显字段 "
                        + field.lookup().displayField() + "）";
            default -> "";
        };
    }

    private String auditLabel(String column) {
        return switch (column) {
            case "CREATED_BY" -> "创建人";
            case "CREATED_AT" -> "创建时间";
            case "UPDATED_BY" -> "修改人";
            default -> "修改时间";
        };
    }

    private String decap(String name) {
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
