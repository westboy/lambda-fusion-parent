package com.lambda.fusion.core.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;

import cn.hutool.core.codec.Base64;
import cn.hutool.extra.spring.SpringUtil;
import com.lambda.cloud.crypto.algorithm.AlgorithmType;
import com.lambda.cloud.crypto.key.KeyEntry;
import com.lambda.cloud.crypto.key.KeyProvider;
import com.lambda.cloud.crypto.service.SymmetricCryptoService;
import com.lambda.cloud.crypto.service.impl.HutoolSymmetricCryptoService;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class FusionConvertFunctionsPasswordTest {

    private MockedStatic<SpringUtil> springUtil;

    @BeforeEach
    void setUp() {
        KeyProvider keyProvider = new KeyProvider() {
            private final KeyEntry entry =
                    KeyEntry.of("default", AlgorithmType.AES, new SecretKeySpec(new byte[32], "AES"));

            @Override
            public KeyEntry get(String keyId) {
                return entry;
            }

            @Override
            public KeyEntry get() {
                return entry;
            }
        };
        SymmetricCryptoService service = new HutoolSymmetricCryptoService(keyProvider);
        springUtil = Mockito.mockStatic(SpringUtil.class);
        springUtil
                .when(() -> SpringUtil.getBean(eq(SymmetricCryptoService.class)))
                .thenReturn(service);
    }

    @AfterEach
    void tearDown() {
        springUtil.close();
    }

    @Test
    void encodeDecodeRoundTrip() {
        String cipher = FusionConvertFunctions.mapEncodePassword("db-Passw0rd!");
        assertThat(cipher).isNotEqualTo("db-Passw0rd!");
        assertThat(Base64.decodeStr(cipher)).isNotEqualTo("db-Passw0rd!");
        assertThat(FusionConvertFunctions.mapDecodePassword(cipher)).isEqualTo("db-Passw0rd!");
    }

    @Test
    void eachEncodeProducesDifferentCiphertext() {
        assertThat(FusionConvertFunctions.mapEncodePassword("same"))
                .isNotEqualTo(FusionConvertFunctions.mapEncodePassword("same"));
    }

    @Test
    void blankInputReturnsAsIs() {
        assertThat(FusionConvertFunctions.mapEncodePassword(null)).isNull();
        assertThat(FusionConvertFunctions.mapEncodePassword("")).isEmpty();
        assertThat(FusionConvertFunctions.mapDecodePassword(null)).isNull();
        assertThat(FusionConvertFunctions.mapDecodePassword("")).isEmpty();
    }
}
