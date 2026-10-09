package com.lambda.fusion.authority.api;

import java.io.Serial;
import java.io.Serializable;
import lombok.Getter;
import lombok.Setter;

/**
 * 远程资源移动参数（跨服务/Dubbo 传输对象）。
 *
 * <p>字段对齐 {@code MoveResource}。
 *
 * @author zx
 */
@Getter
@Setter
public class RemoteMoveResource implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 待移动资源编号 */
    private String id;

    /** 目标上级资源编号 */
    private String tid;

    /** 移动类型（0：调整上级） */
    private int type;
}
