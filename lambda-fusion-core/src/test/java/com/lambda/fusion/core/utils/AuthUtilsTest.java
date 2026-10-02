package com.lambda.fusion.core.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.stp.StpLogic;
import com.lambda.cloud.core.utils.StpLogicUtils;
import com.lambda.fusion.core.FusionConstants;
import org.junit.jupiter.api.Test;

class AuthUtilsTest {
    @Test
    void developerExemptionAlsoAppliesToInternalCallsAndPermissionPresentation() {
        StpLogic logic = mock(StpLogic.class);
        when(logic.hasRole(FusionConstants.ROLE_DEV)).thenReturn(true);
        try (var active = mockStatic(StpLogicUtils.class)) {
            active.when(StpLogicUtils::getActiveStpLogic).thenReturn(logic);
            AuthUtils.checkPermission("form:design:publish");
            assertThat(AuthUtils.hasPermission("form:example:add")).isTrue();
            verify(logic, never()).checkPermission("form:design:publish");
            verify(logic, never()).hasPermission("form:example:add");
        }
    }

    @Test
    void ordinaryUsersAreCheckedByTheirActiveLoginLogic() {
        StpLogic logic = mock(StpLogic.class);
        org.mockito.Mockito.doThrow(new NotPermissionException("form:design:publish"))
                .when(logic)
                .checkPermission("form:design:publish");
        try (var active = mockStatic(StpLogicUtils.class)) {
            active.when(StpLogicUtils::getActiveStpLogic).thenReturn(logic);
            assertThatThrownBy(() -> AuthUtils.checkPermission("form:design:publish"))
                    .isInstanceOf(NotPermissionException.class);
            assertThat(AuthUtils.hasPermission("form:example:add")).isFalse();
            when(logic.hasPermission("form:example:add")).thenReturn(true);
            assertThat(AuthUtils.hasPermission("form:example:add")).isTrue();
        }
    }
}
