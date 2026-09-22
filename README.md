# 临时开发环境编排控制平面（本地模拟版）

一个可在本地完整验证的临时环境（ephemeral environment）控制平面：用**内存模拟资源后端**代替真实
集群/云平台，覆盖环境从申请、就绪、续期到自动回收的完整生命周期，并在并发、部分失败与模板版本
演进下保持结果可预期。

- Java 21 + Spring Boot 3.4，无外部数据库/集群/云依赖
- 身份仅通过请求头 `X-Principal` 注入（仅用于本地演示，测试不使用任何真实凭据）
- 所有判定依据均通过 SLF4J 打印到测试日志（输入 + 判定结果）

---

## 1. 生命周期规则

状态机（非法跳变一律以 `ILLEGAL_TRANSITION` 拒绝）：

```
REQUESTED ──► PROVISIONING ──► READY ──► EXPIRED ──► RELEASING ──► RELEASED
   │              │  │           │                       ▲
   └──────────────┴──┴─► FAILED ─┴───────────────────────┘
```

| 规则 | 行为 |
|---|---|
| 申请 | `POST /api/tenants/{t}/envs`。环境 id 由 `tenant--name` 确定性生成 |
| **重复申请幂等** | 同一 `(租户, 名称)` 的重复申请返回**同一个环境**；并发重复申请只创建一个 |
| 名称校验 | 须匹配 `[a-z0-9][a-z0-9-]{1,38}[a-z0-9]`，否则 `VALIDATION_ERROR` |
| 名称冲突 | 名称仍被非终态环境占用时重复申请返回同一环境；终态 `RELEASED` 后名称可再次申请 |
| 失败不成 ready | 分配部分失败时环境进入 **FAILED**（可查询原因），已分配的资源回滚，**绝不标记 READY**，HTTP 返回 409 |
| 续期 | 只有 READY 可续期；在到期瞬间（`now >= expiresAt`）续期返回 `EXPIRED` |
| 回收 | `DELETE` 幂等：不存在 / 已回收返回 204，不报错；后端释放幂等，杜绝重复释放 |
| 过期 | 到期扫描先 READY→EXPIRED（审计留痕），再 RELEASING→RELEASED；EXPIRED 仍持有资源/配额直到真正释放 |

### 续期 vs 回收的并发性

每个环境有稳定的条带锁；所有续期/回收/申请在该锁内线性化。因此只可能有一个结果：

- 续期先提交新租约 → 扫描观察到新 `expiresAt`，环境保持 READY；
- 回收先进入 RELEASING → 续期得到 `EXPIRED`/`ENV_NOT_FOUND`，**不会出现既续期又被回收的半更新**。

到期判定边界：`now >= expiresAt` 即过期（契约固定，测试在边界处断言确定性结果）。

### 部分失败诊断与重启收敛

- 分配第 N 个单元失败：环境停留在 FAILED 并保存 `failureReason`（如 `unit=1`），先前单元立即回滚，
  配额立即归还；失败状态仍可通过 GET 查询。
- `POST /api/admin/reconcile` 提供两类收敛（均有批次上限）：
  - **孤立资源**：后端存在但不属于任何仍持有资源的环境（REQUESTED/PROVISIONING/READY/EXPIRED）
    的资源被释放；**仍在有效期内的环境绝不会被误删**。
  - **卡住的 RELEASING**：崩溃在迁移与释放完成之间时，安全驱动到 RELEASED，配额精确归还一次。
- 测试通过"重置持久化环境表但保留后端资源 + 重建配额账本"模拟进程重启。

---

## 2. 配额模型

按租户配置（`DataSeeder` 本地种子）：

| 租户 | 最大活跃环境数 | 最大资源单元数 |
|---|---|---|
| alpha | 3 | 6 |
| beta | 2 | 4 |

- 每个租户一把监视器锁：**校验 + 预留是原子操作**，并发申请不会超额（有竞争测试断言 12 线程抢 2 个名额）。
- 只有 `REQUESTED/PROVISIONING/READY/EXPIRED/RELEASING` 占用配额；`FAILED/RELEASED` 不占。
- 回收（含失败回滚、过期回收、收敛）必然释放配额，**无幽灵占用**。
- 资源单元数由模板渲染决定（v1 固定 1；v2 的 `replicas`，默认 1，范围 1..10）。
- 未知租户 → `TENANT_NOT_FOUND`；超额 → `QUOTA_EXCEEDED`（环境数与单元数两条原因文案不同，可区分）。
- 重启后 `recompute()` 以环境集合为权威重建计数。

---

## 3. 访问控制（基于所有者 + 角色）

本地主体（`X-Principal`）：

| 主体 | 租户 | 角色 |
|---|---|---|
| alice | alpha | OWNER |
| bob   | alpha | MEMBER |
| carol | alpha | VIEWER |
| dave  | beta  | OWNER |

| 操作 | VIEWER | MEMBER（非自己环境） | 环境 owner / OWNER |
|---|---|---|---|
| 列表 / 非敏感详情 | ✅ | ✅ | ✅ |
| **读取敏感配置** (`?secrets=true`) | ❌ 403 | ❌（除非是 owner） | ✅ |
| 续期 / 回收 | ❌ 403 | ❌（除非是 owner） | ✅ |
| `/api/admin/*` 扫描、收敛 | ❌ | ❌ | ✅（OWNER） |

- 敏感值（如 `api_key`）**只在显式授权且 `secrets=true` 时**返回；普通投影中 `secrets` 为空 map。
- 跨租户请求一律被拒绝（读路径对不存在/跨租户返回同样泛化的错误），**不泄露环境是否存在或内部细节**。
- 缺少身份 → `UNAUTHENTICATED`；越权 → 泛化 `FORBIDDEN: access denied`。

---

## 4. 模板兼容策略

模板 `demo` 内置两个版本：

| 版本 | 支持字段 | 默认值 / 语义 |
|---|---|---|
| `1` | `image`, `api_key` | image 默认 `registry.local/default:1.0`；api_key 缺省自动生成；单元数固定 1 |
| `2` | `image`, `api_key`, `replicas` | 新增 `replicas`（缺省 **1**，1..10），决定资源单元数 |

规则：

- **未知版本** → `TEMPLATE_NOT_FOUND`（不回退、不静默）。
- **版本不认识的字段** → `UNSUPPORTED_TEMPLATE_FIELD`（如对 v1 传 `replicas`、对 v2 传 `gpu`），绝不静默忽略。
- **新增字段带固定默认值**：v1 请求在 v2 存在后渲染语义不变（单元数仍为 1）。
- **模板升级不追溯**：已 READY 的环境保留其创建时的版本、单元数与资源，不受新版本影响（有测试断言）。

---

## 5. 审计与日志

- 每次状态变更写一条不可变审计：`id / 时间 / 租户 / 环境 / 触发主体 / 动作 / 前状态 / 后状态 / 原因`。
- 审计接口在方法签名层面就不接收敏感参数；测试断言密钥字符串不出现在任何审计记录中。
- 审计存储有界（默认 2000 条，超出淘汰最旧），支持租户隔离与 offset/limit 分页。
- 业务日志只记录 id、主体、状态与判定依据，显式标注 "secrets never logged"；异常按错误类型分别记录，不吞异常。
- 查询：`GET /api/tenants/{t}/envs/{id}/audit?offset=0&limit=50`。

---

## 6. 有界扫描 / 批量查询

`application.properties`：

| 配置 | 默认 | 含义 |
|---|---|---|
| `cp.scan-page-size` | 100 | 单次过期扫描最多处理的候选数，剩余下一轮处理 |
| `cp.scan-budget-millis` | 1000 | 单轮扫描时间预算（纳秒级判定），超出即延迟到下一轮 |
| `cp.bulk-query-limit` | 200 | 列表接口的最大 `limit`，超出返回 `BATCH_LIMIT_EXCEEDED` |
| `cp.reaper-interval-millis` | 5000 | 调度间隔（测试中设为极大值以获得确定性） |
| `cp.reconcile-batch-size` | 100 | 单轮收敛最多处理的资源/卡住环境数 |

候选集合是有限快照（非无界流），内存占用可预期。

---

## 7. HTTP API 摘要

```
POST   /api/tenants/{tenant}/envs                 申请（重复申请幂等）
GET    /api/tenants/{tenant}/envs?limit=50        列表（超 bulk 上限拒绝）
GET    /api/tenants/{tenant}/envs/{id}            详情；?secrets=true 需授权
POST   /api/tenants/{tenant}/envs/{id}/renew      续期（body {"ttlSeconds":7200}）
DELETE /api/tenants/{tenant}/envs/{id}            回收（幂等）
GET    /api/tenants/{tenant}/envs/{id}/audit      审计记录（分页）
POST   /api/admin/sweep                           手动触发一轮有界过期扫描（OWNER）
POST   /api/admin/reconcile                       孤立资源 + 卡住释放收敛（OWNER）
```

错误响应统一为 `{"code":"<ERROR_CODE>","message":"..."}`，HTTP 状态码与错误码一一对应
（400/401/403/404/409/413/503）。

---

## 8. 本地验证方法

### 运行全部测试（无需任何外部服务）

```bash
mvn test
```

36 个测试，覆盖：生命周期与幂等、配额竞争（含 12 线程并发）、越权访问、模板版本不兼容、
续期/回收边界并发（24 轮）、部分失败回滚、重启后孤立资源与卡住释放收敛、有界扫描、审计不含密
钥以及完整 HTTP 链路。单测日志会打印每个用例的输入与判定依据（搜索 `decision`）。

### 启动服务手工验证

```bash
mvn spring-boot:run
```

示例（alice 属于 alpha，具有 OWNER 权限）：

```bash
# 申请
curl -s -X POST localhost:8080/api/tenants/alpha/envs \
  -H 'X-Principal: alice' -H 'Content-Type: application/json' \
  -d '{"name":"demo-env","templateId":"demo","version":"2","parameters":{"replicas":"2"},"ttlSeconds":600}'

# 重复申请 -> 200 且返回同一 id（幂等）
curl -s -X POST localhost:8080/api/tenants/alpha/envs -H 'X-Principal: alice' \
  -H 'Content-Type: application/json' \
  -d '{"name":"demo-env","templateId":"demo","version":"2","parameters":{"replicas":"2"},"ttlSeconds":600}'

# VIEWER 读取密钥 -> 403
curl -i 'localhost:8080/api/tenants/alpha/envs/alpha--demo-env?secrets=true' -H 'X-Principal: carol'

# 续期 / 回收
curl -s -X POST localhost:8080/api/tenants/alpha/envs/alpha--demo-env/renew \
  -H 'X-Principal: alice' -H 'Content-Type: application/json' -d '{"ttlSeconds":1800}'
curl -i -X DELETE localhost:8080/api/tenants/alpha/envs/alpha--demo-env -H 'X-Principal: alice'

# 手动扫描与收敛（仅 OWNER）
curl -s -X POST localhost:8080/api/admin/sweep     -H 'X-Principal: alice'
curl -s -X POST localhost:8080/api/admin/reconcile -H 'X-Principal: alice'
```

### 模拟部分失败 / 重启收敛

- 单元分配失败通过 `SimulatedResourceBackend.failAllocationAt(envId, unitIndex)` 注入，
  测试 `ReconcileConvergenceTest.partialFailureLeavesQueryableStateAndNoLeak` 演示。
- 重启收敛由测试直接驱动：清空环境表（模拟仅后端资源幸存）→ 调 `reconcileOrphans()`；
  将环境置为 RELEASING（模拟崩溃）→ 调 `reconcileStuckReleases()`。
