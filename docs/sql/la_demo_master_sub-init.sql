-- =====================================================================
-- 动态表单主从测试演示：采购订单（真实业务模拟）
--
-- 一个主从表单同时覆盖两种模式：
--   一对多（INLINE 内联 / LIST 台账式）：主表 la_demo_order ← 从表 la_demo_order_item（订单明细）
--   一对一（FLAT 扁平）              ：主表 la_demo_order ← 从表 la_demo_order_payment（支付信息）
--
-- 表单设计器配置建议（基础与数据 → 主从关系）：
--   关系1：子表 la_demo_order_item    关系标识 order_item   外键 ORDER_ID  呈现方式=内联编辑（一对多）
--   关系2：子表 la_demo_order_payment 关系标识 order_payment 外键 ORDER_ID  呈现方式=一对一扁平（FLAT）
--          （也可配成台账式（LIST）体验详情页分页维护子行）
--   配置完成后「导入子表字段」，再到步骤③把需要展示的列勾出来。
--
-- 引擎约定：
--   ID 为 19 位雪花 id（引擎生成）；审计四列与 TENANT_ID 由引擎/租户插件填充，不映射为表单字段；
--   外键列的值由引擎按主行 id 回填，业务上无含义；FLAT 从表外键列建议建唯一索引（一对一约束 + 并发安全）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 主表：采购订单
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `la_demo_order`;
CREATE TABLE `la_demo_order` (
  `ID`          varchar(32)  NOT NULL COMMENT '主键（引擎雪花 id）',
  `ORDER_NO`    varchar(32)  NOT NULL COMMENT '订单编号（唯一）',
  `SUPPLIER`    varchar(128) NOT NULL COMMENT '供应商（必填）',
  `ORDER_DATE`  date         DEFAULT NULL COMMENT '下单日期',
  `STATUS`      varchar(16)  DEFAULT NULL COMMENT '订单状态（DRAFT/SUBMITTED/APPROVED）',
  `REMARK`      varchar(500) DEFAULT NULL COMMENT '备注',
  `TENANT_ID`   varchar(64)  DEFAULT NULL COMMENT '租户（插件填充）',
  `CREATED_BY`  varchar(32)  DEFAULT NULL COMMENT '创建人（引擎填充）',
  `CREATED_AT`  datetime     DEFAULT NULL COMMENT '创建时间（引擎填充）',
  `UPDATED_BY`  varchar(32)  DEFAULT NULL COMMENT '修改人（引擎填充）',
  `UPDATED_AT`  datetime     DEFAULT NULL COMMENT '修改时间（引擎填充）',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_la_demo_order_no` (`ORDER_NO`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '采购订单（主从测试主表）';

-- ---------------------------------------------------------------------
-- 2. 从表（一对多）：订单明细 —— 一个订单多个明细行
--    ORDER_ID 是主从通道列（联查/差量更新/级联删除），必须建索引
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `la_demo_order_item`;
CREATE TABLE `la_demo_order_item` (
  `ID`         varchar(32)  NOT NULL COMMENT '主键（引擎雪花 id）',
  `ORDER_ID`   varchar(32)  NOT NULL COMMENT '外键：主行 id（引擎回填）',
  `ITEM_NAME`  varchar(128) NOT NULL COMMENT '品名（必填）',
  `QTY`        int          NOT NULL COMMENT '数量（必填）',
  `UNIT_PRICE` decimal(12,2) NOT NULL COMMENT '单价（必填）',
  `TENANT_ID`  varchar(64)  DEFAULT NULL COMMENT '租户（插件填充）',
  `CREATED_BY` varchar(32)  DEFAULT NULL COMMENT '创建人（引擎填充）',
  `CREATED_AT` datetime     DEFAULT NULL COMMENT '创建时间（引擎填充）',
  `UPDATED_BY` varchar(32)  DEFAULT NULL COMMENT '修改人（引擎填充）',
  `UPDATED_AT` datetime     DEFAULT NULL COMMENT '修改时间（引擎填充）',
  PRIMARY KEY (`ID`),
  KEY `idx_la_demo_order_item_order` (`ORDER_ID`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '订单明细（一对多从表）';

-- ---------------------------------------------------------------------
-- 3. 从表（一对一）：订单支付信息 —— 一个订单最多一条支付记录
--    ORDER_ID 唯一索引：数据库层强制一对一（同时兜住 FLAT 模式并发写入）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `la_demo_order_payment`;
CREATE TABLE `la_demo_order_payment` (
  `ID`          varchar(32)  NOT NULL COMMENT '主键（引擎雪花 id）',
  `ORDER_ID`    varchar(32)  NOT NULL COMMENT '外键：主行 id（引擎回填）',
  `PAY_METHOD`  varchar(32)  DEFAULT NULL COMMENT '支付方式（可配 DICT：0 转账/1 现金/2 票据）',
  `PAY_TIME`    datetime     DEFAULT NULL COMMENT '支付时间',
  `INVOICE_NO`  varchar(64)  DEFAULT NULL COMMENT '发票号',
  `TENANT_ID`   varchar(64)  DEFAULT NULL COMMENT '租户（插件填充）',
  `CREATED_BY`  varchar(32)  DEFAULT NULL COMMENT '创建人（引擎填充）',
  `CREATED_AT`  datetime     DEFAULT NULL COMMENT '创建时间（引擎填充）',
  `UPDATED_BY`  varchar(32)  DEFAULT NULL COMMENT '修改人（引擎填充）',
  `UPDATED_AT`  datetime     DEFAULT NULL COMMENT '修改时间（引擎填充）',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_la_demo_order_payment_order` (`ORDER_ID`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '订单支付信息（一对一从表）';

-- ---------------------------------------------------------------------
-- 4. 测试数据（幂等：脚本重建三表）
--    订单 1001：2 条明细 + 1 条支付（一对多 + 一对一齐活）
--    订单 1002：3 条明细，未支付（一对多只有一半）
--    订单 1003：无明细无支付（空子表形态）
-- ---------------------------------------------------------------------
INSERT INTO `la_demo_order`
  (`ID`, `ORDER_NO`, `SUPPLIER`, `ORDER_DATE`, `STATUS`, `REMARK`, `TENANT_ID`, `CREATED_BY`, `CREATED_AT`)
VALUES
  ('900000000000000101', 'PO-2026-1001', '华东建材有限公司',   '2026-10-08', 'APPROVED',  '月度例行采购', NULL, 'demo', '2026-10-08 09:00:00'),
  ('900000000000000102', 'PO-2026-1002', '南方五金制品厂',     '2026-10-08', 'SUBMITTED', '急件，请尽快审批', NULL, 'demo', '2026-10-08 09:10:00'),
  ('900000000000000103', 'PO-2026-1003', '北方包装材料供应商', '2026-10-08', 'DRAFT',     NULL, NULL, 'demo', '2026-10-08 09:20:00');

-- 订单 1001 的明细（一对多：2 行）
INSERT INTO `la_demo_order_item`
  (`ID`, `ORDER_ID`, `ITEM_NAME`, `QTY`, `UNIT_PRICE`, `TENANT_ID`, `CREATED_BY`, `CREATED_AT`)
VALUES
  ('910000000000000101', '900000000000000101', '镀锌钢管 DN50',   120, 45.80, NULL, 'demo', '2026-10-08 09:01:00'),
  ('910000000000000102', '900000000000000101', 'PVC 三通接头',    300,  6.50, NULL, 'demo', '2026-10-08 09:01:00');

-- 订单 1002 的明细（一对多：3 行）
INSERT INTO `la_demo_order_item`
  (`ID`, `ORDER_ID`, `ITEM_NAME`, `QTY`, `UNIT_PRICE`, `TENANT_ID`, `CREATED_BY`, `CREATED_AT`)
VALUES
  ('910000000000000103', '900000000000000102', '不锈钢合页 4 寸', 200, 12.00, NULL, 'demo', '2026-10-08 09:11:00'),
  ('910000000000000104', '900000000000000102', '自攻螺丝 M4×30',  1000, 0.35, NULL, 'demo', '2026-10-08 09:11:00'),
  ('910000000000000105', '900000000000000102', '密封胶条 5m/条',   80,  9.90, NULL, 'demo', '2026-10-08 09:11:00');

-- 支付信息（一对一：1001 已支付，1002 未支付，1003 无）
INSERT INTO `la_demo_order_payment`
  (`ID`, `ORDER_ID`, `PAY_METHOD`, `PAY_TIME`, `INVOICE_NO`, `TENANT_ID`, `CREATED_BY`, `CREATED_AT`)
VALUES
  ('920000000000000101', '900000000000000101', '0', '2026-10-08 10:00:00', 'INV-2026-0001', NULL, 'demo', '2026-10-08 10:00:00');

-- ---------------------------------------------------------------------
-- 5. 验证：三张表的行数与关联关系
--    预期：orders=3、items=5（1001→2 行、1002→3 行）、payments=1（1001→1 行）
-- ---------------------------------------------------------------------
SELECT o.`ORDER_NO`,
       o.SUPPLIER,
       (SELECT COUNT(*) FROM `la_demo_order_item` i    WHERE i.ORDER_ID = o.ID) AS item_rows,
       (SELECT COUNT(*) FROM `la_demo_order_payment` p WHERE p.ORDER_ID = o.ID) AS payment_rows
FROM `la_demo_order` o
ORDER BY o.`ORDER_NO`;
