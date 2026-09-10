package com.fusion.startup.ai.tools;

import cn.hutool.core.util.HexUtil;
import com.lambda.cloud.netty.protocol.annotation.ProtocolField;
import com.lambda.cloud.netty.protocol.annotation.ProtocolPayload;
import com.lambda.cloud.netty.protocol.encrypt.impl.DefaultEncryptionService;
import com.lambda.cloud.netty.protocol.engine.ProtocolEngine;
import com.lambda.cloud.netty.protocol.engine.ProtocolEngineFactory;
import com.lambda.cloud.netty.protocol.engine.impl.ReflectionProtocolEngine;
import com.lambda.cloud.netty.protocol.message.ProtocolPayloadRegistry;
import com.lambda.cloud.netty.protocol.scanner.ProtocolPayloadScanner;
import com.lambda.cloud.ykc.message.v16.YkcV16BasePayload;
import com.lambda.fusion.ai.runtime.annotation.AiTools;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import jodd.exception.ExceptionUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;

/**
 * 云快充 v1.6 帧解析工具：输入完整帧 hex，输出可读字段描述。复用 ykc v16 消息体定义与 netty 注解协议引擎解码
 * （零重复）；首次解析前单独扫描注册 v16 包以覆盖 v17/v20 的同帧类型（全局注册表仅按帧类型键控，无版本维度），
 * 确定性按 v16 语义解析。加密帧（encryptFlag=0x01）需配置 {@code lambda.fusion.ai.ykc.encrypt-key}；工具不向模型抛异常，
 * 任何失败降级为可读错误说明。
 * <p>
 * (示例项目)
 *
 * @author zx
 */
@Slf4j
@AiTools
public class YkcFrameParseTool {

    /** 工具名。 */
    public static final String TOOL_NAME = "parse_ykc_charging_frame";

    /** v16 消息包路径（仅本版本，做版本隔离注册）。 */
    private static final String V16_PACKAGE = "com.lambda.cloud.ykc.message.v16";

    /** 帧头固定字节偏移：startFlag0|dataLength1|serialNumber2-3(LE)|encryptFlag4|frameType5。 */
    private static final int ENCRYPT_FLAG_OFFSET = 4;

    private static final int FRAME_TYPE_OFFSET = 5;

    private final String encryptKey;
    private final java.util.concurrent.atomic.AtomicBoolean v16Registered =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public YkcFrameParseTool(@Value("${lambda.fusion.ai.ykc.encrypt-key:}") String encryptKey) {
        this.encryptKey = encryptKey;
    }

    /**
     * 解析云快充 v1.6 协议帧。
     *
     * @param hex 完整帧的十六进制文本（含起始符至校验码），如
     *            {@code 680132000001020054...C23A}
     * @return 帧的可读字段描述；输入非法、帧类型未识别或解析失败时返回可读错误说明
     */
    @Tool(
            name = TOOL_NAME,
            description = "Parse a ykc (云快充) v1.6 protocol frame given as a hexadecimal string, "
                    + "and return a human-readable description of its fields. Use this tool when the user pastes a "
                    + "charging-station protocol frame (hex) and wants to understand its structure, message type, "
                    + "or field values.")
    public String parseFrame(
            @ToolParam(name = "hex", description = "Complete v1.6 frame in hex, e.g. 680132000001020054...C23A")
                    String hex) {
        if (!StringUtils.hasText(hex)) {
            return "无法解析：帧为空。请提供完整的云快充 v1.6 帧（十六进制文本）。";
        }
        String normalized = hex.replaceAll("\\s+", "").toUpperCase();
        if (!isValidHex(normalized)) {
            return "无法解析：不是有效的十六进制文本。请输入纯十六进制（0-9A-F）。";
        }
        byte[] raw;
        try {
            raw = HexUtil.decodeHex(normalized);
        } catch (RuntimeException e) {
            log.warn("帧 hex 解码失败: reason={}", e.getMessage());
            return "无法解析：十六进制解码失败（" + e.getMessage() + "）。";
        }
        if (raw.length < FRAME_TYPE_OFFSET + 1) {
            return "无法解析：帧过短（仅 " + raw.length + " 字节），不足以构成完整 v1.6 帧。";
        }

        ensureV16Registered();

        byte encryptFlag = raw[ENCRYPT_FLAG_OFFSET];
        if (encryptFlag != 0x01 && encryptFlag != 0x00) {
            return "无法解析：不支持的加密标志 0x" + String.format("%02X", encryptFlag) + "（仅支持 0x00 未加密 / 0x01 加密）。";
        }
        if (encryptFlag == 0x01 && !StringUtils.hasText(encryptKey)) {
            return "该帧数据域已加密（encryptFlag=0x01），但未配置解密密钥。请配置 lambda.fusion.ai.ykc.encrypt-key " + "后重试。";
        }

        byte frameTypeByte = raw[FRAME_TYPE_OFFSET];
        String frameType = String.format("%02X", frameTypeByte);
        Class<?> messageType = ProtocolPayloadRegistry.getProtocolMessage(frameType);
        if (messageType == null) {
            return "未识别帧类型 0x" + frameType + "（该帧类型未在云快充 v1.6 消息集中定义）。";
        }

        try {
            ProtocolEngine<YkcV16BasePayload> engine = buildEngine(encryptFlag == 0x01);
            ByteBuf buf = Unpooled.wrappedBuffer(raw);
            YkcV16BasePayload base = engine.parse(buf, YkcV16BasePayload.class);
            return render(base);
        } catch (Exception e) {

            log.warn(
                    "v1.6 帧解析失败: frameType=0x{}, reason={}",
                    frameType,
                    ExceptionUtil.getRootCause(e).getMessage());
            return "解析失败（帧类型 0x" + frameType + "）：" + ExceptionUtil.getRootCause(e);
        }
    }

    /** 仅注册 v16 包，覆盖全局注册表中 v17/v20 的同帧类型，保证确定性按 v16 解码。 */
    private void ensureV16Registered() {
        if (v16Registered.get()) {
            return;
        }
        // 与 YkcAutoConfiguration 相同的扫描方式，仅收敛到 v16 包。
        new ProtocolPayloadScanner().scanAndRegister(V16_PACKAGE);
        v16Registered.set(true);
    }

    private ProtocolEngine<YkcV16BasePayload> buildEngine(boolean encrypted) {
        if (encrypted) {
            String key = encryptKey.trim();
            try {
                ReflectionProtocolEngine engine =
                        new ReflectionProtocolEngine(new DefaultEncryptionService(HexUtil.decodeHex(key)));
                ProtocolEngineFactory.addEngine(ProtocolEngineFactory.EngineType.REFLECTION, engine);
            } catch (RuntimeException e) {
                log.warn("加密密钥非有效 hex，忽略密钥使用未加密引擎: reason={}", e.getMessage());
                ProtocolEngineFactory.addEngine(
                        ProtocolEngineFactory.EngineType.REFLECTION, new ReflectionProtocolEngine(null));
            }
        } else {
            ProtocolEngineFactory.addEngine(
                    ProtocolEngineFactory.EngineType.REFLECTION, new ReflectionProtocolEngine(null));
        }
        return ProtocolEngineFactory.getEngine(ProtocolEngineFactory.EngineType.REFLECTION);
    }

    private String render(YkcV16BasePayload base) {
        StringBuilder sb = new StringBuilder();
        appendObject(sb, base, "");
        return sb.toString();
    }

    /** 反射渲染带 {@link ProtocolField} 的对象：按 order 输出字段名(类型-描述): 值，复合/集合递归。 */
    private void appendObject(StringBuilder sb, Object obj, String indent) {
        if (obj == null) {
            return;
        }
        ProtocolPayload payload = obj.getClass().getAnnotation(ProtocolPayload.class);
        if (payload != null && StringUtils.hasText(payload.name())) {
            sb.append(indent).append("【").append(payload.name()).append("】");
            if (StringUtils.hasText(payload.description())) {
                sb.append(" ").append(payload.description());
            }
            sb.append('\n');
        }

        List<Field> fields = new ArrayList<>();
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.isAnnotationPresent(ProtocolField.class)) {
                    fields.add(field);
                }
            }
        }
        fields.sort(Comparator.comparingInt(
                f -> f.getAnnotation(ProtocolField.class).order()));

        for (Field field : fields) {
            ProtocolField ann = field.getAnnotation(ProtocolField.class);
            Object value = readField(field, obj);
            appendField(sb, field.getName(), ann, value, indent + "  ");
        }
    }

    private void appendField(StringBuilder sb, String name, ProtocolField ann, Object value, String indent) {
        if (value == null || isBlank(value)) {
            return;
        }
        String label = name;
        String type = ann.dataType().name();
        if (StringUtils.hasText(ann.description())) {
            label += "(" + type + "-" + ann.description() + ")";
        } else {
            label += "(" + type + ")";
        }
        boolean composite = ann.composite() || value.getClass().isAnnotationPresent(ProtocolPayload.class);
        if (composite || value instanceof Collection) {
            sb.append(indent).append(name).append(":\n");
            if (value instanceof Collection) {
                for (Object element : (Collection<?>) value) {
                    appendObject(sb, element, indent + "  ");
                }
            } else {
                appendObject(sb, value, indent + "  ");
            }
        } else {
            sb.append(indent).append(label).append(": ").append(value).append('\n');
        }
    }

    private Object readField(Field field, Object target) {
        try {
            String suffix = Character.toUpperCase(field.getName().charAt(0))
                    + field.getName().substring(1);
            for (String prefix : new String[] {"get", "is"}) {
                Method m;
                try {
                    m = target.getClass().getMethod(prefix + suffix);
                } catch (NoSuchMethodException e) {
                    continue;
                }
                m.setAccessible(true);
                return m.invoke(target);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 回退：直接读字段（含私有）
            try {
                field.setAccessible(true);
                return field.get(target);
            } catch (IllegalAccessException ignored) {
                // 忽略单个字段读取失败
            }
        }
        return null;
    }

    private static boolean isBlank(Object value) {
        if (value instanceof String s) {
            return !StringUtils.hasText(s);
        }
        if (value instanceof Collection<?> c) {
            return c.isEmpty();
        }
        return false;
    }

    private static boolean isValidHex(String s) {
        return s.matches("[0-9A-Fa-f]+");
    }
}
