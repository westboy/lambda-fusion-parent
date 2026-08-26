package com.lambda.fusion.core.utils;

import com.lambda.cloud.mybatis.tenant.TenantContextHolder;
import java.util.concurrent.Callable;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

/**
 * 多租户上下文工具。
 *
 * <p>用于从领域对象恢复当前线程租户上下文，并在任务结束后清理/恢复上下文。
 *
 * @author Jin
 */
@UtilityClass
public class TenantUtils {

    /**
     * 在指定租户上下文中执行任务。
     *
     * @param tenantId 目标租户标识；为空时清理当前租户上下文
     * @param task 待执行任务
     */
    public static void withTenant(String tenantId, Runnable task) {
        withTenant(tenantId, () -> {
            task.run();
            return null;
        });
    }

    /**
     * 在指定租户上下文中执行任务。
     *
     * @param tenantId 目标租户标识；为空时清理当前租户上下文
     * @param task 待执行任务
     * @param <T> 返回值类型
     * @return 任务返回值
     * @throws IllegalStateException 任务抛出受检异常
     */
    public static <T> T withTenant(String tenantId, Callable<T> task) {
        String previous = TenantContextHolder.getCurrentTenantId();
        try {
            if (StringUtils.isBlank(tenantId) || "default".equals(tenantId)) {
                TenantContextHolder.getInstance().close();
            } else {
                TenantContextHolder.getInstance().setTenantId(tenantId);
            }
            return task.call();
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Exception exception) {
            throw new IllegalStateException("恢复租户上下文失败", exception);
        } finally {
            TenantContextHolder.getInstance().close();
            if (StringUtils.isNotBlank(previous)) {
                TenantContextHolder.getInstance().setTenantId(previous);
            }
        }
    }
}
