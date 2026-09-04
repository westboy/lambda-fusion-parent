package com.lambda.fusion.message.dispatch.resolver;

/**
 * 联系方式解析 SPI：username → 渠道所需联系方式（手机号/邮箱）
 * <p>
 * 默认实现经 lambda-fusion-authority-api 的 RemoteUserService 获取；渠道实现按 type 取用，
 * 解析器缺失（authority-api 不在 classpath 或 Dubbo 不可用）时由渠道自行降级并记录投递失败。
 *
 * @author jin
 */
public interface MessageContactResolver {

    /**
     * 联系方式类型
     *
     * @return 类型标识（mobile / email）
     */
    String type();

    /**
     * 解析用户联系方式
     *
     * @param username 用户名
     * @return 联系方式；无法解析时返回 null
     */
    String resolve(String username);
}
