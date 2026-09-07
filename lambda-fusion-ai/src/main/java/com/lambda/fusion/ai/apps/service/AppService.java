package com.lambda.fusion.ai.apps.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.fusion.ai.apps.model.AppPageQuery;
import com.lambda.fusion.ai.apps.model.AvailableApp;
import com.lambda.fusion.ai.apps.model.CreateApp;
import com.lambda.fusion.ai.apps.model.UpdateApp;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import java.util.List;

public interface AppService {

    Page<AppEntity> page(AppPageQuery query);

    AppEntity get(String id);

    AppEntity create(CreateApp dto);

    void update(String id, UpdateApp dto);

    void delete(String id);

    /**
     * 按主键加载（含禁用记录），全局；不存在抛出业务异常。供运行时使用。
     */
    AppEntity loadById(String id);

    /**
     * 列出当前用户可见的应用（平台应用按 audience+角色；独立应用按 owner）。
     *
     * @return 可见应用列表
     */
    List<AppEntity> listAvailable();

    /**
     * 列出当前用户可见应用的安全视图（剔除内部运行配置），供普通聊天页与发布 access 使用。
     *
     * @return 可见应用安全视图列表
     */
    List<AvailableApp> listAvailableView();

    /**
     * 加载并校验当前用户对应用可见；不可见或不存在抛 APP_NOT_FOUND。供建会话等校验。
     *
     * @param appId 应用ID
     * @return 应用实体
     */
    AppEntity loadAvailable(String appId);

    /**
     * 加载当前用户可访问应用的安全视图，并回填绑定模型的视觉能力。
     *
     * @param appId 应用ID
     * @return 应用安全视图
     */
    AvailableApp loadAvailableView(String appId);

    /** 应用实体转聊天安全视图（可见性/展示事实的唯一转换点，剔内部配置）。 */
    static AvailableApp toAvailableView(AppEntity app) {
        AvailableApp view = new AvailableApp();
        view.setId(app.getId());
        view.setName(app.getName());
        view.setAvatar(app.getAvatar());
        view.setDescription(app.getDescription());
        view.setAppType(app.getAppType());
        view.setSupportsVision(app.getSupportsVision());
        view.setEnableImageUpload(app.getEnableImageUpload());
        view.setEnableFileUpload(app.getEnableFileUpload());
        return view;
    }
}
