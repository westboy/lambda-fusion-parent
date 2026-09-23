package com.lambda.fusion.authority.api;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 远程分页结果（跨服务/Dubbo 传输对象）。
 *
 * <p>authority-api 不依赖具体分页框架，仅承载 total + rows 最小契约。
 *
 * @author jin
 * @param <T> 行记录类型
 */
@Getter
@Setter
public class RemotePage<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 总记录数 */
    private long total;

    /** 当前页记录 */
    private List<T> rows;

    public RemotePage() {}

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public RemotePage(long total, List<T> rows) {
        this.total = total;
        this.rows = rows;
    }

    public List<T> getRows() {
        return rows == null ? null : List.copyOf(rows);
    }

    public void setRows(List<T> rows) {
        this.rows = rows == null ? null : List.copyOf(rows);
    }
}
