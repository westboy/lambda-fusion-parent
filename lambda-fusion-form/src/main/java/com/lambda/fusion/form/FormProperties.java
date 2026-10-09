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

    /**
     * 选表导列与发布校验的表名排除正则（大小写不敏感、整名匹配）。默认排除平台内部表：
     * la_ 前缀（除 la_demo_ 演示表）覆盖平台各模块数据表（form/authority/datasource/message/
     * logger/oss/tenant…），ai_ 前缀覆盖 AI 模块，flow_ 前缀覆盖流程引擎，另排除 Liquibase
     * 与 Quartz 系统表。平台新增内部模块的数据表必须落入默认排除集（登记本注释或沿用约定前缀），
     * 否则可被动态表单映射写入；业务表请使用自有前缀（如 la_demo_*、crm_*）。
     */
    private String tableExcludePattern = "la_(?!demo_).*|ai_.*|flow_.*|databasechangelog.*|qrtz_.*";

    /**
     * 目标表是否可被动态表单映射（设计、发布与运行共同使用的边界）：
     * 标识合法且未命中排除正则。平台内部表一律保护，其余物理表开放。
     */
    public boolean isMappableTable(String tableName) {
        return FormConstants.isIdentifier(tableName)
                && !Pattern.compile(tableExcludePattern, Pattern.CASE_INSENSITIVE)
                        .matcher(tableName)
                        .matches();
    }
}
