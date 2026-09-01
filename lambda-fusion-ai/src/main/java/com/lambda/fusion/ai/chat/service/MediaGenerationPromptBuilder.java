package com.lambda.fusion.ai.chat.service;

import com.lambda.fusion.ai.chat.model.MediaGenerationRequest;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/** 把界面媒体类型转换为普通用户提示；不解析应用绑定、提供方或执行工具。 */
@Component
public class MediaGenerationPromptBuilder {

    /** 普通消息不覆盖提示；创作请求只追加媒体类型和一次成功即完成的用户意图。 */
    public String build(String prompt, MediaGenerationRequest request) {
        if (request == null) {
            return null;
        }
        String kind = StringUtils.lowerCase(StringUtils.trim(request.getKind()), Locale.ROOT);
        return StringUtils.defaultString(prompt)
                + """


                <media_generation_intent kind="%s">
                用户通过界面明确请求生成实际媒体成果，而不是只获取文字描述。
                请自主选择一个当前可用且匹配的媒体生成工具；具体参数只按所选工具的 Schema 从用户正文中提取。
                任一匹配工具成功产生媒体后，本次界面创作请求即完成，不要在没有用户新指令时再次生成。
                如果没有合适工具或调用失败，请如实说明。
                </media_generation_intent>
                """
                        .formatted(kind);
    }
}
