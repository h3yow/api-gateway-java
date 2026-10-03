# API Gateway（本地 HTTP 网关层）

在本地运行的 HTTP 服务前置一层网关，用**本地状态**维护调用方配额、版本策略与**接口组策略**，
为所有入口（`/gateway/**`）提供一致、可解释的：路由判定、并发限流、版本协商、
接口组上线状态管理、下游异常统一处理与**错误来源分类**。

## 1. 请求约定

| 请求头 | 含义 | 缺失/非法行为 |
| --- | --- | --- |
| `X-Caller-Id` | 调用方标识 | 缺失 → `401 GATEWAY_MISSING_CALLER`，与“未登记调用方”区分 |
| `X-Api-Version` | 声明的接口版本，接受 `v1.0`/`V1.0`/`1.0` | 缺失 → `400 GATEWAY_MISSING_VERSION`；无法解析 → `400 GATEWAY_UNKNOWN_VERSION`，**不回落默认版本** |

- 网关入口：`POST /gateway/**`，路径去掉 `/gateway` 前缀后转发下游，
  例如 `POST /gateway/mock/downstream/echo` → 下游 `/mock/downstream/echo`。
- 每个请求生成唯一 `X-Trace-Id` 响应头（重试也是新请求、新 ID、重新判定）。
  整个响应**只有这一个追踪标识**，且与错误体 `traceId` 字段严格一致。

## 2. 处理链路

```
HTTP 请求
  └─ TraceIdFilter（生成唯一 traceId，写响应头，仅一次）
      └─ GatewayController（解析 X-Caller-Id / X-Api-Version，非法即拒）
          └─ GatewayOrchestrator
              1. VersionNegotiator 调用方级版本协商（失败：不进入后续，不扣配额）
              2. RouteRegistry 路由解析（未注册→本地 404；方法不允许→本地 405；不转发下游）
              3. GroupAuthorizer 接口组准入（未开放/暂停/灰度/组版本，失败：不扣配额）
              4. RateLimiter 按 调用方×版本×接口组 扣减（全请求仅此一次；失败不扣）
              5. DownstreamClient 真实 HTTP 调用（透传 traceId/版本，超时 300ms）
                 下游异常 → DOWNSTREAM_TIMEOUT / _RATE_LIMITED / _ERROR（配额不退还）
          └─ GatewayExceptionHandler → 统一 ErrorResponse（含 errorSource）+ Retry-After
```

## 3. 接口组：路由、配额与回退

### 3.1 路由归属

每个下游路径在 `ApiGroupConfig` 中**精确注册**为一条路由，并归属于一个接口组：

```
Route(downstreamPath, groupId, allowedMethods)
```

- 路径未注册 → 本地 `404 GATEWAY_ROUTE_NOT_FOUND`（**不会**转发下游碰运气，因此绝不伪装成下游故障）；
- 路径存在但 HTTP 方法不在 `allowedMethods` → 本地 `405 GATEWAY_METHOD_NOT_ALLOWED`；
- 路由引用未声明的组 / 组开放了系统不支持的版本 / 缺少默认组 → **启动即失败（fail-fast）**。

### 3.2 计数维度与配额隔离

限流计数维度为 **`调用方 × 接口版本 × 接口组`**：同一调用方同一版本访问不同接口组时，
**各自独立计数、互不占用额度**。同一调用方在不同接口组、不同版本下的配额与判定结论彼此独立、稳定。

固定窗口算法、CAS 自旋、并发守恒、拒绝不扣减等语义与上一版完全一致（见 §4），
只是计数键从 `caller|version` 扩展为 `caller|version|groupId`。

### 3.3 组配额的回退顺序（未配置组绝不无限放行）

每个接口组可配置自己的 `QuotaPolicy(limit, windowMillis)`（独立限额与窗口）。
某条“调用方×版本×组”请求的最终配额按以下顺序命中即止：

1. 组内精确配额 `caller|version|groupId`（可选的组内调用方覆盖）；
2. 组内调用方默认 `caller|*|groupId`；
3. **组级配额**：接口组自身配置的限额与窗口；
4. **回退到既有“调用方 × 版本”配额策略**：`caller|version` → `caller|*`
   → `__default__|version` → `__default__|*`（兜底始终存在）。

> 因此“接口组没单独配配额”等价于**回退到有限的调用方×版本配额**，而不是放开。
> 超限时同样返回 `retryAfterMillis`（距该组当前窗口结束的毫秒数）与可解释的 `basis`，
> 说明命中的是组配额还是哪一级回退。

### 3.4 默认本地配置

| 接口组 | 状态 | 开放版本 | 组配额 | 演示路由（前缀 `/gateway`） |
| --- | --- | --- | --- | --- |
| `__default_group__` | OPEN | v1.0,v1.1 | 无（回退调用方×版本） | `/mock/downstream/{echo,slow,error,busy}` |
| `orders` | OPEN | **仅 v1.0** | **2/1s** | `/mock/downstream/orders/create` |
| `payments` | OPEN | v1.0,v1.1 | 4/1s | `/mock/downstream/payments/charge` |
| `reports` | **PAUSED** | v1.0,v1.1 | 无（回退） | `/mock/downstream/reports/daily` |
| `beta-ai` | **CANARY** | **仅 v1.1**，灰度白名单 `caller-a` | 无（回退） | `/mock/downstream/ai/chat` |
| `ledger-v2` | **NOT_OPENED** | 仅声明 v1.1 | 无（回退） | `/mock/downstream/ledger/v2` |

调用方×版本既有默认配额（窗口均为 1000ms）：caller-a v1.0=5/s、v1.1=10/s；
caller-b `*`=3/s；未登记调用方=1/s。

## 4. 固定窗口与并发正确性

- **窗口对齐**：`windowStart = floor(now / windowMillis) × windowMillis`，
  窗口切换瞬间旧计数作废、新窗口从 0 开始。组可有自己的 `windowMillis`，不同组窗口互不影响。
- **原子扣减**：每维度一个原子窗口状态，`tryAcquire` 以 CAS 自旋完成
  “检查窗口 → 必要时翻窗 → 扣减 1”，放行与扣减在同一次原子迁移内：
  - 不会重复放行（N 个并发请求抢 K 配额，恰好 K 个成功，放行序号严格 1..K）；
  - 不会误拒；拒绝路径不修改计数。
- **只扣一次**：仅在版本协商、路由、组准入全部通过后、调用下游之前扣减一次；
  编排层对 `tryAcquire` 只调用一次，无重试逻辑。下游超时/429/500 **不退还**配额。
- 限流返回 `429 GATEWAY_RATE_LIMITED`，含 `retryAfterMillis` 与 `Retry-After`（向上取整秒）。

## 5. 版本协商与接口组开放语义

版本判定分两层，**都显式判定、绝不静默替换声明版本、绝不自动套用默认接口策略**：

1. **调用方级（系统支持集合 + 调用方白名单，既有能力）**：未声明/无法解析/系统不支持/
   调用方白名单不包含 → 对应错误码拒绝，`resolved == 声明版本` 或拒绝。
2. **接口组级（本组 `openVersions` + 上线状态）**：

| 情形 | 结论 | HTTP |
| --- | --- | --- |
| 接口组 `NOT_OPENED`（尚未开放） | `GATEWAY_API_GROUP_NOT_OPENED` | 404 |
| 接口组 `PAUSED`（已上线后暂停） | `GATEWAY_API_GROUP_PAUSED`（建议稍后重试） | 503 |
| 接口组 `CANARY` 且调用方不在灰度白名单 | `GATEWAY_API_GROUP_CANARY_NOT_ALLOWED` | 403 |
| 声明版本不在本组 `openVersions` | `GATEWAY_API_GROUP_VERSION_NOT_ALLOWED`，不替换/不回落 | 400 |
| 状态与版本都通过 | 放行，生效版本即声明版本 | — |

- `PAUSED` 与 `NOT_OPENED` 是不同语义，错误码与 HTTP 状态都可区分。
- 接口组的开放版本**只看本组策略**：即便某调用方在调用方级能用某版本，
  只要该接口组未开放该版本，就在组级被拒（例如 caller-a 的 v1.1 访问仅开 v1.0 的 `orders`）。
- 未登记调用方仍走调用方级默认白名单（默认仅 v1.0），不会因为某个组开了 v1.1 就越权。
- 上述任一拒绝都发生在扣减配额之前，**不消耗任何接口组配额**。

## 6. 错误来源分类（LOCAL / DOWNSTREAM）

统一错误结构新增 `errorSource` 与 `group` 字段：

```json
{
  "traceId": "...",
  "errorSource": "LOCAL",
  "errorCode": "GATEWAY_RATE_LIMITED",
  "reason": "RATE_LIMITED_QUOTA_EXHAUSTED",
  "caller": "grp-iso",
  "version": "v1.0",
  "group": "orders",
  "message": "判定依据……",
  "retryAfterMillis": 742
}
```

| errorCode | HTTP | source | 含义 |
| --- | --- | --- | --- |
| GATEWAY_MISSING_CALLER | 401 | LOCAL | 未提供调用方标识 |
| GATEWAY_MISSING_VERSION | 400 | LOCAL | 未声明版本 |
| GATEWAY_UNKNOWN_VERSION | 400 | LOCAL | 版本无法解析/系统不支持 |
| GATEWAY_CALLER_VERSION_NOT_ALLOWED | 400 | LOCAL | 版本与调用方不兼容 |
| GATEWAY_ROUTE_NOT_FOUND | 404 | LOCAL | 网关本地无此下游路由 |
| GATEWAY_METHOD_NOT_ALLOWED | 405 | LOCAL | 路由存在但请求方法不支持 |
| GATEWAY_API_GROUP_NOT_OPENED | 404 | LOCAL | 接口组尚未开放 |
| GATEWAY_API_GROUP_PAUSED | 503 | LOCAL | 接口组暂停 |
| GATEWAY_API_GROUP_CANARY_NOT_ALLOWED | 403 | LOCAL | 不在灰度白名单 |
| GATEWAY_API_GROUP_VERSION_NOT_ALLOWED | 400 | LOCAL | 版本不在该接口组开放范围 |
| GATEWAY_RATE_LIMITED | 429 | LOCAL | 网关本地配额耗尽（带重试建议、Retry-After） |
| GATEWAY_INTERNAL_ERROR | 500 | LOCAL | 网关自身未预期缺陷（**不**伪装成下游故障） |
| GATEWAY_DOWNSTREAM_TIMEOUT | 504 | DOWNSTREAM | 下游读/连接超时或 IO 故障 |
| GATEWAY_DOWNSTREAM_RATE_LIMITED | 429 | DOWNSTREAM | 下游返回 429（**无** Retry-After，不替下游给窗口） |
| GATEWAY_DOWNSTREAM_ERROR | 502 | DOWNSTREAM | 下游其他非 2xx |

关键区分：两种 429 的 `errorCode`/`errorSource` 不同——本地限流是
`LOCAL/GATEWAY_RATE_LIMITED`（带 `retryAfterMillis` 与 `Retry-After`），
下游限流是 `DOWNSTREAM/GATEWAY_DOWNSTREAM_RATE_LIMITED`。
路由不存在、方法不支持、网关内部错误一律 `LOCAL`。

## 7. 本地验证

### 7.1 启动

```bash
mvn spring-boot:run
# 服务监听 8080；下游基址默认 http://localhost:${server.port}（同进程 mock）
```

### 7.2 curl 手动验证

```bash
# 正常转发（default 组，回退调用方×版本配额）
curl -i -X POST http://localhost:8080/gateway/mock/downstream/echo \
  -H 'X-Caller-Id: caller-a' -H 'X-Api-Version: v1.0' -d '{}'

# orders 组独立配额 2/s：第 3 次本地 429（不影响你在其他组的额度）
for i in 1 2 3; do
  curl -s -o /dev/null -w '%{http_code}\n' -X POST \
    http://localhost:8080/gateway/mock/downstream/orders/create \
    -H 'X-Caller-Id: demo' -H 'X-Api-Version: v1.0' -d '{}'
done

# orders 仅开放 v1.0：caller-a 的 v1.1 在组级被拒（不静默替换）
curl -i -X POST http://localhost:8080/gateway/mock/downstream/orders/create \
  -H 'X-Caller-Id: caller-a' -H 'X-Api-Version: v1.1' -d '{}'

# 暂停 / 未开放 / 灰度
curl -i -X POST http://localhost:8080/gateway/mock/downstream/reports/daily \
  -H 'X-Caller-Id: caller-a' -H 'X-Api-Version: v1.0' -d '{}'      # 503 PAUSED LOCAL
curl -i -X POST http://localhost:8080/gateway/mock/downstream/ledger/v2 \
  -H 'X-Caller-Id: caller-a' -H 'X-Api-Version: v1.1' -d '{}'      # 404 NOT_OPENED LOCAL
curl -i -X POST http://localhost:8080/gateway/mock/downstream/ai/chat \
  -H 'X-Caller-Id: caller-a' -H 'X-Api-Version: v1.1' -d '{}'      # 200（灰度白名单内）

# 本地路由 404 / 方法 405（都是 LOCAL，不转发下游）
curl -i -X POST http://localhost:8080/gateway/mock/does/not/exist \
  -H 'X-Caller-Id: caller-a' -H 'X-Api-Version: v1.0' -d '{}'
curl -i -X GET http://localhost:8080/gateway/mock/downstream/orders/create \
  -H 'X-Caller-Id: caller-a' -H 'X-Api-Version: v1.0'

# 下游 429（errorSource=DOWNSTREAM，无 Retry-After）
curl -i -X POST http://localhost:8080/gateway/mock/downstream/busy \
  -H 'X-Caller-Id: caller-a' -H 'X-Api-Version: v1.1' -d '{}'
```

### 7.3 自动化测试（任意时间稳定复现）

```bash
mvn test
```

共 **40** 个用例。所有涉及限流窗口与并发的测试都**不依赖真实时间**：

- 测试共享一个可控时钟 `MutableClock`（`src/test/java/.../gateway/MutableClock.java`），
  时间只能由测试代码显式推进（`setMillis`/`advanceMillis`），绝不随真实时间流动；
- 单元/编排测试直接把它传给 `RateLimiter`；端到端测试通过
  `@TestConfiguration` + `@Primary` 把它替换进 Spring 容器里的 `Clock` Bean，
  因此**走 HTTP 全链路的集成测试同样由逻辑时钟驱动**；
- 集成测试每条用例开始前把逻辑时间**向前推进 1 小时**（窗口均为 1s，旧窗口必然过期），
  用例执行顺序不影响结论；推进量是 1000 的整数倍，窗口起点始终对齐；
- 窗口边界由测试**主动推进到临界点**（`windowEnd-1ms` 仍拒、恰好 `windowEnd` 放行），
  并发压测期间时钟冻结，整批请求必然落在同一窗口内——
  **不存在真实 `Thread.sleep` 等待窗口翻页，也不靠延长等待掩盖竞态**。

因此以下结论在任何时刻、任何机器负载下重复运行都一致：

| 场景 | 保证 | 验证位置 |
| --- | --- | --- |
| 并发抢配额 | 总放行数**严格等于**该维度配额，放行序号严格 1..K 无重复 | `RateLimiterTest`（20 线程抢 50）、`GroupRateLimiterTest`（16 线程抢 2）、`GatewayIntegrationTest`（12 线程 HTTP 抢 3） |
| 拒绝不扣减 | 被拒请求不改动已有计数：打满后再请求仍被拒，且同窗 `retryAfterMillis` 完全一致 | 上述三个测试类均有断言 |
| 组间额度隔离 | 同调用方同版本在 orders/payments/default 组各自独立计数、互不占用 | `GroupRateLimiterTest`、`GatewayIntegrationTest` |
| 窗口临界点 | 窗口内打满后再请求必拒；`windowEnd-1ms` 仍拒（`retryAfterMillis=1`）；恰好 `windowEnd` 那一次放行（左闭右开） | `RateLimiterTest`、`GroupRateLimiterTest`、`GatewayIntegrationTest#windowBoundary_*` |
| 重试语义 | 重试是新请求（新 traceId、重新判定），同窗必拒、翻窗后放行 | `GatewayIntegrationTest#retriesAreNewRequests_*` |

| 测试类 | 覆盖内容 |
| --- | --- |
| `GroupRateLimiterTest` | 同调用方同版本跨接口组配额隔离、组配额优先与回退（未配置组不放任）、组窗口切换精确、16 线程抢 2 配额守恒（放行序号 1..2） |
| `GroupPolicyTest` | PAUSED/NOT_OPENED/CANARY/组版本拒绝原因可区分、组间版本规则独立、不静默替换、404/405 均 LOCAL、非法配置 fail-fast |
| `GatewayOrchestratorTest` | 假下游 + 可控时钟：本地各类拒绝不扣减/不调下游、放行只扣一次、下游失败不退还且 source=DOWNSTREAM、本地限流先于下游 |
| `GatewayIntegrationTest` | 端到端（逻辑时钟驱动）：并发限流守恒且拒绝不扣减、窗口边界两个临界点、重试新请求、组配额隔离、暂停/未开放/组版本/灰度拒绝、404/405 本地分类、本地与下游 429 区分、唯一 traceId 头/体一致 |
| `RateLimiterTest` / `VersionNegotiatorTest` / `DownstreamClientTest` | 既有能力回归（调用方×版本限流守恒、调用方版本协商、下游三类错误） |

单测 stdout 打印调用方、版本、接口组、配额与判定依据，例如：

```
[IntegrationTest] 并发限流 caller=caller-b version=v1.0 quota=3/s ok=3 limited=21 total=24
[IntegrationTest] windowEnd-1ms 仍 429 retryAfterMillis=1
[IntegrationTest] 恰好落在窗口边界 windowEnd 恢复 status=200
[GroupRateLimitTest] 组间隔离 orders=2 payments=4 default=5 互不占用
[OrchestratorTest] 本地拒绝均未扣减配额、未调用下游
```

### 7.4 稳定性复现步骤

```bash
# 连续多轮运行，结论必须每轮一致（40 个用例全绿）；-B 关闭颜色便于 grep
for i in 1 2 3 4 5; do mvn -B test | grep -E "Tests run: [0-9]+, Failures" | tail -1; done
```

预期：每轮输出均为 `Tests run: 40, Failures: 0, Errors: 0, Skipped: 0`。
整个套件不涉及外部服务、真实凭据或长时间运行才会触发的条件
（唯一起真实 HTTP 的是指向同进程 mock 下游与本地 `MockWebServer` 的调用；
下游超时用例用 1s 延迟对 300ms 超时，量级差距保证结论与执行时刻无关）。

## 8. 兼容范围

- 计数维度由 `调用方|版本` 扩展为 `调用方|版本|接口组`；`RateLimiter` 保留旧构造与
  `tryAcquire(caller, version)`，旧的调用方×版本单测与行为不回归。
- 既有错误码与 HTTP 状态保持不变；`ErrorResponse` 新增 `errorSource`、`group` 两个字段（增量、向后兼容）。
- 新增错误码：`GATEWAY_ROUTE_NOT_FOUND`、`GATEWAY_METHOD_NOT_ALLOWED`、
  `GATEWAY_API_GROUP_PAUSED`、`GATEWAY_API_GROUP_NOT_OPENED`、
  `GATEWAY_API_GROUP_CANARY_NOT_ALLOWED`、`GATEWAY_API_GROUP_VERSION_NOT_ALLOWED`、
  `GATEWAY_INTERNAL_ERROR`。
- 未预期异常由旧的 `502 GATEWAY_DOWNSTREAM_ERROR` 修正为 `500 GATEWAY_INTERNAL_ERROR`（LOCAL），
  避免把网关自身缺陷误报为下游故障。
