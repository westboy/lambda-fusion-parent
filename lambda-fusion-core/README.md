# Lambda Fusion Core

Lambda Fusion Core 是 Lambda Fusion 框架的核心基础模块，提供企业级微服务开发所需的基础工具类、抽象服务、树形结构处理、分页查询、用户身份管理等核心功能。作为整个 Lambda Fusion 生态系统的基石，为其他模块（权限管理、配置管理、数据字典、数据源管理等）提供统一的基础设施。

## 模块概述

本项目是 Lambda Fusion 框架的核心基础库，专为企业级微服务应用设计，提供统一的数据访问模式、树形结构处理、分页查询、类型转换等基础功能。通过泛型编程、函数式编程等现代 Java 特性，实现高性能、类型安全的基础组件。

### 核心特性

- **通用 CRUD 服务**：基于 MyBatis Plus 的抽象 CRUD 服务，支持实体与 VO 自动转换
- **高性能树形结构**：O(n) 复杂度的树形数据构建器，支持多种构建策略
- **统一分页查询**：安全的分页查询框架，支持多字段排序和 SQL 注入防护
- **用户身份管理**：完整的用户身份主体，支持多租户和角色权限
- **类型转换工具**：MapStruct 兼容的转换函数和注解处理
- **工具类库**：SQL 参数处理、常量定义等实用工具
- **树形数据过滤**：保持父子关系的树形数据过滤器

## 技术栈

### 核心技术
- **Java 21+**
- **Spring Boot 3.x**
- **MyBatis Plus** - ORM 框架
- **Lambda Cloud Core** - 基础框架依赖

### 主要依赖
- **lambda-cloud-core** - Lambda Cloud 核心库
- **mybatis-plus-core** - MyBatis Plus 核心
- **mybatis-plus-extension** - MyBatis Plus 扩展
- **lambda-cloud-starter-security** - 安全框架
- **Google Guava** - 集合工具（Multimap, Maps）
- **Apache Commons** - 通用工具
- **Lombok** - 代码生成

## 项目结构

```
lambda-fusion-core/
├── src/main/java/com/lambda/fusion/core/
│   ├── annotation/                       # 注解处理
│   │   └── DictMapper.java               # 字典映射注解
│   ├── convert/                          # 类型转换工具
│   │   └── ConvertFunctions.java         # MapStruct 转换函数
│   ├── identity/                         # 用户身份管理
│   │   └── LoginUserDetails.java         # 用户身份主体
│   ├── pagination/                       # 分页查询
│   │   └── Pagination.java               # 分页查询基类
│   ├── service/                          # 基础服务
│   │   └── AbstractCrudService.java      # 抽象 CRUD 服务
│   ├── tree/                             # 树形结构处理
│   │   ├── TreeNode.java                 # 树节点接口
│   │   ├── builder/
│   │   │   └── TreeBuilder.java          # 树形数据构建器
│   │   ├── filter/
│   │   │   ├── TreeDataFilter.java       # 树形数据过滤器接口
│   │   │   └── DefaultTreeDataFilter.java # 默认过滤器实现
│   │   ├── model/
│   │   │   ├── TreeDragMode.java         # 树节点拖拽模式
│   │   │   └── TreeNodeKey.java          # 树节点键
│   │   └── util/
│   │       ├── TreeNodeUtils.java        # 树节点工具类
│   │       └── TreeNodeKeyUtils.java     # 树节点键工具类
│   ├── utils/                            # 通用工具类
│   │   ├── LoginUserUtils.java           # 登录用户工具类
│   │   └── SqlParamUtils.java            # SQL 参数工具类
│   └── FusionConstants.java              # 系统常量定义
└── pom.xml
```

## 核心功能详解

### 1. 抽象 CRUD 服务 (AbstractCrudService)

**主要功能：**
- 继承 MyBatis Plus 的 `ServiceImpl`，提供标准 CRUD 操作
- 自动实体与 VO（值对象）转换
- 支持分页查询和条件查询的 VO 转换
- 基于泛型的类型安全设计

**核心方法：**
```java
// 实体转 VO
public V toVO(E entity)
public List<V> toVO(List<E> entities)

// 分页查询转 VO
public IPage<V> pageForVO(IPage<E> page, Wrapper<E> queryWrapper)
public IPage<V> pageForVO(IPage<E> page)

// 条件查询转 VO
public List<V> listForVO(Wrapper<E> queryWrapper)
public List<V> listForVO()

// 单个查询转 VO
public V getForVO(Wrapper<E> queryWrapper)
public V getByIdForVO(Serializable id)
```

**使用示例：**
```java
@Service
public class UserService extends AbstractCrudService<UserEntity, UserVO, UserMapper> {
    
    public IPage<UserVO> getUserPage(Page<UserEntity> page, String name) {
        LambdaQueryWrapper<UserEntity> wrapper = Wrappers.lambdaQuery();
        if (StringUtils.isNotBlank(name)) {
            wrapper.like(UserEntity::getName, name);
        }
        return pageForVO(page, wrapper);
    }
}
```

### 2. 树形结构处理系统 (Tree System)

#### TreeNode 接口
定义树形数据的基本契约：
```java
public interface TreeNode<T> {
    String id();                    // 节点唯一标识
    String pid();                   // 父节点标识
    void children(List<T> children); // 设置子节点
    
    // 可选方法
    default String parentKeys() { return null; }
    default int level() { throw new NotSupportedException(); }
    default int order() { throw new NotSupportedException(); }
}
```

#### TreeBuilder 构建器
高性能树形数据构建，支持三种构建策略：

**标准构建：**
```java
List<TreeNode> treeData = TreeBuilder.build(flatList);
```

**优化构建（不生成空子节点）：**
```java
List<TreeNode> treeData = TreeBuilder.build2(flatList, 
    TreeNode::id, TreeNode::pid, TreeNode::children);
```

**基于层级的构建：**
```java
List<TreeNode> treeData = TreeBuilder.build3(flatList,
    TreeNode::id, TreeNode::pid, TreeNode::level, TreeNode::children);
```

#### 树形数据过滤 (TreeDataFilter)
保持父子关系的智能过滤：
```java
@Autowired
private TreeDataFilter treeDataFilter;

public List<Organization> filterTree(List<Organization> orgList, String searchName) {
    return treeDataFilter.filter(
        orgList,
        searchName,                    // 查询字符串
        Organization::getName,         // 查询字段
        Organization::getId,           // ID 字段
        Organization::getParentKeys,   // 完整路径字段
        target -> target.stream()      // 排序函数
            .sorted(Comparator.comparing(Organization::getLevel))
            .collect(Collectors.toList())
    );
}
```

#### 树节点拖拽操作 (TreeNodeUtils)
支持树节点的拖拽移动：
```java
// 获取拖拽后所有变更的节点
List<T> changedNodes = TreeNodeUtils.getAllChangedAfterMoved(
    sourceNode,                    // 被拖拽的节点
    targetNode,                    // 目标节点
    TreeDragMode.CHILD,           // 拖拽模式：CHILD, BEFORE, AFTER
    this::getDirectChildren,       // 直接子节点获取函数
    this::getAllChildren          // 所有子节点获取函数
);
```

### 3. 分页查询框架 (Pagination)

**主要功能：**
- 统一的分页查询基类
- 多字段排序支持
- SQL 注入防护
- 自动驼峰转下划线
- 性能优化选项

**核心特性：**
```java
public abstract class PageQuery<T> extends BasePageDTO<T> {
    // 排序字段和方向
    private String orderBy;
    private String orderDirection = "ASC";
    
    // 是否查询总数（性能优化）
    private Boolean searchCount = true;
    
    // 获取 MyBatis Plus 分页对象
    public Page<T> getPage() { ... }
}
```

**使用示例：**
```java
public class UserQueryDTO extends PageQuery<UserEntity> {
    private String name;
    private String email;
    
    @Override
    public LambdaQueryWrapper<UserEntity> getLambdaQueryWrapper() {
        LambdaQueryWrapper<UserEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StringUtils.isNotBlank(name), UserEntity::getName, name);
        wrapper.eq(StringUtils.isNotBlank(email), UserEntity::getEmail, email);
        return wrapper;
    }
}

// 在 Controller 中使用
@PostMapping("/page")
public Page<UserVO> page(@Valid @RequestBody UserQueryDTO queryDTO) {
    return userService.pageForVO(queryDTO.getPage(), queryDTO.getLambdaQueryWrapper());
}
```

**链式调用支持：**
```java
UserQueryDTO query = new UserQueryDTO()
    .page(1)                      // 设置页码
    .size(20)                     // 设置页大小
    .orderByDesc("createTime")    // 按创建时间降序
    .addOrderByAsc("name")        // 追加按名称升序
    .disableSearchCount();        // 禁用总数查询（性能优化）
```

### 4. 用户身份管理 (LoginUserDetails)

**主要功能：**
- 实现 `LoginUser` 接口
- 支持多租户和组织架构
- 角色权限管理
- 账户状态控制

**核心属性：**
```java
@Data
public class LoginUserDetails implements LoginUser {
    private String username;        // 用户名
    private String password;        // 密码（JSON 忽略）
    private String nickname;        // 昵称
    private String orgId;          // 组织 ID
    private String tenantId;       // 租户 ID
    private Set<String> roles;     // 角色集合
    private Boolean accountExpired; // 账户是否过期
    private Boolean accountLocked;  // 账户是否锁定
    private Date expiredTime;      // 过期时间
}
```

**角色检查方法：**
```java
// 角色权限检查
public boolean isDev()           // 开发者角色
public boolean isAdmin()         // 管理员角色
public boolean isManager()       // 管理者角色
public boolean isTenantManager() // 租户管理员角色
public boolean isTenant()        // 租户角色
public boolean isSystem()        // 系统角色
```

**工具类使用：**
```java
// 获取当前登录用户
LoginUserDetails user = LoginUserUtils.getLoginUser();

// 获取当前租户 ID
String tenantId = LoginUserUtils.getTenantId();
```

### 5. 类型转换工具 (ConvertFunctions)

**MapStruct 兼容的转换函数：**
```java
public interface ConvertFunctions {
    
    @Named("mapAccountExpired")
    static Boolean mapAccountExpired(LocalDateTime expiredTime) {
        return expiredTime != null && !expiredTime.isAfter(LocalDateTime.now());
    }
    
    @Named("mapAccountLocked")
    static Boolean mapAccountLocked(boolean enabled) {
        return !enabled;
    }
    
    @Named("mapAccountEnabled")
    static Integer mapAccountEnabled(boolean enabled) {
        return enabled ? 1 : 0;
    }
}
```

### 6. 注解处理 (@DictMapper)

**字典映射注解：**
```java
@DictMapper(
    dictName = "USER_STATUS",
    dictUsage = 0,
    dictDesc = "用户状态",
    key = "key",
    val = "val"
)
public enum UserStatus {
    ACTIVE(1, "激活"),
    INACTIVE(0, "禁用");
    
    private final Integer val;
    private final String key;
}
```

### 7. 工具类库

#### SQL 参数工具 (SqlParamUtils)
```java
// 模糊查询参数处理，自动转义特殊字符
String fuzzyParam = SqlParamUtils.fuzzyQuery("test_value");
// 结果: %test\_value%
```

#### 系统常量 (FusionConstants)
```java
// 系统基础常量
FusionConstants.ENABLED          // 1
FusionConstants.DISABLED         // 0
FusionConstants.TENANT_ID        // "tenant_id"

// 角色权限常量
FusionConstants.ROLE_ADMIN       // "ROLE_ADMIN"
FusionConstants.ROLE_USER        // "ROLE_USER"
FusionConstants.ROLE_MANAGER     // "ROLE_MANAGER"

// HTTP 相关常量
FusionConstants.AUTHORIZATION    // "Authorization"
FusionConstants.BEARER          // "Bearer "
FusionConstants.CONTENT_TYPE    // "Content-Type"

// 字符串分隔符
FusionConstants.DELIMITER       // ","
FusionConstants.JOINER         // "-"
FusionConstants.DOT            // "."
```