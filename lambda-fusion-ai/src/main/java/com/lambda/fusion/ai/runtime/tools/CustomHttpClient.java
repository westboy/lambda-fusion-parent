package com.lambda.fusion.ai.runtime.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 自定义媒体工具 HTTP 直连的极小封装：为每个薄适配器构建一个携带 Bearer 凭据的 POST 发送函数。
 * 阻塞调用在 bounded-elastic 线程执行，调用方（{@code @Tool} 方法）拿到的是响应原始 JSON。
 * 仅承载「同步请求-响应」的厂商调用；异步轮询等复杂交互不进本封装。
 *
 * @author Jin
 */
final class CustomHttpClient {

    private CustomHttpClient() {}

    /**
     * 构建一个向 {@code endpoint} POST JSON 并返回响应体的函数。
     *
     * @param endpoint 完整接口地址
     * @param apiKey Bearer 凭据
     * @return 发送函数；非 2xx 或网络异常时以 {@link IOException} 失败
     */
    static java.util.function.Function<String, Mono<String>> poster(String endpoint, String apiKey) {
        CloseableHttpClient client = HttpClients.custom()
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .build();
        return body -> Mono.fromCallable(() -> execute(client, endpoint, apiKey, body))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static String execute(CloseableHttpClient client, String endpoint, String apiKey, String body)
            throws IOException {
        HttpPost request = new HttpPost(endpoint);
        request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        request.setHeader(HttpHeaders.ACCEPT, ContentType.APPLICATION_JSON.getMimeType());
        request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON.withCharset(StandardCharsets.UTF_8)));
        return client.execute(request, response -> {
            String responseBody = response.getEntity() == null
                    ? ""
                    : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            int status = response.getCode();
            if (status < 200 || status >= 300) {
                throw new IOException("自定义媒体接口 HTTP状态异常: " + status + " " + abbreviate(responseBody));
            }
            return responseBody;
        });
    }

    private static String abbreviate(String body) {
        return StringUtils.abbreviate(StringUtils.defaultString(body), 400);
    }
}
