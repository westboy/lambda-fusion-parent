package com.lambda.fusion.form.design.service;

import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.form.design.model.FormPreviewSource;
import com.lambda.fusion.form.runtime.model.FormDataPageQuery;
import com.lambda.fusion.form.runtime.model.FormSchemaView;
import java.util.Map;

/** 预览使用选定配置读写真实业务数据，不自动保存设计或改变发布指针。 */
public interface FormPreviewService {
    FormSchemaView preview(FormPreviewSource source);

    FormSchemaView previewDraft(String id);

    FormSchemaView previewVersion(String id, long version);

    PageView<Map<String, Object>> page(FormPreviewSource source, FormDataPageQuery query);

    Map<String, Object> detail(FormPreviewSource source, String id);

    String create(FormPreviewSource source, Map<String, Object> data);

    void update(FormPreviewSource source, String id, Map<String, Object> data);

    void delete(FormPreviewSource source, String id);
}
