package com.lambda.fusion.workflow.exception;

import org.dromara.warm.flow.core.exception.FlowException;

/**
 * 引擎调用异常翻译：引擎侧 FlowException → 模块 16140 业务异常
 *
 * <p>引擎所有 service 调用统一经 {@link #call(Supplier)} 包裹，保证对外只抛
 * 模块自己的 {@link WorkflowBusinessException}，错误码语义稳定、不向下游泄露引擎实现。
 *
 * @author jin
 */
public final class EngineCalls {

    private EngineCalls() {}

    /**
     * 执行引擎调用并翻译异常
     */
    public static <T> T call(EngineSupplier<T> call) {
        try {
            return call.get();
        } catch (FlowException e) {
            throw new WorkflowBusinessException(WorkflowErrorCode.OPERATION_REJECTED, e.getMessage());
        }
    }

    /**
     * 无返回值的引擎调用
     */
    public static void run(Runnable call) {
        call(() -> {
            call.run();
            return null;
        });
    }

    /**
     * 允许返回 null 的 Supplier（java.util.function.Supplier 语义一致，命名仅为与 run 对仗）
     */
    @FunctionalInterface
    public interface EngineSupplier<T> {

        T get();
    }
}
