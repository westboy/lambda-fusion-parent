package com.lambda.fusion.authority;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "lambda.fusion.authorize")
@SuppressFBWarnings("EI_EXPOSE_REP")
public class AuthorityProperties {
    /**
     * 组织名称作为 id 使用并入库
     */
    private boolean useOrgNameAsId = false;

    /**
     * 密码策略
     */
    private PasswordStrategy passwordStrategy = new PasswordStrategy();

    /**
     * 三分登录配置
     */
    private ThirdPartConfig thirdPart = new ThirdPartConfig();

    @Data
    public static class ThirdPartConfig {

        /**
         * 是否启用第三方登录
         */
        private boolean enabled = false;

        /**
         * 是否允许第三方登录自动注册
         */
        private boolean autoRegister = false;

        /**
         * 允许第三方登录自动注册的登录类型
         */
        private List<String> autoRegisterLoginTypes = new ArrayList<>();

        private WxMaConfig wxMa = new WxMaConfig();
        private WxOpenConfig wxOpen = new WxOpenConfig();
        private AlipayMaConfig alipayMa = new AlipayMaConfig();
        private DingTalkConfig dingTalk = new DingTalkConfig();

        @Data
        public static class WxMaConfig {
            private String appId;
            private String appSecret;
            private String redirectUri = "http://localhost:8080/authorize";
        }

        @Data
        public static class AlipayMaConfig {
            private String appId;
            private String privateKey;
            private String appCertPath;
            private String alipayPublicCertPath;
            private String rootCertPath;
        }

        @Data
        public static class DingTalkConfig {
            private String appId;
            private String appSecret;
            private String redirectUri = "http://localhost:8080/authorize";
        }

        @Data
        public static class WxOpenConfig {
            private String appId;
            private String appSecret;
            private String redirectUri = "http://localhost:8080/authorize";
        }
    }

    /**
     * 密码策略
     */
    @Data
    public static class PasswordStrategy {
        /**
         * 密码策略模式
         */
        Mode mode = Mode.RANDOM;
        /**
         * 当密码策略为固定值时生效
         */
        String customize = "123456";

        /**
         * 启用密码有效期
         */
        private Boolean enablePeriodChange = false;

        /**
         * 密码有效期天数 默认 90 天
         */
        private Integer periodChangeDays = 90;

        /**
         * 密码即将过期的提前提醒天数 默认 7 天（仅在提醒窗口内向前端返回剩余天数）
         */
        private Integer remindDays = 7;

        /**
         * 密码策略模式
         */
        public enum Mode {
            /**
             * 固定值
             */
            FIXED,
            /**
             * 随机值
             */
            RANDOM,
            /**
             * 加密值
             */
            CIPHERTEXT
        }
    }
}
