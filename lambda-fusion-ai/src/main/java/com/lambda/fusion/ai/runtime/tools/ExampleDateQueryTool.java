package com.lambda.fusion.ai.runtime.tools;

import com.lambda.fusion.ai.runtime.annotation.AiTools;
import com.lambda.fusion.ai.runtime.annotation.RequireConfirm;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import lombok.extern.slf4j.Slf4j;

/**
 * 日期查询示例工具：演示 {@code @Tool} + {@link RequireConfirm}（HITL）。
 *
 * <p>日期查询本身只读，此处配 ASK 仅为演示 HITL 机制；真实场景应对有副作用的工具配置。
 *
 * @author Jin
 */
@Slf4j
@AiTools
public class ExampleDateQueryTool {

    /** 工具名。 */
    public static final String TOOL_NAME = "query_date";

    @Tool(
            name = TOOL_NAME,
            description = "Query the current date/time with a custom format and timezone. "
                    + "Use this tool when the user asks about the current date, time, or weekday.")
    @RequireConfirm("日期查询演示 HITL 人工确认")
    public String queryDate(
            @ToolParam(
                            name = "format",
                            description = "Date/time format pattern, e.g. yyyy-MM-dd or yyyy-MM-dd HH:mm:ss")
                    String format,
            @ToolParam(
                            name = "timezone",
                            description = "IANA timezone ID, e.g. Asia/Shanghai or UTC. Defaults to system timezone.",
                            required = false)
                    String timezone) {
        String pattern = (format == null || format.isBlank()) ? "yyyy-MM-dd" : format;
        DateTimeFormatter formatter = resolveFormatter(pattern);
        ZoneId zone = resolveZone(timezone);
        String result = ZonedDateTime.now(zone).format(formatter);
        log.info("日期查询: pattern={}, timezone={}, result={}", pattern, zone.getId(), result);
        return result;
    }

    private static DateTimeFormatter resolveFormatter(String pattern) {
        try {
            return DateTimeFormatter.ofPattern(pattern);
        } catch (IllegalArgumentException e) {
            log.warn("日期格式非法，回退默认 yyyy-MM-dd: pattern={}", pattern);
            return DateTimeFormatter.ofPattern("yyyy-MM-dd");
        }
    }

    private static ZoneId resolveZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(timezone);
        } catch (Exception e) {
            log.warn("时区非法，回退系统默认: timezone={}", timezone);
            return ZoneId.systemDefault();
        }
    }
}
