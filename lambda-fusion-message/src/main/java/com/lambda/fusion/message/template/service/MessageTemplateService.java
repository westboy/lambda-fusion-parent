package com.lambda.fusion.message.template.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.message.template.model.entity.MessageTemplateEntity;
import com.lambda.fusion.message.template.model.query.MessageTemplateQuery;
import com.lambda.fusion.message.template.model.vo.MessageTemplate;
import java.util.Map;

/**
 * 消息模板服务（管理端 CRUD + 发送侧按编码取用与渲染）
 *
 * @author jin
 */
public interface MessageTemplateService extends IService<MessageTemplateEntity> {

    /**
     * 模板管理分页
     *
     * @param query 查询参数
     * @return 模板分页
     */
    PageView<MessageTemplate> page(MessageTemplateQuery query);

    /**
     * 创建模板（编码租户内唯一）
     *
     * @param template 模板参数
     * @return 模板 VO
     */
    MessageTemplate create(MessageTemplate template);

    /**
     * 更新模板
     *
     * @param id       模板ID
     * @param template 模板参数
     * @return 模板 VO
     */
    MessageTemplate update(String id, MessageTemplate template);

    /**
     * 删除模板
     *
     * @param id 模板ID
     */
    void delete(String id);

    /**
     * 按编码取启用中的模板；不存在或已停用返回 null（发送侧降级判断用）
     *
     * @param code 模板编码
     * @return 模板实体
     */
    MessageTemplateEntity getEnabledByCode(String code);

    /**
     * 渲染模板内容：将 ${var} 占位符替换为参数值；模板或参数为空时原样返回
     *
     * @param template 模板内容
     * @param params   变量参数
     * @return 渲染结果
     */
    String render(String template, Map<String, Object> params);
}
