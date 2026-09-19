package com.lambda.fusion.ai.chat.runtime.event;

import java.nio.charset.StandardCharsets;

/**
 * 对话执行事件。
 *
 * @param cursor 当前 JVM 缓冲区内的游标
 * @param type 事件类型
 * @param data AG-UI 事件 JSON
 * @param byteSize data 的 UTF-8 字节数（构造时计算一次，窗口容量计量直接复用）
 * @author Jin
 */
public record ChatRunEvent(long cursor, String type, String data, int byteSize) {

    /** 构造时计算字节计量，避免窗口入窗/淘汰时重复 UTF-8 编码。 */
    public ChatRunEvent(long cursor, String type, String data) {
        this(cursor, type, data, data == null ? 0 : data.getBytes(StandardCharsets.UTF_8).length);
    }
}
