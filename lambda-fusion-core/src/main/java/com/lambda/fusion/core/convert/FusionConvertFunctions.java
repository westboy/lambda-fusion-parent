package com.lambda.fusion.core.convert;

import cn.hutool.core.codec.Base64;
import cn.hutool.extra.spring.SpringUtil;
import com.lambda.cloud.crypto.service.SymmetricCryptoService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import org.mapstruct.Named;

@SuppressWarnings("unused")
public interface FusionConvertFunctions {

    @Named("mapAccountExpired")
    static Boolean mapAccountExpired(LocalDateTime expiredTime) {
        if (expiredTime == null) {
            return true;
        }
        return !expiredTime.isAfter(LocalDateTime.now());
    }

    @Named("mapAccountLocked")
    static Boolean mapAccountLocked(boolean enabled) {
        return !enabled;
    }

    @Named("mapAccountEnabled")
    static Integer mapAccountEnabled(boolean enabled) {
        return enabled ? 1 : 0;
    }

    /**
     * 密码加密存储：Base64(AES-GCM(明文))
     */
    @Named("mapEncodePassword")
    static String mapEncodePassword(String password) {
        if (password == null || password.isBlank()) {
            return password;
        }
        byte[] cipher =
                SpringUtil.getBean(SymmetricCryptoService.class).encrypt(password.getBytes(StandardCharsets.UTF_8));
        return Base64.encode(cipher);
    }

    /**
     * 密文还原为明文
     */
    @Named("mapDecodePassword")
    static String mapDecodePassword(String password) {
        if (password == null || password.isBlank()) {
            return password;
        }
        byte[] plain = SpringUtil.getBean(SymmetricCryptoService.class).decrypt(Base64.decode(password));
        return new String(plain, StandardCharsets.UTF_8);
    }
}
