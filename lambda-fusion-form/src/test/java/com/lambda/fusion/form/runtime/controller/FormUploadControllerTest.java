package com.lambda.fusion.form.runtime.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.form.FormProperties;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.support.FormModel;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import com.lambda.fusion.upload.model.AttachmentView;
import com.lambda.fusion.upload.service.AttachmentService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

/** 上传入口校验：表单存在性与 FILE 字段、权限分支（预览权限不得越权业务表单）、大小与扩展名白名单。 */
class FormUploadControllerTest {

    private final AttachmentService attachmentService = mock(AttachmentService.class);
    private final FormModelProvider models = mock(FormModelProvider.class);
    private final FormUploadController controller =
            new FormUploadController(attachmentService, models, new FormProperties());
    private MockedStatic<AuthUtils> auth;

    private final MockMultipartFile file =
            new MockMultipartFile("file", "report.pdf", "application/pdf", new byte[] {1, 2, 3});

    @BeforeEach
    void setUp() {
        auth = mockStatic(AuthUtils.class);
        when(attachmentService.upload(any(), any(), any())).thenReturn(view());
    }

    @AfterEach
    void tearDown() {
        auth.close();
    }

    private AttachmentView view() {
        AttachmentView view = new AttachmentView();
        view.setId("att-1");
        view.setFileName("report.pdf");
        view.setFileUrl("https://oss/report.pdf");
        return view;
    }

    @Test
    void designPreviewUploadWithoutFormKeyRequiresPreviewPermission() {
        auth.when(() -> AuthUtils.checkPermission("form:design:preview")).thenThrow(new RuntimeException("denied"));
        assertThatThrownBy(() -> controller.upload(file, null)).isInstanceOf(RuntimeException.class);
        Mockito.verifyNoInteractions(attachmentService);

        auth.reset();
        Map<String, String> result = controller.upload(file, null);
        assertThat(result).containsEntry("id", "att-1");
        // 不归置业务分组（form-xxx 分组的 snowflake 主键机制从未生效）
        org.mockito.Mockito.verify(attachmentService).upload(any(), isNull(), isNull());
    }

    @Test
    void formKeyRequiresPublishedFormWithFileFieldAndAddOrEditPermission() {
        when(models.require("leave_demo")).thenReturn(model(false));
        assertThatThrownBy(() -> controller.upload(file, "leave_demo"))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.INVALID_PARAMETER.getCode()));
        Mockito.verifyNoInteractions(attachmentService);

        when(models.require("leave_demo")).thenReturn(model(true));
        auth.when(() -> AuthUtils.hasPermission("form:leave_demo:add")).thenReturn(false);
        auth.when(() -> AuthUtils.hasPermission("form:leave_demo:edit")).thenReturn(false);
        auth.when(() -> AuthUtils.checkPermission("form:leave_demo:add")).thenThrow(new RuntimeException("denied"));
        assertThatThrownBy(() -> controller.upload(file, "leave_demo")).isInstanceOf(RuntimeException.class);
        Mockito.verifyNoInteractions(attachmentService);

        auth.when(() -> AuthUtils.hasPermission("form:leave_demo:edit")).thenReturn(true);
        Map<String, String> result = controller.upload(file, "leave_demo");
        assertThat(result).containsEntry("url", "https://oss/report.pdf");
    }

    @Test
    void unpublishedFormKeyIsRejectedByModelProvider() {
        when(models.require("ghost")).thenThrow(new FormBusinessException(FormErrorCode.FORM_NOT_PUBLISHED, "ghost"));
        assertThatThrownBy(() -> controller.upload(file, "ghost")).isInstanceOf(FormBusinessException.class);
        Mockito.verifyNoInteractions(attachmentService);
    }

    @Test
    void rejectsOversizedDisallowedExtensionAndIllegalFormKeyBeforeStorage() {
        MockMultipartFile oversized =
                new MockMultipartFile("file", "a.png", "image/png", new byte[(int) (11L * 1024 * 1024)]);
        assertThatThrownBy(() -> controller.upload(oversized, null))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.INVALID_PARAMETER.getCode()));
        MockMultipartFile exe = new MockMultipartFile("file", "a.exe", "application/octet-stream", new byte[1]);
        assertThatThrownBy(() -> controller.upload(exe, null)).isInstanceOf(FormBusinessException.class);
        assertThatThrownBy(() -> controller.upload(file, "bad key!")).isInstanceOf(FormBusinessException.class);
        Mockito.verifyNoInteractions(attachmentService);
    }

    private static FormModel model(boolean withFileField) {
        List<FormModel.FormField> fields = withFileField
                ? List.of(new FormModel.FormField(
                        "attachment",
                        "附件",
                        "ATTACHMENT",
                        "FILE",
                        true,
                        null,
                        null,
                        0,
                        true,
                        null,
                        new FormModel.QueryConf(false, "eq", null),
                        new FormModel.ListConf(true, null, null, null, null, false, null, 0),
                        new FormModel.FormConf(true, null, Map.of(), Map.of(), false)))
                : List.of();
        return new FormModel(
                "leave_demo",
                "请假",
                1L,
                "SINGLE",
                "la_demo_leave",
                null,
                null,
                new FormModel.ListConfig(List.of(), true, List.of()),
                FormModel.DetailConfig.DEFAULT,
                null,
                fields,
                List.of(),
                null,
                null);
    }
}
