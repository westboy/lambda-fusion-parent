package com.lambda.fusion.ai;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class AiPropertiesValidationTest {

    @Test
    void shouldRejectNonPositiveDefaultMaxIters() {
        AiProperties properties = new AiProperties();
        properties.getRuntime().setDefaultMaxIters(0);

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("runtime.defaultMaxIters");
        }
    }

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

    @Test
    void shouldRejectUnknownDefaultRetrievalMode() {
        AiProperties properties = new AiProperties();
        properties.getRag().setDefaultRetrievalMode("ELASTICSEARCH");

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("rag.defaultRetrievalModeValid");
        }
    }

    @Test
    void shouldRequireCredentialsWhenQwenImageToolIsEnabled() {
        AiProperties properties = new AiProperties();
        properties.getTools().getQwenImage().setEnabled(true);
        properties.getTools().getQwenImage().setModelName("qwen-image-plus");

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("tools.qwenImage.configurationValid");
        }
    }

    @Test
    void shouldRequireModelNameWhenQwenImageToolIsEnabled() {
        AiProperties properties = new AiProperties();
        properties.getTools().getQwenImage().setEnabled(true);
        properties.getTools().getQwenImage().setApiKey("test-key");
        properties.getTools().getQwenImage().setBaseUrl("https://dashscope.test/api/v1");

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("tools.qwenImage.configurationValid");
        }
    }

    @Test
    void shouldAcceptIndependentQwenImageToolConfiguration() {
        AiProperties properties = new AiProperties();
        properties.getTools().getQwenImage().setEnabled(true);
        properties.getTools().getQwenImage().setApiKey("test-key");
        properties.getTools().getQwenImage().setBaseUrl("https://dashscope.test/api/v1");
        properties.getTools().getQwenImage().setModelName("qwen-image-plus");

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties)).isEmpty();
        }
    }

    @Test
    void shouldValidateQwenImageHttpTimeouts() {
        AiProperties properties = new AiProperties();
        properties.getTools().getQwenImage().setConnectTimeoutSeconds(0);
        properties.getTools().getQwenImage().setRequestTimeoutSeconds(1801);

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("tools.qwenImage.connectTimeoutSeconds", "tools.qwenImage.requestTimeoutSeconds");
        }
    }
}
