package com.lambda.fusion.form.runtime.support;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 发布快照 JSON 结构（不可变，仅用于编译读取）
 *
 * @author Zx
 */
@Data
@SuppressFBWarnings("EI_EXPOSE_REP")
public class FormDefSnapshot {

    /** 快照格式版本，用于未来结构演进 */
    private Integer snapshotVersion = 1;

    private String formKey;

    private String formName;

    private String formType;

    private String tableName;

    private String treeConfig;

    private String flowCode;

    private String titleTemplate;

    private String interceptor;

    private List<Field> fields = new ArrayList<>();

    private List<Relation> relations = new ArrayList<>();

    /**
     * 字段快照
     */
    @Data
    public static class Field {
        private String fieldName;
        private String columnName;
        private String fieldType;
        private Boolean nullable = Boolean.TRUE;
        private String defaultValue;
        private String validation;
        private String dictType;
        private String uiComponent;
        private String uiProps;
        private Boolean listShow = Boolean.TRUE;
        private Boolean queryShow = Boolean.FALSE;
        private String queryOp;
        private Boolean formShow = Boolean.TRUE;
        private Integer sort = 0;
    }

    /**
     * 主从关系快照
     */
    @Data
    public static class Relation {
        private String subFormKey;
        private String foreignKey;
        private String cascadeStrategy;
    }
}
