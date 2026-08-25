package com.lambda.fusion.ai.chat.model;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class MediaGenerationRequestValidationTest {

    @Test
    void shouldLeavePositiveProviderSpecificValuesToAgentScopeAndTheUpstreamProvider() {
        MediaGenerationRequest request = new MediaGenerationRequest();
        request.setKind("video");
        request.setWidth(11);
        request.setHeight(111);
        request.setDuration(5);
        request.setNegativePrompt("111");
        request.setShotType("single");
        request.setPromptExtend(Boolean.TRUE);
        request.setSeed(111);

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request)).isEmpty();
        }
    }

    @Test
    void shouldStillRejectNonPositivePlatformValues() {
        MediaGenerationRequest request = new MediaGenerationRequest();
        request.setKind("video");
        request.setWidth(0);
        request.setHeight(111);

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request))
                    .extracting((violation) -> violation.getPropertyPath().toString())
                    .containsExactly("width");
        }
    }
}
