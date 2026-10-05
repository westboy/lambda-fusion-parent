package com.lambda.fusion.form;

import java.util.List;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Form 模块配置属性
 *
 * @author Zx
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "lambda.fusion.form")
public class FormProperties {

    /** 总开关 */
    private boolean enabled = true;

    /** 允许动态读写的物理表名（精确匹配），默认不开放任何业务表。 */
    private List<String> allowedTables = List.of();

    public List<String> getAllowedTables() {
        return List.copyOf(allowedTables);
    }

    public void setAllowedTables(List<String> allowedTables) {
        this.allowedTables = allowedTables == null ? List.of() : List.copyOf(allowedTables);
    }

    /** 查询条件数上限 */
    private int maxConditionCount = 20;

    /** 单个 IN 条件的值数上限（防低成本全表扫描） */
    private int maxInValues = 500;

    /** 深分页页码上限（超出钳制到上限） */
    private long maxPageNum = 10000;

    /** INLINE 子表随主行加载的每主行子行上限（超出截断，完整数据走台账式端点） */
    private long maxInlineSubRows = 200;

    /** 导出行数上限（超出直接拒绝，防全表拖出） */
    private long maxExportRows = 10000;

    /** 树子孙节点 id 集上限 */
    private int maxTreeDescendants = 500;

    /** FILE 字段上传大小上限（MB） */
    private long uploadMaxSizeMb = 10;

    /** FILE 字段上传允许的扩展名（小写，不含点） */
    private List<String> uploadAllowedExtensions = List.of(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "pdf", "doc", "docx", "xls", "xlsx", "csv", "txt", "zip");

    public List<String> getUploadAllowedExtensions() {
        return List.copyOf(uploadAllowedExtensions);
    }

    public void setUploadAllowedExtensions(List<String> uploadAllowedExtensions) {
        this.uploadAllowedExtensions =
                uploadAllowedExtensions == null ? List.of() : List.copyOf(uploadAllowedExtensions);
    }

    /** 选表导列与发布校验的表名排除正则（元数据表自身与 Liquibase 系统表默认不可映射） */
    private String tableExcludePattern = "la_form_.*|databasechangelog.*|qrtz_.*";

    /** 设计、发布与运行共同使用的目标表边界。 */
    public boolean allowsTable(String tableName) {
        return FormConstants.isIdentifier(tableName)
                && allowedTables != null
                && allowedTables.contains(tableName)
                && !Pattern.compile(tableExcludePattern, Pattern.CASE_INSENSITIVE)
                        .matcher(tableName)
                        .matches();
    }
}
