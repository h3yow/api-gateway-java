# 本地 API 网关（Java / Spring Boot）

在本地 HTTP 服务之上实现的一层网关能力：用**本地内存状态**维护「调用方 × 接口版本」
维度的固定窗口配额，并在所有入口上执行同一条「版本协商 → 配额判定 → 下游调用 →
统一错误映射」链路，使并发限流、版本协商与异常处理得到**一致且可解释**的结论。

## 1. 请求约定

网关入口（多个入口共用同一判定链路）：

| 入口 | 说明 |
| --- | --- |
| `GET /gateway/items` | 主入口 |
| `GET /gateway/echo` | 等价入口，用于验证跨入口结论一致 |

请求头：

| 请求头 | 必填 | 含义 |
| --- | --- | --- |
| `X-Caller-Id` | 否 | 调用方标识；缺失或未登记 → 落入 `default` 默认策略 |
| `X-Api-Version` | 否 | 版本声明（`v1` / `v2`，大小写与首尾空白会被归一化）；缺失/未知/不兼容一律 **400 拒绝** |
| `X-Trace-Id` | 否 | 追踪标识；缺失时网关生成 `gw-<uuid>`，并在响应头/错误体中回传 |

查询参数 `scenario`（仅本地模拟下游使用）：`ok`（默认）/ `timeout` / `error` / `throttle`。

## 2. 限流维度与窗口语义

- **配额维度 = 调用方 + 接口版本**（`QuotaKey(caller, version)`）。同一调用方在
  `v1` 与 `v2` 上的计数彼此独立；不同调用方之间也独立。
- **固定时间窗口（Fixed Window）**：每个 key 维护一个不可变的窗口状态
  `(windowStart, count)`，窗口起点对齐到纪元整点：
  `windowStart = now - floorMod(now, windowMillis)`，窗口区间为
  `[windowStart, windowStart + windowMillis)`。窗口滚动后计数**原子归零**，
  旧窗口迟到的请求不会污染新窗口。
- **并发正确性**：放行与计数自增通过对 `AtomicReference<Window>` 的一次
  CAS「比较并自增」同时完成，CAS 失败者自旋重试整个判定。因此在高并发下
  放行数**恰好等于配额**——既不重复放行，也不会误拒（测试断言请求总数守恒）。
- **重试建议时间**：`retryAfterMillis = windowStart + windowMillis - now`，
  即当前固定窗口剩余毫秒，确定且对所有调用方一致；响应同时给出向上取整的
  秒级 `Retry-After` 头与精确的 `X-Retry-After-Millis` 头。
- **默认策略**：调用方未登记时使用配置表中的 `default` 条目（保守配额，非无限放行）；
  若连默认条目都缺失，代码再退化为「配额 0、无授权版本」的硬拒绝策略。
- **配额代表“已被网关接纳的请求”**：通过配额判定后即使下游失败也**不回补**配额；
  客户端重试被视为一次新请求，携带新 traceId 重新进入并重新判定。
- **同一请求全链路只扣减一次**：`GatewayService.handle(...)` 是唯一扣减点，
  下游调用不再二次计数；版本协商失败的请求在扣减前即被拒绝。

当前内置策略（`src/main/resources/application.properties`）：

| 调用方 | 配额 | 窗口 | 授权版本 |
| --- | --- | --- | --- |
| `default`（未登记回落） | 5 | 60000ms | v1 |
| `premium-a` | 3 | 1000ms | v1, v2 |
| `legacy-b` | 2 | 1000ms | v1 |
| `v2-only-c` | 4 | 1000ms | v2 |

## 3. 版本协商与兼容规则

版本协商是**纯函数**，对相同输入组合结论稳定，且**任何失败都不静默回落到默认版本**：

1. 声明缺失或空白 → `VERSION_MISSING`（400）；
2. 声明无法解析为已知版本枚举（如 `v3`、`9.9`）→ `VERSION_UNKNOWN`（400）；
3. 版本可识别但不在该调用方授权版本集合内（如默认策略调用方请求 `v2`）
   → `VERSION_NOT_SUPPORTED`（400）；
4. 通过后得到确定的 `ApiVersion`，再以「调用方 × 该版本」为 key 进入配额判定。

## 4. 错误分类与统一错误结构

所有错误（含本地限流、版本拒绝、下游超时/异常/限流）输出**同一 JSON 结构**，
`reason` 字段保留细分原因，使「本地配额耗尽」与「下游 429」可明确区分：

```json
{
  "errorCode": "LOCAL_RATE_LIMITED",
  "message": "local quota exhausted in fixed window",
  "caller": "premium-a",
  "version": "V1",
  "reason": "LOCAL_QUOTA_EXCEEDED",
  "retryAfterMillis": 877,
  "traceId": "gw-xxxx"
}
```

| errorCode | HTTP | reason | retryAfterMillis |
| --- | --- | --- | --- |
| `VERSION_MISSING` | 400 | `VERSION_MISSING` | – |
| `VERSION_UNKNOWN` | 400 | `VERSION_UNKNOWN` | – |
| `VERSION_NOT_SUPPORTED` | 400 | `VERSION_NOT_SUPPORTED` | – |
| `LOCAL_RATE_LIMITED` | 429 | `LOCAL_QUOTA_EXCEEDED` | 当前窗口剩余毫秒 |
| `DOWNSTREAM_RATE_LIMITED` | 429 | `THROTTLED`（下游 429） | 解析下游 `Retry-After`，缺省 2000ms |
| `DOWNSTREAM_TIMEOUT` | 504 | `TIMEOUT`（读/连接超时） | – |
| `DOWNSTREAM_UNAVAILABLE` | 504 | `IO_ERROR`（其他网络故障） | – |
| `DOWNSTREAM_ERROR` | 502 | `SERVER_ERROR`（下游 5xx） | – |
| `INTERNAL_ERROR` | 500 | `INTERNAL`（兜底） | – |

`traceId` 同时出现在响应头 `X-Trace-Id`、错误体与转发给下游的 `X-Trace-Id` 头中，
可在全链路日志中追踪同一次请求。

## 5. 本地验证

### 5.1 自动化测试

```bash
mvn test
```

共 18 个用例（测试日志会打印**调用方、版本、配额与判定依据**）：

- `RateLimiterTest`（3）：20 线程 × 10 请求争抢 50 配额，断言放行恰为 50、
  拒绝恰为 150；窗口边界（切换前 1ms 拒绝、切换瞬间配额全额恢复）；
  调用方/版本维度独立。
- `VersionNegotiatorTest`（4）：缺失 / 未知 / 不兼容拒绝、归一化容错、判定稳定性。
- `GatewayServiceTest`（5）：默认策略回落不无限放行、每请求恰好扣减一次
  （下游失败也计数但不二次计数）、下游四类失败映射、本地限流与下游 429 可区分、
  重试按新请求重新判定、版本拒绝零下游调用。
- `GatewayIntegrationTest`（5）：MockMvc 全栈验证 HTTP 状态、错误 JSON、
  `Retry-After`/`X-Trace-Id` 头、跨入口一致性。

### 5.2 手工端到端（内置模拟下游）

```bash
mvn spring-boot:run
# 若 8080 被占用：
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8081 --gateway.downstream.base-url=http://localhost:8081"
```

```bash
# 正常
curl -i -H 'X-Caller-Id: premium-a' -H 'X-Api-Version: v1' \
  -H 'X-Trace-Id: demo-1' http://localhost:8080/gateway/items

# 版本未知 / 不兼容（400，不回落）
curl -i -H 'X-Caller-Id: premium-a' -H 'X-Api-Version: v9' http://localhost:8080/gateway/items
curl -i -H 'X-Caller-Id: nobody'    -H 'X-Api-Version: v2' http://localhost:8080/gateway/items

# 下游 429（429 + Retry-After，与本地限流错误码不同）
curl -i -H 'X-Caller-Id: legacy-b' -H 'X-Api-Version: v1' \
  'http://localhost:8080/gateway/items?scenario=throttle'

# 下游 500 -> 502；下游超时 -> 约 1.5s 后 504
curl -i -H 'X-Caller-Id: legacy-b' -H 'X-Api-Version: v1' \
  'http://localhost:8080/gateway/items?scenario=error'
curl -i -H 'X-Caller-Id: legacy-b' -H 'X-Api-Version: v1' \
  'http://localhost:8080/gateway/items?scenario=timeout'

# 真实并发：premium-a 配额 3/1000ms，期望恰好 3 个 200、其余 429
seq 1 20 | xargs -P 20 -I{} curl -s -o /dev/null -w '%{http_code}\n' \
  -H 'X-Caller-Id: premium-a' -H 'X-Api-Version: v1' \
  'http://localhost:8080/gateway/items' | sort | uniq -c
```

## 6. 代码结构

```
gateway/
├─ config/      ApiVersion、CallerPolicy、GatewayProperties（策略解析/默认回落）、GatewayConfig
├─ version/     VersionNegotiator（版本声明解析与兼容判定）
├─ ratelimit/   RateLimiter（固定窗口 + CAS 并发计数）、QuotaKey、RateLimitDecision/Reason
├─ downstream/  DownstreamInvoker 抽象、HttpDownstreamInvoker（RestClient）、
│               DownstreamException/Failure、MockDownstreamController（本地模拟下游）
├─ error/       GatewayErrorCode、GatewayException、ApiError（统一错误体）
├─ core/        GatewayService（唯一编排链路与唯一配额扣减点）
└─ web/         GatewayController（/gateway/items、/gateway/echo）、GlobalExceptionHandler
```

> 说明：限流状态为单实例本地内存态，不跨进程共享；下游调用通过
> `DownstreamInvoker` 接口隔离，测试以确定性替身替换真实 HTTP。
