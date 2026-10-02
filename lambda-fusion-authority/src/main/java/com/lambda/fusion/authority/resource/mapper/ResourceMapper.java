package com.lambda.fusion.authority.resource.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lambda.cloud.core.utils.ConvertUtils;
import com.lambda.fusion.authority.authentication.model.MenuQuery;
import com.lambda.fusion.authority.resource.model.Resource;
import com.lambda.fusion.authority.resource.model.ResourceTree;
import com.lambda.fusion.authority.resource.model.entity.ResourceEntity;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 资源服务数据持久层接口
 *
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface ResourceMapper extends BaseMapper<ResourceEntity> {
    /** 资源为全局管理元数据；使用不可被菜单管理删除的固定锁行串行化路径检查和写入。 */
    @Select("SELECT ID FROM la_resource_tree_lock WHERE ID = 'tree' FOR UPDATE")
    String lockResourceTree();

    @Delete({
        "<script>DELETE FROM la_api_resources WHERE resource_id IN",
        "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
        "</script>"
    })
    void deleteApiBindings(@Param("ids") Set<String> ids);
    /**
     * 根据操作用户查询所有下级(直接下级和间接下级)的信息
     *
     * @param parameters
     * @return
     */
    List<Resource> getAllChildren(Map<String, Object> parameters);

    /**
     * 根据操作用户查询所有上级(直接上级和间接上级)的信息
     *
     * @param parameters
     * @return
     */
    List<Resource> getAllParents(Map<String, Object> parameters);

    /**
     * 查询直接下级资源,仅包含该节点下的直接下级
     *
     * @param id
     */
    List<ResourceTree> getDirectChildren(@Param("id") String id);

    /***
     * 查询所有下级资源(直接下级和间接下级)的资源信息
     * @param parentKeys
     */
    List<ResourceTree> queryAllChildren(@Param("parentKeys") String parentKeys);

    /**
     * 是否包含下级资源
     *
     * @param id
     * @return
     */
    boolean hasChildren(String id);

    /**
     * 下级是否包含按钮
     *
     * @param pid
     * @param id
     */
    boolean hasButton(@Param("pid") String pid, @Param("id") String id);

    /**
     * 下级是否包含非按钮资源
     *
     * @param pid
     * @param id
     */
    boolean hasNotButton(@Param("pid") String pid, @Param("id") String id);

    /**
     * 修改资源
     *
     */
    default void updateResource(Resource resource) {
        ResourceEntity resourceEntity = ConvertUtils.convert(resource);
        this.updateById(resourceEntity);
    }

    /***
     * 根据编号查询资源信息
     *
     * @param id
     */
    default Resource getResourceById(String id) {
        ResourceEntity resourceEntity = selectById(id);
        return ConvertUtils.convert(resourceEntity);
    }

    /***
     * 新建资源
     *
     * @param resource
     * @return void
     */
    default void addResource(Resource resource) {
        ResourceEntity resourceEntity = ConvertUtils.convert(resource);
        this.insert(resourceEntity);
    }

    /***
     * 删除资源
     *
     * @param ids
     * @return void
     */
    default void deleteResource(Set<String> ids) {
        this.deleteByIds(ids);
    }

    /***
     * 删除资源关系的角色列表
     *
     * @param ids
     * @return void
     */
    void deleteRolesResource(Set<String> ids);

    /***
     * 获取所有可用的资源
     * @param parameter 参数
     */
    default List<Resource> queryAvailableResources(MenuQuery parameter) {
        List<ResourceEntity> resourceEntities = selectList(new LambdaQueryWrapper<ResourceEntity>()
                .eq(ResourceEntity::getResMode, parameter.getMode())
                .eq(
                        StringUtils.isNotBlank(parameter.getApplication()),
                        ResourceEntity::getApplication,
                        parameter.getApplication())
                .ge(ResourceEntity::getResType, 0)
                .orderByAsc(ResourceEntity::getResLevel, ResourceEntity::getOrderNo));
        return ConvertUtils.convertList(resourceEntities);
    }

    /**
     * 查询系统资源列表
     *
     * @param
     */
    List<Resource> getAllResourcesByOrderNo();

    /***
     * 批量排序
     *
     * @param changed
     */
    void updateResourceOrdered(List<Resource> changed);

    /**
     * 批量更新隐藏/显示子资源
     *
     * @param changed 子资源
     * @param status  隐藏状态
     */
    void updateResourceIsHidden(@Param("list") List<ResourceTree> changed, @Param("status") boolean status);

    /***
     * 获取所有的资源信息
     */
    List<Resource> getAllResources();

    /***
     * 更新Parentkeys
     * @param resources
     */
    void updateResourceParentKeys(List<Resource> resources);

    /***
     * 批量更新Rank值
     * @param changed2
     */
    void updateResourceLevel(List<Resource> changed2);

    /**
     * 更新移动的资源
     *
     * @param moved
     * @return void
     */
    void updateMovedResource(Resource moved);
}
