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
         * <p>CIPHERTEXT（默认）：使用前端传入的密码，表单输入即登录密码；未传时兜底 md5f2(customize)</p>
         * <p>RANDOM：忽略前端密码，存随机值（新增用户不返回密码，账号无人可登录，需慎用）</p>
         * <p>FIXED：忽略前端密码，所有用户登录密码固定为 md5f2(customize) 的十六进制串</p>
         */
        Mode mode = Mode.CIPHERTEXT;
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
         * 新增用户首次登录是否强制修改密码（可通过系统配置管理界面调整，public 配置全局生效）
         */
        private Boolean firstLoginChangePassword = true;

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
