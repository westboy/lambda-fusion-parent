package com.lambda.fusion.ai.apps.model;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class AppRequestValidationTest {

    @Test
    void shouldRejectNonPositiveMaxItersWhenCreatingApp() {
        CreateApp request = new CreateApp();
        request.setName("demo");
        request.setModelId("model-1");
        request.setMaxIters(0);

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("maxIters");
        }
    }

    @Test
    void shouldRejectNonPositiveMaxItersWhenUpdatingApp() {
        UpdateApp request = new UpdateApp();
        request.setMaxIters(0);

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("maxIters");
        }
    }
}
