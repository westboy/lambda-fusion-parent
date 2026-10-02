package com.lambda.fusion.form.design.service;

import com.lambda.fusion.form.design.model.FormVersionView;
import java.util.List;

/**
 * 表单发布服务（校验 → 快照 → 指针；停用/回滚）
 *
 * @author Zx
 */
public interface FormPublishService {

    /**
     * 发布草稿：发布校验 → 写不可变快照 → 拨 published_version 指针
     */
    void publish(String id);

    /**
     * 停用（运行时拒绝该表单全部数据请求）
     */
    void disable(String id);

    /** 启用当前生效快照，不创建新版本。 */
    void enable(String id);

    /**
     * 回滚到历史快照（published_version 指针回指）
     */
    void rollback(String id, Long version);

    /**
     * 版本历史列表
     */
    List<FormVersionView> versions(String id);
}
