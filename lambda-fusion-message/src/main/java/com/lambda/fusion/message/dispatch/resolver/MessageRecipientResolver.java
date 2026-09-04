package com.lambda.fusion.message.dispatch.resolver;

/**
 * 公告可见范围解析 SPI：按范围类型判定用户是否命中范围（读扩散，读取时解析）
 * <p>
 * 每个范围类型一个实现（all / user，org / role 随 P2 接入 authority 数据后补充），
 * 公告可见性判定统一走本 SPI，避免出现第二套解析逻辑。
 *
 * @author jin
 */
public interface MessageRecipientResolver {

    /**
     * 范围类型
     *
     * @return 范围类型标识（如 all / user）
     */
    String scopeType();

    /**
     * 判定用户是否命中范围
     *
     * @param scopeTargets 范围目标 JSON 数组（all 类型为 null）
     * @param username     用户名
     * @return 是否命中
     */
    boolean contains(String scopeTargets, String username);
}
