package com.lambda.fusion.form;

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

    /** 无 Dubbo 时元数据缓存 TTL（秒）降级 */
    private long cacheTtlSeconds = 300;

    /** 查询条件数上限 */
    private int maxConditionCount = 20;

    /** 树子孙节点 id 集上限 */
    private int maxTreeDescendants = 500;

    /** 选表导列与发布校验的表名排除正则（元数据表自身与 Liquibase 系统表默认不可映射） */
    private String tableExcludePattern = "la_form_.*|databasechangelog.*|qrtz_.*";

    /** 表名排除正则编译缓存 */
    public Pattern tableExclude() {
        return Pattern.compile(tableExcludePattern, Pattern.CASE_INSENSITIVE);
    }
}
