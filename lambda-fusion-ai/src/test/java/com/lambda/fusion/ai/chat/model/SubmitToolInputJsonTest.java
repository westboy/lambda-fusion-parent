package com.lambda.fusion.ai.chat.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.NullNode;

/**
 * 复现前端提交报文的多选数组反序列化。
 *
 * @author Jin
 */
class SubmitToolInputJsonTest {

    @Test
    void shouldDeserializeMultiChoiceArrayValue() throws Exception {
        String body = "{\"inputs\":[{\"toolCallId\":\"call_00_CWbzjozmjBJ7OUy4nDdX5520\","
                + "\"value\":[\"优惠券\",\"积分\",\"会员体验卡\",\"实物礼品\"]}],\"phaseNo\":1}";
        SubmitToolInput dto = new ObjectMapper().readValue(body, SubmitToolInput.class);
        assertThat(dto.getPhaseNo()).isEqualTo(1);
        assertThat(dto.getInputs()).hasSize(1);
        assertThat(dto.getInputs().get(0).getValue().isArray()).isTrue();
        assertThat(dto.getInputs().get(0).getValue().size()).isEqualTo(4);
    }

    @Test
    void shouldDeserializeNullValueAsCancel() throws Exception {
        String body = "{\"inputs\":[{\"toolCallId\":\"call_1\",\"value\":null}],\"phaseNo\":1}";
        SubmitToolInput dto = new ObjectMapper().readValue(body, SubmitToolInput.class);
        assertThat(dto.getInputs().get(0).getValue()).isInstanceOf(NullNode.class);
    }
}
