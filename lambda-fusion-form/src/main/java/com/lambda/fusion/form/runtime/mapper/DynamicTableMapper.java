package com.lambda.fusion.form.runtime.mapper;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 动态业务表读写 Mapper
 *
 * <p>表名/列名全部来自服务端 {@code FormModel} 白名单，值全部经 {@code #{params.*}}
 * 参数化绑定（无一处原文拼接值）；租户条件由框架 TenantLineInnerInterceptor 在解析层追加。
 *
 * @author Zx
 */
public interface DynamicTableMapper {

    /**
     * 按列清单查询列表
     *
     * @param tableName 目标表（白名单）
     * @param selectColumns 列清单（白名单，逗号分隔）
     * @param whereSql 预构建 WHERE 片段（形如 " AND COL = #{params.p0} AND ..."，可空）
     * @param orderSql 预构建 ORDER BY 片段（形如 " ORDER BY COL DESC"，可空）
     * @param params 参数 Map
     */
    @Select(
            """
            <script>
            SELECT ${selectColumns} FROM ${tableName} WHERE 1 = 1
            <if test="whereSql != null">${whereSql}</if>
            <if test="orderSql != null">${orderSql}</if>
            </script>
            """)
    List<Map<String, Object>> selectList(
            @Param("tableName") String tableName,
            @Param("selectColumns") String selectColumns,
            @Param("whereSql") String whereSql,
            @Param("orderSql") String orderSql,
            @Param("params") Map<String, Object> params);

    /**
     * 计数
     */
    @Select(
            """
            <script>
            SELECT COUNT(1) FROM ${tableName} WHERE 1=1
            <if test="whereSql != null">${whereSql}</if>
            </script>
            """)
    long countBy(
            @Param("tableName") String tableName,
            @Param("whereSql") String whereSql,
            @Param("params") Map<String, Object> params);

    /**
     * 合计聚合（selectSql 为服务端拼装的 SUM/COUNT 表达式，列名全部来自服务端模型白名单）
     */
    @Select(
            """
            <script>
            SELECT ${selectSql} FROM ${tableName} WHERE 1=1
            <if test="whereSql != null">${whereSql}</if>
            </script>
            """)
    Map<String, Object> aggregate(
            @Param("tableName") String tableName,
            @Param("selectSql") String selectSql,
            @Param("whereSql") String whereSql,
            @Param("params") Map<String, Object> params);

    /**
     * 按主键查询单条（租户条件由拦截器追加）
     */
    @Select("<script>SELECT ${selectColumns} FROM ${tableName} WHERE ID = #{params.id}"
            + "<if test=\"whereSql != null\">${whereSql}</if></script>")
    Map<String, Object> selectById(
            @Param("tableName") String tableName,
            @Param("selectColumns") String selectColumns,
            @Param("whereSql") String whereSql,
            @Param("params") Map<String, Object> params);

    /**
     * 行锁读取（乐观锁指纹比对的预读；须在事务内调用，锁随事务提交释放）
     */
    @Select("<script>SELECT ${selectColumns} FROM ${tableName} WHERE ID = #{params.id}"
            + "<if test=\"whereSql != null\">${whereSql}</if> FOR UPDATE</script>")
    Map<String, Object> selectForUpdate(
            @Param("tableName") String tableName,
            @Param("selectColumns") String selectColumns,
            @Param("whereSql") String whereSql,
            @Param("params") Map<String, Object> params);

    /**
     * 按外键列批量查子行（主从两段式读组装；列名经服务端关系配置白名单，
     * 值为主表行 id 集参数化绑定）
     */
    @Select(
            """
            <script>
            SELECT ${selectColumns} FROM ${tableName} WHERE ${fkColumn} IN
            <foreach collection="values" item="v" open="(" separator="," close=")">#{v}</foreach>
            <if test="whereSql != null">${whereSql}</if>
            <if test="orderSql != null">${orderSql}</if>
            </script>
            """)
    List<Map<String, Object>> selectByFk(
            @Param("tableName") String tableName,
            @Param("selectColumns") String selectColumns,
            @Param("fkColumn") String fkColumn,
            @Param("values") Collection<String> values,
            @Param("whereSql") String whereSql,
            @Param("orderSql") String orderSql);

    /**
     * 按外键批量查子行并限制每个外键组的行数（窗口函数 ROW_NUMBER 分组截断；
     * 主从读组装防海量子行整页加载，跨库可移植）
     */
    @Select(
            """
            <script>
            SELECT ${selectColumns} FROM (
                SELECT ${selectColumns}, ROW_NUMBER() OVER (PARTITION BY ${fkColumn} ORDER BY ID ${windowOrder}) AS RN_
                FROM ${tableName} WHERE ${fkColumn} IN
                <foreach collection="values" item="v" open="(" separator="," close=")">#{v}</foreach>
                <if test="whereSql != null">${whereSql}</if>
            ) T_ WHERE T_.RN_ &lt;= #{maxPerGroup}
            ORDER BY T_.ID
            </script>
            """)
    List<Map<String, Object>> selectByFkWindowed(
            @Param("tableName") String tableName,
            @Param("selectColumns") String selectColumns,
            @Param("fkColumn") String fkColumn,
            @Param("values") Collection<String> values,
            @Param("whereSql") String whereSql,
            @Param("maxPerGroup") long maxPerGroup,
            @Param("windowOrder") String windowOrder);

    /**
     * 按外键列删除子行（CASCADE 物理/替换式更新清理）
     */
    @Delete("<script>DELETE FROM ${tableName} WHERE ${fkColumn} = #{params.value}"
            + "<if test=\"whereSql != null\">${whereSql}</if></script>")
    int deleteByFk(
            @Param("tableName") String tableName,
            @Param("fkColumn") String fkColumn,
            @Param("whereSql") String whereSql,
            @Param("params") Map<String, Object> params);

    /**
     * 按外键列批量更新子行（逻辑删除级联：SET 片段如 "DELETED = 1"）
     */
    @Update("<script>UPDATE ${tableName} SET ${setSql} WHERE ${fkColumn} = #{params.value}"
            + "<if test=\"whereSql != null\">${whereSql}</if></script>")
    int updateByFk(
            @Param("tableName") String tableName,
            @Param("setSql") String setSql,
            @Param("fkColumn") String fkColumn,
            @Param("whereSql") String whereSql,
            @Param("params") Map<String, Object> params);

    /**
     * 动态列插入（含审计列与租户列）
     *
     * @param columns 列清单（白名单）
     * @param values 值占位片段（形如 "#{params.p0}"，与 columns 等长）
     */
    @Insert(
            """
            <script>
            INSERT INTO ${tableName}
            <foreach collection="columns" item="col" open="(" separator="," close=")">${col}</foreach>
            VALUES
            <foreach collection="values" item="v" open="(" separator="," close=")">${v}</foreach>
            </script>
            """)
    int insertDynamic(
            @Param("tableName") String tableName,
            @Param("columns") List<String> columns,
            @Param("values") List<String> values,
            @Param("params") Map<String, Object> params);

    /**
     * 动态列更新（主键定位，租户条件由拦截器追加）
     *
     * @param setSql 预构建 SET 片段（形如 "COL = #{params.p0}, ..."）
     */
    @Update(
            """
            <script>
            UPDATE ${tableName} SET ${setSql} WHERE ID = #{params.id}
            <if test="whereSql != null">${whereSql}</if>
            </script>
            """)
    int updateDynamic(
            @Param("tableName") String tableName,
            @Param("setSql") String setSql,
            @Param("whereSql") String whereSql,
            @Param("params") Map<String, Object> params);

    /**
     * 按主键删除（租户条件由拦截器追加）
     */
    @Delete("<script>DELETE FROM ${tableName} WHERE ID = #{params.id}"
            + "<if test=\"whereSql != null\">${whereSql}</if></script>")
    int deleteById(
            @Param("tableName") String tableName,
            @Param("whereSql") String whereSql,
            @Param("params") Map<String, Object> params);
}
