package com.lambda.fusion.ai.security;

import com.lambda.cloud.crypto.exception.CryptoException;
import com.lambda.cloud.crypto.service.SymmetricCryptoService;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * 基于 lambda-cloud-starter-crypto 的 API Key 加解密实现，密钥经 {@code lambda.crypto.keys} 配置，
 * 密文格式为 {@code base64(IV || ciphertext+tag)}。
 *
 * @author Jin
 */
@Component
@RequiredArgsConstructor
public class CryptoKeyEncryptionService implements KeyEncryptionService {

    private final SymmetricCryptoService symmetricCryptoService;

    @Override
    public String encrypt(String plaintext) {
        if (StringUtils.isBlank(plaintext)) {
            return null;
        }
        try {
            byte[] cipher = symmetricCryptoService.encrypt(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(cipher);
        } catch (CryptoException e) {
            throw mapException(e, AiErrorCode.CONFIGURATION_ERROR);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        if (StringUtils.isBlank(ciphertext)) {
            return null;
        }
        try {
            byte[] plain = symmetricCryptoService.decrypt(Base64.getDecoder().decode(ciphertext));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (CryptoException e) {
            throw mapException(e, AiErrorCode.LLM_API_KEY_DECRYPT_FAILED);
        } catch (Exception e) {
            throw new AiBusinessException(AiErrorCode.LLM_API_KEY_DECRYPT_FAILED, e);
        }
    }

    /**
     * 密钥缺失映射为 30421，其余为加解密失败
     */
    private AiBusinessException mapException(CryptoException e, AiErrorCode fallback) {
        if (e.getMessage() != null && e.getMessage().startsWith("Crypto key not found")) {
            return new AiBusinessException(AiErrorCode.LLM_ENCRYPTION_KEY_NOT_CONFIGURED, e);
        }
        return new AiBusinessException(fallback, e);
    }
}
