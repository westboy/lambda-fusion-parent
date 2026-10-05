package com.lambda.fusion.form.runtime.integration;

import com.lambda.fusion.form.runtime.support.FormModel;
import java.util.Map;

/**
 * 表单数据写入拦截器 SPI：非标业务逻辑（联动校验、写后通知、流程编排）的统一挂点
 *
 * <p>下游以 Spring bean 提供实现，表单定义按 bean 名绑定（{@code la_form_def.INTERCEPTOR}，
 * 随发布快照生效）；未配置即空操作。全部方法在数据写事务内同步执行，抛异常即回滚。
 *
 * @author Zx
 */
public interface FormDataInterceptor {

    /** 设计器下拉展示名 */
    String getName();

    /** 新增写入前（data 为客户端原始提交体，可就地补充派生值） */
    default void beforeCreate(FormModel model, Map<String, Object> data) {}

    /** 新增写入后（主行与子表均已落库） */
    default void afterCreate(FormModel model, Object id, Map<String, Object> data) {}

    /** 更新写入前 */
    default void beforeUpdate(FormModel model, Object id, Map<String, Object> data) {}

    /** 更新写入后 */
    default void afterUpdate(FormModel model, Object id, Map<String, Object> data) {}

    /** 删除前（级联校验已通过） */
    default void beforeDelete(FormModel model, Object id) {}

    /** 删除后 */
    default void afterDelete(FormModel model, Object id) {}
}
