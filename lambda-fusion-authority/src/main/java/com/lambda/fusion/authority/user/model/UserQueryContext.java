package com.lambda.fusion.authority.user.model;

import com.lambda.fusion.authority.user.model.entity.UserFieldsEntity;
import java.util.List;
import java.util.Set;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 用户查询参数
 */
@Data
@Accessors(chain = true)
public class UserQueryContext {
    private List<String> usernames;
    private boolean dev;
    private boolean admin;
    private String username;
    private String email;
    private String nickname;
    private String keyword;
    private String mobile;
    private String tenantId;
    private String authority;
    private List<UserFieldsEntity> userFields;
    private Boolean isOnline;
    private Set<String> orgIds;
}
