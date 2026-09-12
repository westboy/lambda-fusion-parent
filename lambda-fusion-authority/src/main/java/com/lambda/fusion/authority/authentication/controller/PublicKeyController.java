package com.lambda.fusion.authority.authentication.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.hutool.crypto.PemUtil;
import com.lambda.autoconfig.SecurityProperties;
import com.lambda.cloud.crypto.key.KeyProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.PublicKey;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录凭据加密公钥下发，匿名可访问（经 lambda.security.sa-token.ignored 放行）。
 *
 * @author Jin
 */
@Tag(name = "登录加密接口", description = "下发登录凭据 RSA 加密公钥")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class PublicKeyController {

    private final KeyProvider keyProvider;
    private final SecurityProperties securityProperties;

    @SaIgnore
    @GetMapping("/public-key")
    @Operation(summary = "获取登录凭据加密公钥", description = "前端登录前取公钥，用 RSA-OAEP(SHA-256) 加密 username/password")
    public PublicKeyInfo getPublicKey() {
        String keyId = securityProperties.getForm().getCredentialsEncrypt().getKeyId();
        PublicKey publicKey = keyProvider.get(keyId).getPublicKey();
        return new PublicKeyInfo(PemUtil.toPem("PUBLIC KEY", publicKey.getEncoded()), "RSA-OAEP-256");
    }

    /**
     * 公钥下发结果
     */
    public record PublicKeyInfo(String publicKey, String algorithm) {}
}
