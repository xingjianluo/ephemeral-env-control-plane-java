# 临时开发环境编排控制平面（本地模拟）

一套可在**本地完全验证**的临时环境生命周期控制平面。后端资源用内存模拟器代替真实集群/云平台，
不依赖任何外部服务或真实凭据；覆盖“申请 → 就绪 → 续期 → 挂起/恢复 → 回收”的完整生命周期，
并在并发、部分失败、模板版本演进取景下保持行为可预期。

- Java 21 · Spring Boot 3.4 · 内存存储（ConcurrentMap）· JUnit 5 / Spring MockMvc

---

## 1. 快速开始

```bash
# 构建并运行全部测试（53 个，含并发/失败收敛/挂起恢复场景）
mvn test

# 启动服务（默认写入演示数据）
mvn spring-boot:run
```

服务启动后监听 `8080`。所有业务请求通过三个请求头声明访问主体（本地模拟鉴权）：

| Header | 含义 | 演示值 |
| --- | --- | --- |
| `X-Subject` | 用户名 | `alice` / `bob` / `carol` / `dave` |
| `X-Tenant` | 租户（团队） | `team-a` / `team-b` |
| `X-Role` | 租户内角色 | `TENANT_ADMIN` / `OWNER` / `VIEWER` |

演示数据（`ecp.bootstrap-demo=true` 时自动创建）：

- 模板：`v1`（字段 `image/replicas/dbPassword`）、`v2`（仅新增 `featureFlag`）；
- 配额：`team-a` = 2 环境 / 4 CPU / 2048MB；`team-b` = 1 环境 / 4 CPU / 2048MB；
- 主体：`alice`(team-a 管理员)、`bob`(team-a 所有者)、`carol`(team-a 只读)、`dave`(team-b 管理员)。

### 端到端冒烟示例

```bash
# 申请（敏感字段 dbPassword 在普通响应中被掩码为 ***）
curl -s -X POST localhost:8080/api/environments \
  -H 'Content-Type: application/json' \
  -H 'X-Subject: bob' -H 'X-Tenant: team-a' -H 'X-Role: OWNER' \
  -d '{
  "tenantId":"team-a","name":"demo","owner":"bob","templateVersion":"v2","ttlSeconds":120,
  "resources":[{"kind":"service","image":"app:1.0","cpu":1,"memoryMb":256}],
  "configOverrides":{"dbPassword":"db-pw","featureFlag":true}}'

# 查看（非敏感视图） / 读取敏感配置（需授权）
curl -s -H X-Subject:bob -H X-Tenant:team-a -H X-Role:OWNER \
  'localhost:8080/api/environments/demo?tenantId=team-a'
curl -s -H X-Subject:bob -H X-Tenant:team-a -H X-Role:OWNER \
  'localhost:8080/api/environments/demo/secret?tenantId=team-a'

# 续期 / 回收（回收幂等，重复调用不报错、不重复释放）
curl -s -X POST localhost:8080/api/environments/demo/renew \
  -H 'Content-Type: application/json' \
  -H 'X-Subject: bob' -H 'X-Tenant: team-a' -H 'X-Role: OWNER' \
  -d '{"tenantId":"team-a","ttlSeconds":300}'
curl -s -H X-Subject:bob -H X-Tenant:team-a -H X-Role:OWNER \
  -X DELETE 'localhost:8080/api/environments/demo?tenantId=team-a&reason=finished'

# 挂起 / 恢复（均幂等；挂起让出计算资源与配额，恢复按固化快照重建）
curl -s -X POST -H X-Subject:bob -H X-Tenant:team-a -H X-Role:OWNER \
  'localhost:8080/api/environments/demo/suspend?tenantId=team-a'
curl -s -X POST -H X-Subject:bob -H X-Tenant:team-a -H X-Role:OWNER \
  'localhost:8080/api/environments/demo/resume?tenantId=team-a'

# 手动触发到期扫描 / 重启后孤立资源收敛
curl -s -X POST 'localhost:8080/api/admin/scan-expiries?maxBatch=50&maxMillis=200'
curl -s -X POST 'localhost:8080/api/admin/reconcile?limit=50&maxMillis=200'

# 审计查询（租户隔离）
curl -s -H X-Subject:alice -H X-Tenant:team-a -H X-Role:TENANT_ADMIN \
  'localhost:8080/api/audit?envName=demo&limit=20'
```

---

## 2. 环境生命周期规则

### 2.1 状态机（禁止跳变）

```
PENDING ──► PROVISIONING ──► READY
                  │             │
                  ▼             ▼
                FAILED ──► RELEASING ──► RELEASED（终态）
                             ▲
                   READY ────┘

READY ──► SUSPENDING ──► SUSPENDED ──► RESUMING ──► READY
                              │            │
                              │            └──► SUSPENDED（恢复失败/收敛回滚）
                              └──► RELEASING ──► RELEASED（挂起不免回收）
```

- 合法转换仅为：`PENDING→PROVISIONING→READY|FAILED`、`READY|FAILED→RELEASING→RELEASED`、
  `READY→SUSPENDING→SUSPENDED→RESUMING→READY`、`RESUMING→SUSPENDED`（恢复失败回滚）、
  `SUSPENDED→RELEASING`（挂中到期/超保留期清理）；
  释放中途失败允许 `RELEASING→FAILED` 的回退判定路径以外，任何跳变（如 `FAILED→READY`、
  `RELEASED→任何`）都抛 `INVALID_STATE`。
- **失败的环境永远不会被标记为 READY**，并保留可查询的 `failureReason`。
- 续期不改变状态，只把到期时间推后；仅 `READY` 可续期（挂起中须先恢复再续期）。

### 2.2 幂等

- **申请**：同租户同名 + 同参数（owner/templateVersion/ttl/资源规格指纹一致）重复申请，
  返回同一个环境，不重复分配资源、不重复占配额；同名但参数不同 → `NAME_CONFLICT`。
- **回收**：对已 `RELEASED` 的环境重复回收返回同一终态，不报错、不重复释放资源/配额。
- **挂起**：对已 `SUSPENDED` 的环境重复挂起返回当前表示，不重复释放资源/配额。
- **恢复**：对已 `READY` 的环境重复恢复返回当前表示，不重复占用配额/分配资源。

### 2.3 租约、到期与并发确定性

- 每个环境有 `expiresAtMillis`；`READY` 与 `SUSPENDED` 都参与到期判定（挂起不免回收）。
- 同租户的所有写操作（申请/续期/挂起/恢复/回收/到期回收）都在同一把**租户锁**内串行。
- “到期判定 + 回收”在锁内基于仓库最新状态完成：
  - 到期前发生续期 → 续期成功，后到的扫描看到新到期时间并跳过（**不会续完又被回收**）；
  - 已到期发生续期 → 先完成回收，续期得到 `INVALID_STATE`，环境保持 `RELEASED`（**不会复活**）；
  - 已到期发生挂起/恢复 → 同样先完成回收，挂起/恢复得到 `INVALID_STATE`（**回收赢**）；
    恢复与到期扫描并发时，回收恰好发生一次，恢复方被明确拒绝，不出现半更新。
- 到期扫描器默认关闭（`ecp.lease.scan-enabled=false`），可配置打开或用管理接口手动触发。

### 2.4 挂起与恢复

- **挂起（suspend）**：`READY` 环境可被挂起。挂起释放全部后端计算资源并归还配额
  （CPU/内存/环境槽位立即被同租户其他环境复用），但环境本体仍然存在、可被有权者查到，
  归属、身份与固化的配置快照全部保持不变。
- **恢复（resume）**：按挂起前固化的资源规格重建后端资源，**不重新渲染模板**——
  挂起之后注册的模板新版本不会追溯改变挂起前已固化的配置快照与模板版本。
- **配额联动**：恢复需要重新占用配额；此刻配额若已被其他环境占满，恢复返回
  `QUOTA_EXCEEDED`（HTTP 409）被明确拒绝，环境**稳定停在 SUSPENDED** 可稍后重试，
  绝不部分恢复、不留幽灵占用。
- **与有效期联动**：挂起不等于免回收——挂起中的环境仍受原 `expiresAtMillis` 约束，
  到期被扫描器自动清理；另有可配置的挂起最长保留时间 `ecp.suspend.max-retention-ms`
  （默认 24h），超时同样被清理，防止挂起环境无界堆积。
- **失败与中间态**：挂起过程后端释放失败 → 停留可查询的 `SUSPENDING` 中间态
  （配额仍持有，无半更新），由收敛器完成；恢复过程后端分配失败 → 回滚部分资源与配额，
  稳定回到 `SUSPENDED`；进程崩溃卡在 `RESUMING` 的，重启收敛回滚到 `SUSPENDED` 并可重试。
  收敛器对正常挂起的环境绝不动（不误删、不误改）。

---

## 3. 配额与命名隔离

- 配额按租户独立计量：`maxEnvs`（环境数）、`maxCpu`、`maxMemoryMb`，
  超限抛 `QUOTA_EXCEEDED`，消息明确指出是哪一维度（便于区分原因）。
- **“配额检查 + 占用”与环境落库在同一个租户临界区内**，且配额预留先于 PENDING 记录落库：
  被拒绝的请求不会留下幽灵环境或幽灵占用；24 线程并发申请的测试证明不会超额分配。
- 环境在以下时机恰好归还一次配额（`quotaReleased` 标记守卫）：
  供应失败回滚、**挂起成功**、恢复失败回滚、回收成功、重启收敛清理。
  挂起/销毁后槽位立即可被新申请使用；恢复时重新占用配额，不足则明确拒绝。
- 命名按 `租户 + 名称` 隔离：不同租户可使用相同名称；名称必须匹配 `[A-Za-z0-9._-]{1,64}`。

---

## 4. 访问控制模型（RBAC + 所有者 + 租户隔离）

| 权限 | TENANT_ADMIN | OWNER | VIEWER |
| --- | --- | --- | --- |
| 查看环境非敏感信息 (`VIEW_ENV`) | 本租户任意 | 仅本人所有 | 本租户任意 |
| 查看敏感配置 (`VIEW_SENSITIVE_CONFIG`) | 本租户任意 | 仅本人所有 | 禁止 |
| 续期 (`RENEW`) / 回收 (`RELEASE`) | 本租户任意 | 仅本人所有 | 禁止 |
| 挂起 (`SUSPEND`) / 恢复 (`RESUME`) | 本租户任意 | 仅本人所有 | 禁止 |

- 跨租户请求一律 `FORBIDDEN`（通用文案 `access denied`，不暴露目标是否存在）。
- 敏感值（模板的 secret 字段，演示中为 `dbPassword`）：
  - 普通环境视图中固化为掩码 `***`；
  - 只能通过独立的 `/secret` 端点、经 `VIEW_SENSITIVE_CONFIG` 授权获取；
  - 回收时立即从环境对象清除；
  - **绝不写入审计记录或应用日志**。
- 主体必须先注册；请求头声明角色与注册角色不符 → `UNAUTHORIZED`（防角色伪造）。

---

## 5. 配置模板版本兼容策略

模板采用**严格模式**，不支持静默忽略：

1. **渲染时校验**
   - 版本未注册 → `TEMPLATE_INCOMPATIBLE`（unsupported version）；
   - 请求包含该版本未声明的字段 → `TEMPLATE_INCOMPATIBLE`（unknown field，绝不静默忽略）；
   - 未提供的字段取该版本声明的默认值（语义稳定）。
2. **演进时校验**（注册新版本）
   - 只允许相对上一版本**新增**字段且新字段必须带默认值；
   - 删除既有字段、修改既有字段默认值、变更敏感字段名，均拒绝注册。
3. **不追溯**：环境在就绪时把渲染结果固化为快照，之后注册任何新版本都不改变已就绪环境；
   测试 `templateUpgradeDoesNotRetroactivelyChangeReadyEnvironments` 覆盖此点。

---

## 6. 部分失败诊断与重启收敛

- **供应部分失败**：逐资源分配，任一失败则尽力回滚已分配资源、归还配额、落 `FAILED`
  可查询中间态并把原始后端异常抛出——不返回成功响应、不伪装就绪。
- **回收部分失败**：停留在 `RELEASING`，保留残留资源记录与配额占用，
  资源释放接口幂等可重试；收敛器或下一次回收完成后才进入 `RELEASED`。
- **收敛器（`POST /api/admin/reconcile`）两阶段**：
  1. 清理孤立后端资源：所属环境不存在或已 `RELEASED` 的资源删除；
  2. 收敛卡住/失败环境：`PENDING/PROVISIONING/FAILED/RELEASING` 清理残留并归还配额；
     `SUSPENDING` 继续完成挂起、`RESUMING` 回滚到 `SUSPENDED`、`SUSPENDED` 仅清理崩溃
     窗口的后端残留；**`READY` 且未过期、正常 `SUSPENDED` 的环境绝不动**（不误删有效环境）。
- 扫描/收敛均受 `maxBatch`（条数）与 `maxMillis`（耗时）双上限约束，
  超限即分页停止、下一轮继续；列表/审计查询受 `ecp.list-max-limit` 约束，
  超限返回 `BATCH_LIMIT`（HTTP 429），要求分页。审计存储带 10000 条硬上限，避免无界内存增长。

---

## 7. 审计

每次状态变更追加一条审计记录：`actor / tenant / env / action / reason / before / after / time`，
单调递增 id、倒序查询。动作包括 `APPLY, PROVISION, MARK_READY, RENEW, SUSPEND, RESUME,
RELEASE, EXPIRE, RECONCILE, REJECT`。系统动作（扫描、收敛）的 actor 记为 `system`。
挂起、恢复以及挂起中的到期/保留期清理都会留下可查询的审计记录（触发主体、原因、前后状态）。
异常链路通过错误码与日志（4xx 摘要、5xx 完整栈）区分，不被吞掉。

---

## 8. HTTP 接口与错误码

| 方法/路径 | 说明 |
| --- | --- |
| `POST /api/environments` | 申请（幂等） |
| `GET  /api/environments?limit=` | 列出本租户环境（有上限） |
| `GET  /api/environments/{name}?tenantId=` | 查看非敏感视图 |
| `GET  /api/environments/{name}/secret?tenantId=` | 读取敏感配置（需授权） |
| `POST /api/environments/{name}/renew` | 续期 |
| `POST /api/environments/{name}/suspend?tenantId=` | 挂起（幂等，让出计算资源与配额） |
| `POST /api/environments/{name}/resume?tenantId=` | 恢复（幂等，重新占用配额，不足则 409 拒绝） |
| `DELETE /api/environments/{name}?tenantId=&reason=` | 回收（幂等） |
| `GET  /api/audit?envName=&limit=` | 审计查询（租户隔离） |
| `POST /api/admin/quotas` / `GET /api/admin/quotas` | 设置/查看配额 |
| `POST /api/admin/scan-expiries` | 手动到期扫描 |
| `POST /api/admin/reconcile` | 手动孤立资源收敛 |

| error 码 | HTTP | 含义 |
| --- | --- | --- |
| `VALIDATION` | 400 | 非法参数 |
| `TEMPLATE_INCOMPATIBLE` | 400 | 不支持的版本/字段、破坏兼容的模板注册 |
| `UNAUTHORIZED` | 401 | 缺少/伪造主体 |
| `FORBIDDEN` | 403 | 越权（跨租户/角色不足） |
| `NOT_FOUND` | 404 | 环境不存在 |
| `NAME_CONFLICT` / `INVALID_STATE` / `QUOTA_EXCEEDED` | 409 | 名称冲突 / 非法状态转换 / 配额超限 |
| `BATCH_LIMIT` | 429 | 查询或批量规模超配置上限 |
| `BACKEND_FAILURE` | 503 | 模拟后端分配/释放失败（中间态可查询） |

---

## 9. 配置项（`application.properties`）

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `ecp.bootstrap-demo` | true | 是否写入演示模板/配额/主体 |
| `ecp.list-max-limit` | 100 | 列表/审计查询条数硬上限 |
| `ecp.lease.scan-enabled` | false | 是否启用自动到期调度（测试默认关） |
| `ecp.lease.scan-interval-ms` | 1000 | 调度间隔 |
| `ecp.lease.default-ttl-seconds` | 300 | 申请未指定 TTL 时的默认值 |
| `ecp.lease.max-ttl-seconds` | 3600 | TTL 上限（超出拒绝） |
| `ecp.scan.max-batch` | 50 | 扫描/收敛每轮处理条数上限 |
| `ecp.scan.max-millis` | 200 | 扫描/收敛每轮耗时上限（毫秒） |
| `ecp.suspend.max-retention-ms` | 86400000 | 挂起环境最长保留时间（超时自动清理，防无界堆积） |

---

## 10. 测试与本地验证方法

测试不启动真实集群、不连外部服务、不使用真实凭据；单测手工装配组件并使用**可推进的固定时钟**，
日志中打印每轮输入与判定依据（`TEST ... -> expect ...` / `判定依据: ...`，见 surefire 输出）。

```bash
mvn test                      # 全量
mvn test -Dtest=LifecycleTest # 生命周期与幂等（8）
mvn test -Dtest=QuotaConcurrencyTest     # 配额竞争（24 线程）与归还（3）
mvn test -Dtest=AccessControlTest        # 越权/跨租户/密钥隔离（6）
mvn test -Dtest=TemplateCompatibilityTest# 模板版本兼容（6）
mvn test -Dtest=RenewExpiryConcurrencyTest  # 续期 vs 到期并发确定性（2）
mvn test -Dtest=SuspendResumeTest  # 挂起/恢复：幂等、配额竞争、到期联动、越权、失败收敛（14）
mvn test -Dtest=ReconciliationTest       # 部分失败/孤立资源/有界扫描（6）
mvn test -Dtest=AuditAndValidationTest   # 审计/参数/批量上限（5）
mvn test -Dtest=HttpApiIntegrationTest   # HTTP 全链路 RBAC/错误码（2）
```

覆盖的关键需求映射：

- 并发续期与回收 → `RenewExpiryConcurrencyTest`（到期前续期赢、到期后回收赢，各 8 线程竞争）
- 挂起/恢复幂等与配置快照稳定 → `SuspendResumeTest.suspendAndResumeAreIdempotentAndConfigStaysFrozen`
- 挂起归还配额、恢复遇配额竞争被明确拒绝并可重试 → `SuspendResumeTest.suspendReturnsQuotaAndResumeLosesRaceStaysSuspended`
- 挂起与到期/回收并发确定性、挂中到期与保留期清理 → `SuspendResumeTest`（race/scanner/retention 用例）
- 挂起/恢复失败中间态与重启收敛 → `SuspendResumeTest`（SUSPENDING/RESUMING 收敛用例）
- 配额竞争 → `QuotaConcurrencyTest.concurrentAppliesNeverExceedQuota`
- 越权访问与敏感值不泄露 → `AccessControlTest`、`HttpApiIntegrationTest`
- 模板版本不兼容 → `TemplateCompatibilityTest`
- 部分失败后的收敛 → `ReconciliationTest`（含资源后端失败注入 `failAllocateRule` /
  `failNextReleaseOnce`，以及手工注入孤立资源模拟重启残留）

### 失败注入 API（模拟后端，供手工/测试验证）

`InMemoryResourceBackend` 提供：
- `failAllocateRule(Predicate<AllocateContext>)`：按 (envId, 规格, 第几次尝试) 注入分配失败；
- `failNextReleaseOnce()`：下一次释放先删部分资源再失败，制造 `RELEASING` 残留。
