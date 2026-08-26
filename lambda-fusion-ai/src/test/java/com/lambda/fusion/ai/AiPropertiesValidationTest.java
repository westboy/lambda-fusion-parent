package com.lambda.fusion.ai;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class AiPropertiesValidationTest {

    @Test
    void shouldCascadeValidationIntoChatAttachmentLimits() {
        AiProperties properties = new AiProperties();
        properties.getChat().getAttachment().setMaxInlineChars(0);
        properties.getChat().getAttachment().setMaxToolReadChars(100);

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("chat.attachment.maxInlineChars", "chat.attachment.maxToolReadChars");
        }
    }
}
