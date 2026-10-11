package com.lambda.fusion.ai.runtime.operator;

import static org.assertj.core.api.Assertions.assertThat;

import com.lambda.autoconfig.datascope.DataScopeProperties;
import com.lambda.cloud.core.principal.LoginUser;
import com.lambda.cloud.mybatis.datascope.DataScopeEvaluator;
import com.lambda.cloud.mybatis.datascope.DataScopePropertiesHolder;
import com.lambda.cloud.mybatis.datascope.context.DataScopeContext;
import com.lambda.fusion.core.identity.UserDetails;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.junit.jupiter.api.Test;

/**
 * run-as PoC（命题 B 切片）：form 引擎手工数据权限标记在拦截器链路上的解析与改写行为。
 *
 * <p>覆盖 {@code DataScopeEvaluator} 的静态语义：标记识别（{@code getReplace}）、
 * {@code 'permissions|{type}'} 解析（{@code parseReplaceDataScope}，form 引擎
 * {@code dataScopeFilter} 的原生支持路径）、owner 替换（{@code modifySqlForOwner}）、
 * operator 解析通道（Mapper 参数 LoginUser 优先 → Sa-Token 回退）与授权子查询组装。
 *
 * @author zx
 */
class DataScopeMarkerPocTest {

    private static final String MARKER_SQL =
            "SELECT id, name FROM la_demo WHERE status = 0 AND scope_column IN ('permissions|0')";

    @Test
    void formMarkerIsDetectedAndParsed() {
        assertThat(DataScopeEvaluator.getReplace(MARKER_SQL)).isTrue();
        DataScopeContext context = DataScopeEvaluator.parseReplaceDataScope(MARKER_SQL);
        assertThat(context).isNotNull();
        assertThat(context.getType()).containsExactly(0);
        assertThat(context.isReplace()).isTrue();
    }

    @Test
    void plainSqlIsIgnored() {
        assertThat(DataScopeEvaluator.getReplace("SELECT id FROM la_demo WHERE status = 0"))
                .isFalse();
        assertThat(DataScopeEvaluator.parseReplaceDataScope("SELECT id FROM la_demo WHERE status = 0"))
                .isNull();
    }

    /** owner（superAdminIdentifiers 命中）→ 标记替换为恒真条件。 */
    @Test
    void ownerMarkerIsReplacedWithTrueCondition() {
        String rewritten = DataScopeEvaluator.modifySqlForOwner(MARKER_SQL);
        assertThat(rewritten).contains("1 = 1").doesNotContain("permissions|");
    }

    /** operator 解析：Mapper 参数中的 LoginUser 优先；无参数且无上下文（headless）→ null → 拦截器跳过改写。 */
    @Test
    void operatorComesFromParamMapFirstThenSessionFallback() {
        UserDetails operator = new UserDetails();
        operator.setUsername("tester");
        ParamMap<Object> withOperator = new ParamMap<>();
        withOperator.put("p1", operator);
        assertThat(DataScopeEvaluator.getOperator(withOperator)).isSameAs(operator);
        assertThat(DataScopeEvaluator.getOperator(new ParamMap<>())).isNull();
    }

    /** owner 识别与授权子查询组装：superAdmin 命中 → owner；非 owner → 按操作人 username/orgId 过滤 la_datascopes。 */
    @Test
    void superAdminRecognizedAndSubqueryBuiltForNonOwner() {
        DataScopePropertiesHolder.initialize(new DataScopeProperties());
        UserDetails admin = new UserDetails();
        admin.setUsername("admin");
        UserDetails tester = new UserDetails();
        tester.setUsername("tester");
        tester.setOrgId("org-1");
        assertThat(DataScopeEvaluator.isOwner(admin)).isTrue();
        assertThat(DataScopeEvaluator.isOwner(tester)).isFalse();

        DataScopeContext context = DataScopeEvaluator.parseReplaceDataScope(MARKER_SQL);
        String scopeSql = DataScopeEvaluator.buildStrategyScopeSql(context, (LoginUser) tester);
        assertThat(scopeSql)
                .contains("SELECT DISTINCT id FROM la_datascopes")
                .contains("target_type = 'USER'")
                .contains("tid = 'tester'")
                .contains("target_type = 'ORG'")
                .contains("tid = 'org-1'")
                .contains("domain_type = 0");
    }
}
