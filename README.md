# 陪玩平台后端（pw-server）

Spring Boot 3.3.5 + MyBatis-Plus + MySQL 8 + Redis 7 + Flyway。老板端 / 打手端 / 管理端三端共用一套 API（`/api/v1`）。

## 技术栈

- Java 17+（本地开发用 21），Maven 构建
- 数据库 DDL 全部走 Flyway（`db/migration`，V1–V908），dev 种子数据在 `db/seed-dev`
- 认证：JWT；金额全程整数分（接口序列化为字符串）
- 微信登录/支付：测试号可真登录（`WX_APPID`/`WX_SECRET` 环境变量），支付为模拟模式
- 管理端权限字典下发：`GET /admin/dict/permissions`

## 启动

```bash
# 前置：本机 MySQL 8（localhost:3306）+ Redis 7（见 docker-compose.dev.yml）
mvn spring-boot:run    # 默认 8080，context-path /api/v1，dev profile 自动建表+种子数据
```

- dev 数据源在 `src/main/resources/application-dev.yml`（本地库 root/root，勿用于生产）
- E2E 验证：`python3 scripts/dev_e2e_verify.py http://localhost:8080/api/v1`（需干净库，基准 143 项）

## 模块结构（com.pw）

`auth` 登录 · `user` 用户 · `booster` 打手入驻/接单 · `order` 订单/仲裁 · `refund` 退款 · `withdraw` 提现 · `settlement` 结算 · `catalog` 类目/商品/Banner · `vip` 会员 · `admin` 管理端聚合 · `im` 消息（客服聊天+站内通知） · `common` 基础设施
