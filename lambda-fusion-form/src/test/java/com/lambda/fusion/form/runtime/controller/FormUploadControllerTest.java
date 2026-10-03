package com.lambda.fusion.form.runtime.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.upload.model.AttachmentView;
import com.lambda.fusion.upload.service.AttachmentService;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

/** 上传端点权限入口与分组归置：any-of 权限、design/form 分组、非法标识拒绝。 */
class FormUploadControllerTest {

    private final AttachmentService attachmentService = mock(AttachmentService.class);
    private final FormUploadController controller = new FormUploadController(attachmentService);
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
    void designPreviewUploadsToDesignGroupWithoutFormKey() {
        auth.when(() -> AuthUtils.hasPermission("form:design:preview")).thenReturn(true);

        Map<String, String> result = controller.upload(file, null);

        assertThat(result).containsEntry("id", "att-1").containsEntry("url", "https://oss/report.pdf");
        verify(attachmentService).upload(eq(file), eq("form-design"), isNull());
    }

    @Test
    void formKeyRoutesToFormGroupWithAddPermission() {
        auth.when(() -> AuthUtils.hasPermission("form:design:preview")).thenReturn(false);
        auth.when(() -> AuthUtils.hasPermission("form:leave_demo:add")).thenReturn(true);

        controller.upload(file, "leave_demo");

        verify(attachmentService).upload(eq(file), eq("form-leave_demo"), isNull());
    }

    @Test
    void editPermissionAlsoAllowsUpload() {
        auth.when(() -> AuthUtils.hasPermission("form:design:preview")).thenReturn(false);
        auth.when(() -> AuthUtils.hasPermission("form:leave_demo:add")).thenReturn(false);
        auth.when(() -> AuthUtils.hasPermission("form:leave_demo:edit")).thenReturn(true);

        controller.upload(file, "leave_demo");

        verify(attachmentService).upload(eq(file), eq("form-leave_demo"), isNull());
    }

    @Test
    void rejectsWhenNoPermissionMatches() {
        auth.when(() -> AuthUtils.hasPermission("form:design:preview")).thenReturn(false);
        auth.when(() -> AuthUtils.hasPermission("form:leave_demo:add")).thenReturn(false);
        auth.when(() -> AuthUtils.hasPermission("form:leave_demo:edit")).thenReturn(false);
        auth.when(() -> AuthUtils.checkPermission("form:leave_demo:add")).thenThrow(new RuntimeException("denied"));

        assertThatThrownBy(() -> controller.upload(file, "leave_demo"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("denied");
        Mockito.verifyNoInteractions(attachmentService);
    }

    @Test
    void rejectsIllegalFormKeyBeforeTouchingStorage() {
        assertThatThrownBy(() -> controller.upload(file, "bad key!"))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.INVALID_PARAMETER.getCode()));
        Mockito.verifyNoInteractions(attachmentService);
    }
}
