# API Gateway（本地 HTTP 网关层）

在本地运行的 HTTP 服务前置一层网关，用**本地状态**维护调用方配额、版本策略与**接口组策略**，
为所有入口（`/gateway/**`）提供一致、可解释的：接口组路由、按组隔离的并发限流、
版本协商（调用方维度 + 接口组维度）、接口上线状态管控、错误来源分类与统一返回。

## 1. 请求约定

| 请求头 | 含义 | 缺失/非法行为 |
| --- | --- | --- |
| `X-Caller-Id` | 调用方标识 | 缺失 → `401 GATEWAY_MISSING_CALLER`，与“未登记调用方”区分 |
| `X-Api-Version` | 声明的接口版本，接受 `v1.0`/`V1.0`/`1.0` | 缺失 → `400 GATEWAY_MISSING_VERSION`；无法解析 → `400 GATEWAY_UNKNOWN_VERSION`，**不回落默认版本** |

- 网关入口：`/gateway/**`，路径去掉 `/gateway` 前缀后用于**接口组路由**，命中后再转发下游，
  例如 `POST /gateway/api/orders/1` → 路由到接口组 `orders`，转发下游 `/api/orders/1`。
- 每个请求生成唯一 `X-Trace-Id` 响应头（重试也是新请求、新 ID、重新判定）。
  响应头与错误体 `traceId` **同源一致**，且每个响应**只有一个** `X-Trace-Id`。

## 2. 接口组与路由

每个下游路径按**最长前缀**归入一个接口组（前缀必须落在路径分隔符边界，
`/api/ordersX` 不会误命中 `/api/orders`）。未命中任何已配置前缀的路径不会被转发，
直接返回**本地** `404 GATEWAY_ROUTE_NOT_FOUND`（绝不伪装成下游故障）。

默认本地路由表（`RouteTable.defaultLocalTable`）：

| 前缀 | 接口组 | 状态 | 开放版本 | 灰度白名单 | 自有配额 |
| --- | --- | --- | --- | --- | --- |
| `/mock/downstream` | mock-downstream | ACTIVE | v1.0、v1.1 | — | 无（回退） |
| `/api/orders` | orders | ACTIVE | v1.0、v1.1 | — | **2/1s** |
| `/api/reports` | reports | ACTIVE | v1.0、v1.1 | — | 无（回退） |
| `/api/beta` | beta | **GRAY** | **仅 v1.1** | **caller-a** | 2/1s |
| `/api/legacy` | legacy | **PAUSED** | v1.0 | — | 无（回退） |
| `/api/future` | future | **NOT_OPEN** | v1.0、v1.1 | — | 无（回退） |

接口组配置在启动期强校验，错误配置直接 fail-fast：开放版本列表必须显式非空
且为系统支持集合的子集、GRAY 必须显式给出灰度调用方集合、配额限额/窗口必须为正。

## 3. 接口组配额与回退规则

- **计数维度**：`调用方 × 接口组 × 版本`。同一调用方、同一版本访问**不同接口组**时，
  配额与计数器**彼此独立、互不占用**（orders 组打满不影响 reports 组）。
- **组自有配额**：接口组配置了 `limit + windowMillis` 时使用组配额。
- **回退（不是无限放行）**：接口组未配置自有配额时，限额值**回退到现有
  “调用方×版本”策略**的四级命中（`caller|version` → `caller|*`
  → `__default__|version` → `__default__|*`，兜底始终存在）。
  回退只借用限额数值，**计数器仍然按接口组隔离**；未登记调用方 + 未配置组配额
  仍落到保守的兜底默认配额（1/s），因此“没有单独配置的接口组”绝不等于无限放行。
- **算法**：固定窗口，`windowStart = floor(now / windowMillis) × windowMillis`，
  窗口切换瞬间旧计数作废、新窗口从 0 开始。
- **并发正确性**：每维度一个原子窗口状态，CAS 自旋完成“检查窗口 → 翻窗 → 扣减 1”，
  放行与扣减在同一次原子迁移内：不会重复放行、不会误拒、拒绝路径不扣减。
- **超限时**返回本地 `429 GATEWAY_RATE_LIMITED`，错误体含 `retryAfterMillis`
  （距窗口结束毫秒）与可解释 `message`（含 dimension/window/used/basis），
  响应头含 `Retry-After`（向上取整秒）。
- **扣减时机**：所有本地判定（路由/方法/状态/两层版本）通过后、调用下游之前
  **仅扣减一次**；下游超时/429/500 不退还配额，编排层无重试，同一请求不可能重复计数。

## 4. 版本协商：调用方维度 + 接口组维度

判定按固定顺序进行，两层都通过才放行；任何一层拒绝都不扣配额：

1. **系统支持集合**（默认 `{v1.0, v1.1}`）与**调用方白名单**（`VersionNegotiator`）：
   - 未声明 → `GATEWAY_MISSING_VERSION`；
   - 无法解析或系统不支持（v9.9、v2.0）→ `GATEWAY_UNKNOWN_VERSION`；
   - 调用方白名单不含（caller-b 请求 v1.1、未登记调用方请求 v1.1）
     → `GATEWAY_CALLER_VERSION_NOT_ALLOWED`；
   - 命中则 `resolved == 声明版本`（不做小版本静默替换）。
2. **接口组开放范围**（`InterfaceGroupGate.checkVersion`）：声明版本不在该组
   `openVersions` 内 → `GATEWAY_INTERFACE_VERSION_NOT_OPEN`。这里**只按用户声明的版本**
   判定：不会悄悄替换成系统默认版本，也不会自动套用默认接口策略。
3. **接口组上线状态**（在版本协商前判定）：
   - PAUSED → `503 GATEWAY_INTERFACE_PAUSED`（所有人拒绝）；
   - NOT_OPEN → `404 GATEWAY_INTERFACE_NOT_OPEN`（尚未开放，所有人拒绝）；
   - GRAY 且调用方不在灰度白名单 → `403 GATEWAY_INTERFACE_GRAY_NOT_ALLOWED`；
   - 方法不在允许集合（默认仅 POST）→ `405 GATEWAY_METHOD_NOT_ALLOWED`。

同一调用方在**不同接口组、不同版本**下的版本结论与配额各自独立、稳定（纯函数判定）。
例如 v1.0 对 `beta` 组不开放（400），完全不影响它访问 `orders`/`reports`。

## 5. 错误来源分类（LOCAL vs DOWNSTREAM）

统一错误结构新增 `source` 与 `group` 字段：

```json
{
  "traceId": "…",
  "errorCode": "GATEWAY_RATE_LIMITED",
  "reason": "RATE_LIMITED_QUOTA_EXHAUSTED",
  "source": "LOCAL",
  "caller": "caller-a",
  "version": "v1.0",
  "group": "orders",
  "message": "判定依据……dimension=caller-a|orders|v1.0; window …",
  "retryAfterMillis": 742
}
```

- **LOCAL（请求尚未进入下游）**：缺调用方、缺/错版本、调用方/接口组版本不兼容、
  接口组暂停/未开放/灰度拒绝、方法不支持、路由不存在、本地配额耗尽、网关自身内部错误。
- **DOWNSTREAM（已放行并实际调用下游）**：下游超时、下游限流、下游业务异常。

| errorCode | HTTP | source | 含义 | Retry-After |
| --- | --- | --- | --- | --- |
| GATEWAY_MISSING_CALLER | 401 | LOCAL | 未提供调用方标识 | — |
| GATEWAY_MISSING_VERSION | 400 | LOCAL | 未声明版本 | — |
| GATEWAY_UNKNOWN_VERSION | 400 | LOCAL | 版本无法解析/系统不支持 | — |
| GATEWAY_CALLER_VERSION_NOT_ALLOWED | 400 | LOCAL | 版本与调用方接口不兼容 | — |
| GATEWAY_INTERFACE_VERSION_NOT_OPEN | 400 | LOCAL | 版本未对该接口组开放 | — |
| GATEWAY_INTERFACE_PAUSED | 503 | LOCAL | 接口组暂停 | — |
| GATEWAY_INTERFACE_NOT_OPEN | 404 | LOCAL | 接口组尚未开放 | — |
| GATEWAY_INTERFACE_GRAY_NOT_ALLOWED | 403 | LOCAL | 不在灰度白名单 | — |
| GATEWAY_ROUTE_NOT_FOUND | 404 | LOCAL | 网关上无此路由 | — |
| GATEWAY_METHOD_NOT_ALLOWED | 405 | LOCAL | 请求方法不被接口组支持 | — |
| GATEWAY_RATE_LIMITED | 429 | LOCAL | 网关本地配额耗尽 | 有 |
| GATEWAY_INTERNAL_ERROR | 500 | LOCAL | 网关自身未预期异常（不再伪装成下游故障） | — |
| GATEWAY_DOWNSTREAM_TIMEOUT | 504 | DOWNSTREAM | 下游读/连接超时或 IO 故障 | — |
| GATEWAY_DOWNSTREAM_RATE_LIMITED | 429 | DOWNSTREAM | 下游返回 429 | **无** |
| GATEWAY_DOWNSTREAM_ERROR | 502 | DOWNSTREAM | 下游其他非 2xx | — |

两种 429 可由 `errorCode`/`source`/`Retry-After` 三重区分。

## 6. 处理链路

```
HTTP 请求
  └─ TraceIdFilter（生成唯一 traceId，写唯一响应头）
      └─ GatewayController（解析调用方/版本头，非法即本地拒）
          └─ GatewayOrchestrator
              1. RouteTable 路由解析（未命中 → 本地 404）
              2. InterfaceGroupGate.checkAccess（405 / PAUSED 503 / NOT_OPEN 404 / GRAY 403）
              3. VersionNegotiator 调用方版本协商（失败：不扣配额）
              4. InterfaceGroupGate.checkVersion（接口组版本未开放：不扣配额）
              5. QuotaResolver 解析配额（组自有 → 回退调用方×版本）
              6. RateLimiter.tryAcquire（全请求仅此一次；失败：不扣配额）
              7. DownstreamClient 真实 HTTP 调用（超时/429/5xx → DOWNSTREAM，不退还）
          └─ GatewayExceptionHandler → 统一 ErrorResponse（source/group/traceId 一致）
```

## 7. 兼容范围

- 既有的调用方×版本限流、四级配额回退、调用方版本白名单、下游三类错误映射**保持不变**；
  旧的两维 `RateLimiter.tryAcquire(caller, version)` 入口保留（归入内部默认组）。
- 既有 `/mock/downstream/**` 路径归入全版本开放、回退配额的 `mock-downstream` 组，
  历史 curl 与既有 22 个用例行为不变。
- 错误体在原有字段基础上**新增** `source`、`group` 两个字段，旧字段名与含义不变。
- 新增下游业务 mock：`/api/orders/{id}`、`/api/reports/daily`、`/api/beta/query`、
  `/api/legacy/query`、`/api/future/query`（后两者会被网关本地拦下，不会到达下游）。

## 8. 本地验证

### 8.1 启动

```bash
mvn spring-boot:run
# 服务监听 8080；下游基址默认 http://localhost:8080（同进程 mock）
```

### 8.2 curl 手动验证

```bash
# 接口组配额隔离：orders 组 2/s，第 3 次本地 429（带 Retry-After）
for p in 1 2 3; do
  curl -s -o /dev/null -w '%{http_code}\n' -X POST \
    http://localhost:8080/gateway/api/orders/$p \
    -H 'X-Caller-Id: caller-a' -H 'X-Api-Version: v1.0' -d '{}'
done

# orders 被打满不影响 reports（回退 caller-a|v1.0=5/s）
curl -i -X POST http://localhost:8080/gateway/api/reports/daily \
  -H 'X-Caller-Id: caller-a' -H 'X-Api-Version: v1.0' -d '{}'

# 暂停 / 未开放（本地 503 / 404，source=LOCAL）
curl -i -X POST http://localhost:8080/gateway/api/legacy/query \
  -H 'X-Caller-Id: caller-a' -H 'X-Api-Version: v1.0' -d '{}'
curl -i -X POST http://localhost:8080/gateway/api/future/query \
  -H 'X-Caller-Id: caller-a' -H 'X-Api-Version: v1.1' -d '{}'

# 灰度：caller-a+v1.1 放行；caller-b 403；caller-a+v1.0 400（版本未对该组开放）
curl -i -X POST http://localhost:8080/gateway/api/beta/query \
  -H 'X-Caller-Id: caller-a' -H 'X-Api-Version: v1.1' -d '{}'
curl -i -X POST http://localhost:8080/gateway/api/beta/query \
  -H 'X-Caller-Id: caller-b' -H 'X-Api-Version: v1.1' -d '{}'

# 路由不存在 / 方法不支持（本地 404 / 405，不会伪装成下游故障）
curl -i -X POST http://localhost:8080/gateway/no/such/route \
  -H 'X-Caller-Id: caller-a' -H 'X-Api-Version: v1.0' -d '{}'
curl -i -X GET http://localhost:8080/gateway/api/orders/1 \
  -H 'X-Caller-Id: caller-a' -H 'X-Api-Version: v1.0'

# 下游 429（source=DOWNSTREAM，无 Retry-After，区别于本地 429）
curl -i -X POST http://localhost:8080/gateway/mock/downstream/busy \
  -H 'X-Caller-Id: caller-a' -H 'X-Api-Version: v1.1' -d '{}'
```

### 8.3 自动化测试（时间确定性，可稳定复现）

```bash
mvn test
```

共 **59** 个用例。**所有窗口/时间边界都由可控时钟（`MutableClock`）手动驱动，
不依赖真实等待、不依赖墙钟、不依赖用例执行顺序**；仅“下游读超时”用例保留真实
HTTP IO（MockWebServer 延迟 1s、客户端 300ms 超时），与限流器时钟无关。

| 测试类 | 覆盖内容 |
| --- | --- |
| `RateLimiterTest` | 既有：两维并发守恒（50/200）、拒绝不扣减、固定窗口、维度独立、默认策略 |
| `RateLimiterGroupTest` | **新增：组间配额隔离、回退限额仍按组隔离、组×版本独立、组维度并发守恒（10/64）、可控时钟翻窗、dimension 依据** |
| `VersionNegotiatorTest` | 既有：调用方白名单、无静默回落、默认策略、稳定性、版本字符串解析 |
| `RouteTableTest` | **新增：最长前缀匹配、路径边界、未命中、开放版本/灰度/配额/前缀的 fail-fast 校验** |
| `InterfaceGroupGateTest` | **新增：ACTIVE/GRAY/PAUSED/NOT_OPEN、灰度白名单、405、组版本不开放、拒绝原因可区分、判定稳定** |
| `QuotaResolverTest` | **新增：组配额优先、回退调用方×版本、`caller|*` 回退、未登记调用方兜底非无限、未命中组内部标识** |
| `GatewayOrchestratorTest` | **新增（桩下游+固定时钟）：404/405/暂停/未开放/灰度/组版本不跨组串扰、组配额隔离、下游错误分类、配额只扣一次不退还、LOCAL/DOWNSTREAM 枚举归属** |
| `DownstreamClientTest` | 既有：真实 HTTP 模拟 200/429/500/读超时，traceId 透传 |
| `GatewayIntegrationTest` | 既有端到端（已改用可控时钟）：并发守恒、翻窗恢复、版本拒绝不扣配额、缺调用方、下游三类错误、重试新请求、单 traceId 校验 |
| `InterfaceGroupIntegrationTest` | **新增端到端：组配额隔离与翻窗恢复、暂停/未开放、灰度白名单+版本、组规则互不影响、404/405 本地错误、两种 429 区分** |

测试 stdout 会打印调用方、接口组、版本、配额 dimension 与判定依据，例如：

```
[QuotaTest] 组回退: group=reports has no dedicated quota, fallback to caller×version policy
  (exact quota for caller-a|v1.0); counter still isolated by group
```
