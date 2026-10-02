package com.lambda.fusion.form.runtime.mapper;

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
            SELECT COUNT(1) FROM ${tableName} WHERE 1 = 1
            <if test="whereSql != null">${whereSql}</if>
            </script>
            """)
    long countBy(
            @Param("tableName") String tableName,
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
