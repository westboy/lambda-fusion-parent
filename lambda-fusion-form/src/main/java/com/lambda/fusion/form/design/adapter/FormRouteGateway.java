package com.lambda.fusion.form.design.adapter;

import com.lambda.fusion.form.design.entity.FormDefEntity;
import com.lambda.fusion.form.design.model.FormRouteCommand;
import com.lambda.fusion.form.design.model.FormRouteInfo;

/** 菜单管理集成边界；缺少提供方时设计、预览与版本发布仍可使用。 */
public interface FormRouteGateway {
    FormRouteInfo read(FormDefEntity form);

    String save(FormDefEntity form, FormRouteCommand command);

    void remove(FormDefEntity form);
}
