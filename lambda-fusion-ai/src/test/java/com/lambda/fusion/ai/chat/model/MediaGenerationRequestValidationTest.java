package com.lambda.fusion.ai.chat.model;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class MediaGenerationRequestValidationTest {

    @Test
    void shouldAcceptTheThreeUiMediaKinds() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            for (String kind : new String[] {"image", "video", "audio"}) {
                MediaGenerationRequest request = new MediaGenerationRequest();
                request.setKind(kind);
                assertThat(factory.getValidator().validate(request)).isEmpty();
            }
        }
    }

    @Test
    void shouldRejectMissingOrUnknownMediaKind() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            MediaGenerationRequest missing = new MediaGenerationRequest();
            MediaGenerationRequest unknown = new MediaGenerationRequest();
            unknown.setKind("music");

            assertThat(factory.getValidator().validate(missing))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("kind");
            assertThat(factory.getValidator().validate(unknown))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("kind");
        }
    }
}
