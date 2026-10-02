package com.lambda.fusion.form.design.service;

import com.lambda.fusion.form.design.model.FormRouteCommand;
import com.lambda.fusion.form.design.model.FormRouteInfo;
import java.util.List;
import java.util.Map;

public interface FormRouteService {
    Map<String, FormRouteInfo> states(List<String> ids);

    FormRouteInfo detail(String id);

    FormRouteInfo save(String id, FormRouteCommand command);

    void remove(String id);
}
