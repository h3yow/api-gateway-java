# API Gateway（本地 HTTP 网关层）

在本地运行的 HTTP 服务前置一层网关，用**本地状态**维护调用方配额与版本策略，
为所有入口（`/gateway/**`）提供一致、可解释的：并发限流、版本协商、下游异常统一处理。

## 1. 请求约定

| 请求头 | 含义 | 缺失/非法行为 |
| --- | --- | --- |
| `X-Caller-Id` | 调用方标识 | 缺失 → `401 GATEWAY_MISSING_CALLER`，与“未登记调用方”区分 |
| `X-Api-Version` | 声明的接口版本，接受 `v1.0`/`V1.0`/`1.0` | 缺失 → `400 GATEWAY_MISSING_VERSION`；无法解析 → `400 GATEWAY_UNKNOWN_VERSION`，**不回落默认版本** |

- 网关入口：`POST /gateway/**`，路径去掉 `/gateway` 前缀后转发下游，
  例如 `POST /gateway/mock/downstream/echo` → 下游 `/mock/downstream/echo`。
- 每个请求生成唯一 `X-Trace-Id` 响应头（重试也是新请求、新 ID、重新判定）。

## 2. 限流维度与窗口语义

- **维度**：`调用方 × 接口版本`。同一调用方不同版本配额互相独立
  （caller-a 的 v1.0 与 v1.1 各有独立计数器）。
- **算法**：固定窗口（fixed window）。窗口按 epoch 毫秒对齐：
  `windowStart = floor(now / windowMillis) × windowMillis`，
  窗口切换瞬间旧计数作废、新窗口从 0 开始（边界确定、无平滑泄漏）。
- **并发正确性**：每维度一个原子窗口状态，`tryAcquire` 以 CAS 自旋完成
  “检查窗口 → 必要时翻窗 → 扣减 1”。放行与扣减在同一次原子迁移内完成：
  - 不会重复放行（200 个并发请求抢 50 配额，恰好 50 个成功，放行序号严格 1..50）；
  - 不会误拒（窗口未满时每个请求都能抢到配额）；
  - 拒绝路径**不修改计数**，不产生扣减。
- **配额扣减时机**：仅在版本协商通过后、调用下游之前扣减一次。
  下游超时/429/500 **不退还**配额（调用机会已消费）；编排层对 `tryAcquire` 只调用一次，
  同一请求不可能重复计数。调用方重试是一次新的 HTTP 请求，按新请求重新判定。
- **默认策略（未登记调用方不明令无限放行）**，查找按以下顺序命中即止：
  1. `caller|version` 精确配额；
  2. `caller|*` 调用方版本无关配额；
  3. `__default__|version` 版本级默认配额；
  4. `__default__|*` 兜底默认配额（始终存在，缺失即启动失败）。

默认本地配额（`RateLimitConfig.defaultLocalConfig`，窗口均为 1000ms）：

| 调用方 | v1.0 | v1.1 |
| --- --- --- |
| caller-a | 5/s | 10/s |
| caller-b | 3/s（`caller-b|*`） | 3/s |
| 未登记调用方 | **1/s（默认策略）** | **1/s** |

被限流时返回 `429`、错误码 `GATEWAY_RATE_LIMITED`，
响应体含 `retryAfterMillis`（距当前窗口结束的毫秒数），响应头含 `Retry-After`（向上取整秒）。

## 3. 版本协商与兼容规则

- 系统受支持版本集合在启动时固定：默认 `{v1.0, v1.1}`（`VersionNegotiator`）。
- 每个调用方有一份**白名单**策略；调用方未登记时落到 `__default__` 默认策略（默认仅允许 v1.0）。
- 判定为纯函数，相同输入结论稳定（测试连续断言 100 次一致）：

| 情形 | 结论 |
| --- | --- |
| 未声明版本 | 拒绝 `GATEWAY_MISSING_VERSION`，不回落 |
| 版本字符串无法解析 | 拒绝 `GATEWAY_UNKNOWN_VERSION`，不回落 |
| 版本不在系统支持集合（如 v9.9、v2.0） | 拒绝 `GATEWAY_UNKNOWN_VERSION`，不回落 |
| 版本受支持但不在该调用方白名单（caller-b 请求 v1.1） | 拒绝 `GATEWAY_CALLER_VERSION_NOT_ALLOWED`，不回落 |
| 命中白名单 | 放行，`resolved == 声明版本`（不做小版本静默替换） |

## 4. 错误分类（统一结构、原因可区分）

所有错误均为同一 JSON 结构，并带 `X-Trace-Id`：

```json
{
  "traceId": "...",
  "errorCode": "GATEWAY_RATE_LIMITED",
  "reason": "RATE_LIMITED_QUOTA_EXHAUSTED",
  "caller": "caller-b",
  "version": "v1.0",
  "message": "判定依据……",
  "retryAfterMillis": 742
}
```

| errorCode | HTTP | 含义 | retryAfterMillis |
| --- | --- | --- | --- |
| GATEWAY_MISSING_CALLER | 401 | 未提供调用方标识 | 0 |
| GATEWAY_MISSING_VERSION | 400 | 未声明版本 | 0 |
| GATEWAY_UNKNOWN_VERSION | 400 | 版本无法解析/系统不支持 | 0 |
| GATEWAY_CALLER_VERSION_NOT_ALLOWED | 400 | 版本与调用方接口不兼容 | 0 |
| GATEWAY_RATE_LIMITED | 429 | 网关本地配额耗尽 | >0（窗口剩余毫秒） |
| GATEWAY_DOWNSTREAM_TIMEOUT | 504 | 下游读/连接超时或 IO 故障 | 0 |
| GATEWAY_DOWNSTREAM_RATE_LIMITED | 429 | 下游返回 429 | 0 |
| GATEWAY_DOWNSTREAM_ERROR | 502 | 下游其他非 2xx / 网关兜底错误 | 0 |

注意两种 429 的 `errorCode` 不同：`GATEWAY_RATE_LIMITED`（本地限流，带重试建议）
与 `GATEWAY_DOWNSTREAM_RATE_LIMITED`（下游限流）。

## 5. 处理链路

```
HTTP 请求
  └─ TraceIdFilter（生成 traceId，写响应头）
      └─ GatewayController（解析 X-Caller-Id / X-Api-Version，非法即拒）
          └─ GatewayOrchestrator
              1. VersionNegotiator 协商（失败：不扣配额）
              2. RateLimiter.tryAcquire（全请求仅此一次；失败：不扣配额）
              3. DownstreamClient 真实 HTTP 调用（透传 traceId/版本，超时 300ms）
              4. 下游异常 → DOWNSTREAM_TIMEOUT / _RATE_LIMITED / _ERROR
          └─ GatewayExceptionHandler → 统一 ErrorResponse + Retry-After
```

## 6. 本地验证

### 6.1 启动

```bash
mvn spring-boot:run
# 服务监听 8080；下游基址默认 http://localhost:8080（同进程 mock）
```

### 6.2 curl 手动验证

```bash
# 正常转发
curl -i -X POST http://localhost:8080/gateway/mock/downstream/echo \
  -H 'X-Caller-Id: caller-a' -H 'X-Api-Version: v1.0' -d '{}'

# 连续打 caller-a/v1.0 配额（5/s），第 6 次得到 429 + Retry-After + retryAfterMillis
for i in $(seq 1 6); do
  curl -s -o /dev/null -w '%{http_code}\n' -X POST \
    http://localhost:8080/gateway/mock/downstream/echo \
    -H 'X-Caller-Id: caller-a' -H 'X-Api-Version: v1.0' -d '{}'
done

# 版本不兼容（caller-b 不允许 v1.1）
curl -i -X POST http://localhost:8080/gateway/mock/downstream/echo \
  -H 'X-Caller-Id: caller-b' -H 'X-Api-Version: v1.1' -d '{}'

# 下游超时（mock sleep 2s，网关 300ms 超时）→ 504 GATEWAY_DOWNSTREAM_TIMEOUT
curl -i -X POST http://localhost:8080/gateway/mock/downstream/slow \
  -H 'X-Caller-Id: caller-a' -H 'X-Api-Version: v1.1' -d '{}'

# 下游 429 / 500
curl -i -X POST http://localhost:8080/gateway/mock/downstream/busy \
  -H 'X-Caller-Id: caller-a' -H 'X-Api-Version: v1.1' -d '{}'

# 未登记调用方：默认 1/s，第二次即被限流
curl -i -X POST http://localhost:8080/gateway/mock/downstream/echo \
  -H 'X-Caller-Id: nobody' -H 'X-Api-Version: v1.0' -d '{}'
```

### 6.3 自动化测试

```bash
mvn test
```

共 22 个用例，覆盖需求点：

| 测试类 | 覆盖内容 |
| --- | --- |
| `RateLimiterTest` | 20 线程 × 10 请求并发抢 50 配额（守恒：恰好 50 放行/150 拒绝，放行序号 1..50）、拒绝不扣减、固定窗口边界切换、跨调用方/版本维度独立、未登记调用方默认策略 |
| `VersionNegotiatorTest` | 白名单放行稳定、缺版本/不支持版本/调用方不兼容均明确拒绝、默认策略、版本字符串解析 |
| `DownstreamClientTest` | 真实本地 HTTP 服务器（MockWebServer）模拟 200/429/500/读超时，断言三类原因可区分、traceId 透传 |
| `GatewayIntegrationTest` | 端到端：并发限流守恒与错误结构、窗口翻页恢复、版本拒绝不扣配额、缺调用方、下游三类错误统一结构、重试为新请求（新 traceId） |

单测 stdout 打印调用方、版本、配额与判定依据，例如：

```
[RateLimitTest] caller=stranger version=v1.0 acquired=false quota=1/1 remaining=0
  window=[0,1000) retryAfterMillis=1000 reason=RATE_LIMITED_QUOTA_EXHAUSTED
  basis=fallback default quota for __default__|*; ...
```

服务端处理日志（INFO，`com.github.h3yow.apigatewayjava.gateway`）同样打印
`traceId/caller/version/quota/decision/reason/basis`。
