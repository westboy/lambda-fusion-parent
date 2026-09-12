package com.lambda.fusion.ai.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lambda.autoconfig.CryptoProperties;
import com.lambda.cloud.crypto.algorithm.AlgorithmType;
import com.lambda.cloud.crypto.key.PropertiesKeyProvider;
import com.lambda.cloud.crypto.service.impl.HutoolSymmetricCryptoService;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

class CryptoKeyEncryptionServiceTest {

    private CryptoKeyEncryptionService newService(String base64Key) {
        CryptoProperties props = new CryptoProperties();
        if (base64Key != null) {
            CryptoProperties.KeyProperties key = new CryptoProperties.KeyProperties();
            key.setId("default");
            key.setType(AlgorithmType.AES);
            key.setSecretKey(base64Key);
            props.setKeys(List.of(key));
        }
        return new CryptoKeyEncryptionService(new HutoolSymmetricCryptoService(new PropertiesKeyProvider(props)));
    }

    @Test
    void encryptDecryptRoundTrip() {
        CryptoKeyEncryptionService svc = newService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        String plaintext = "sk-abcdef1234567890";
        String cipher = svc.encrypt(plaintext);
        assertThat(cipher).isNotEqualTo(plaintext).isNotBlank();
        assertThat(svc.decrypt(cipher)).isEqualTo(plaintext);
    }

    @Test
    void eachEncryptionProducesDifferentCiphertext() {
        CryptoKeyEncryptionService svc = newService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        String c1 = svc.encrypt("same-secret");
        String c2 = svc.encrypt("same-secret");
        assertThat(c1).isNotEqualTo(c2);
        assertThat(svc.decrypt(c1)).isEqualTo("same-secret");
        assertThat(svc.decrypt(c2)).isEqualTo("same-secret");
    }

    @Test
    void blankInputReturnsNull() {
        CryptoKeyEncryptionService svc = newService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        assertThat(svc.encrypt(null)).isNull();
        assertThat(svc.encrypt("")).isNull();
        assertThat(svc.decrypt(null)).isNull();
        assertThat(svc.decrypt("")).isNull();
    }

    @Test
    void encryptWithoutKeyThrowsNotConfigured() {
        CryptoKeyEncryptionService svc = newService(null);
        assertThatThrownBy(() -> svc.encrypt("sk-test"))
                .isInstanceOfSatisfying(AiBusinessException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AiErrorCode.LLM_ENCRYPTION_KEY_NOT_CONFIGURED.getCode()));
    }

    @Test
    void corruptCiphertextThrowsDecryptFailed() {
        CryptoKeyEncryptionService svc = newService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        assertThatThrownBy(() -> svc.decrypt("dGhpcyBpcyBub3QgYSB2YWxpZCBjaXBoZXJ0ZXh0"))
                .isInstanceOfSatisfying(AiBusinessException.class, e -> assertThat(e.getCode())
                        .isEqualTo(AiErrorCode.LLM_API_KEY_DECRYPT_FAILED.getCode()));
    }
}
