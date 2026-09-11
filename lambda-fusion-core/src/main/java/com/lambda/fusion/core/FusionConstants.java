package com.lambda.fusion.core;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.lambda.fusion.core.annotation.DictMapper;
import com.lambda.fusion.core.dict.DictEnum;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

/**
 * 常量定义类
 * 集中管理系统中使用的所有常量，按功能模块分组组织
 *
 * @author Jin
 */
@SuppressWarnings("unused")
public interface FusionConstants {

    // ========== 系统基础常量 ==========
    /**
     * 启用
     **/
    Integer ENABLED = 1;
    /**
     * 停用
     **/
    Integer DISABLED = 0;
    /**
     * 系统标识
     */
    String EXCLUDES = "excludes";
    /**
     * 设备终端
     */
    String DEVICE_DEFAULT = "default";
    /**
     * 租户ID 字段名
     */
    String TENANT_ID = "tenantId";
    /**
     * 租户域名 Redis Key
     */
    String TENANT_HOST_REDIS_KEY = "tenant_host";
    /**
     * 日期时间格式
     */
    String DATE_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss";

    // ========== 字符串分隔符常量 ==========
    /**
     * 分隔符0
     */
    String SEPARATOR0 = "-";
    /**
     * 模糊查询通配符
     */
    String FUZZY = "%";
    /**
     * 逗号分隔符
     */
    String DELIMITER = ",";
    /**
     * 分号分隔符
     */
    String SEMICOLON = ";";
    /**
     * 空字符串
     */
    String EMPTY = StringUtils.EMPTY;
    /**
     * 空格
     */
    String SPACE = " ";
    /**
     * 点号
     */
    String DOT = ".";
    /**
     * 连接符
     */
    String JOINER = "-";
    /**
     * @ 符号
     */
    String AT = "@";
    /**
     * 正斜杠
     */
    String SLASH = "/";
    /**
     * 反斜杠
     */
    String BACK_SLASH = "\\";
    /**
     * 冒号
     */
    String COLON = ":";
    /**
     * 下划线
     */
    String UNDERSCORE = "_";
    /**
     * 井号
     */
    String ANCHOR = "#";
    /**
     * 通配符
     */
    String ALL = "*";
    /**
     * 通配符路径
     */
    String ALL_PATH = "/**";

    // ========== HTTP相关常量 ==========
    /**
     * Bearer 认证前缀
     */
    String BEARER = "Bearer ";
    /**
     * HMAC 算法前缀
     */
    String HMAC = "HmacSHA ";
    /**
     * Content-Type 请求头
     */
    String CONTENT_TYPE = "Content-Type";
    /**
     * JSON Content-Type 值
     */
    String CONTENT_TYPE_VALUE = "application/json";
    /**
     * XMLHttpRequest 请求头
     */
    String XML_HTTP_REQUEST = "x-requested-with";
    /**
     * XMLHttpRequest 请求头值
     */
    String XML_HTTP_REQUEST_VALUE = "XMLHttpRequest";
    /**
     * Authorization 请求头
     */
    String AUTHORIZATION = "Authorization";
    /**
     * X-Content-Type 请求头
     */
    String X_CONTENT_TYPE = "X-Content-Type";
    /**
     * 自定义认证Token请求头
     */
    String X_AUTHORIZED_TOKEN = "x-authorized-token";
    /**
     * 自定义认证Bearer请求头
     */
    String X_AUTHORIZED_BEARER = X_AUTHORIZED_TOKEN;
    /**
     * 安全策略请求头
     */
    String X_SECURITY_POLICY = "x-security-policy";
    /**
     * 清除安全策略请求头值
     */
    String X_SECURITY_POLICY_CLEAR = "x-security-policy=clear";
    /**
     * HTTP方法覆盖请求头
     */
    String X_HTTP_METHOD_OVERRIDE = "X-HTTP-Method-Override";
    /**
     * Token无效标识
     */
    String TOKEN_INVALID = "x-token-invalid";
    /**
     * UTF-8 字符集
     */
    Charset UTF_8 = StandardCharsets.UTF_8;
    /**
     * UTF-8 字符集名称
     */
    String UTF_8_VALUE = UTF_8.name();

    // ========== 会话相关常量 ==========
    /**
     * 会话标识
     */
    String SESSION = "SESSION";
    /**
     * JSESSIONID
     */
    String JSESSIONID = "JSESSIONID";
    /**
     * 登录时间键
     */
    String LOGIN_TIME = "__login-time";
    /**
     * 登录用户名键
     */
    String LOGIN_USERNAME = "__login-username";
    /**
     * 重定向URL键
     */
    String REDIRECT_URL = "__redirectUrl";
    /**
     * 失败页面路径
     */
    String FAILURE_PAGE = "#/errors/401";

    // ========== 角色权限常量 ==========
    /**
     * 角色前缀
     */
    String ROLE_PREFIX = "ROLE_";
    /**
     * 开发者角色
     */
    String ROLE_DEV = ROLE_PREFIX + "DEV";
    /**
     * 管理员角色
     */
    String ROLE_ADMIN = ROLE_PREFIX + "ADMIN";
    /**
     * 普通用户角色
     */
    String ROLE_USER = ROLE_PREFIX + "USER";
    /**
     * 机构角色
     */
    String ROLE_ORG = ROLE_PREFIX + "ORG";
    /**
     * HMAC角色
     */
    String ROLE_HMAC = ROLE_PREFIX + "HMAC";
    /**
     * 租户角色
     */
    String ROLE_TENANT = ROLE_PREFIX + "TENANT";
    /**
     * 管理者角色
     */
    String ROLE_MANAGER = ROLE_PREFIX + "MANAGER";
    /**
     * 系统角色
     */
    String ROLE_SYSTEM = ROLE_PREFIX + "SYSTEM";

    // ========== 树形结构相关常量 ==========
    /**
     * 树结构顶级节点标识
     */
    String TREE_TOP_LEVEL = "";
    /**
     * 树结构分隔符
     */
    String TREE_SPLIT = "-";

    /**
     * 前端租户表示
     */
    String TENANT_ID_HEADER = "X-Tenant-Id";

    @Getter
    @DictMapper(dictName = "ACTIVE_STATUS", dictUsage = 0, dictDesc = "启停状态")
    @AllArgsConstructor
    enum ActiveStatus implements DictEnum<Integer> {
        ENABLED(1, "启用"),
        DISABLED(0, "停用");

        @EnumValue
        @JsonValue
        private final Integer code;

        private final String label;

        public boolean isEnabled() {
            return FusionConstants.ENABLED.equals(getCode());
        }

        public boolean isDisabled() {
            return FusionConstants.DISABLED.equals(getCode());
        }
    }
}
