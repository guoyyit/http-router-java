# HTTP 路由与匹配服务 — 匹配语义与优先级规则

本文档说明路由的路径语法、联合匹配规则、命中优先级、注册冲突检测、热更新
一致性以及快照持久化/回滚语义。所有规则均有自动化测试覆盖
（见 `src/test/java/com/github/guoyyit/httprouterjava/tests`）。

---

## 1. 路径模式语法

一条路由的 `path` 必须以 `/` 开头，由若干段组成：

| 写法 | 名称 | 匹配规则 |
| --- | --- | --- |
| `/users` | 静态段 | 逐字符、区分大小写精确匹配 |
| `/users/{id}` | 参数段 | 匹配任意一个非空段，提取为路径变量 `id` |
| `/users/{id:[0-9]+}` | 正则参数段 | 段内容必须与正则**完整匹配**（`Matcher.matches()`）才命中 |
| `/assets/*` | 单段通配 | 匹配任意一个非空段，不提取变量 |
| `/files/**` 或 `/files/**:tail` | 尾部 catch-all | 只能出现在最后一段；匹配零个或多个剩余段，可命名并提取尾部路径 |
| `/api(/v1)/users` | 可选片段 | 括号片段可有可无；不支持嵌套，最多 10 个可选组 |

可选片段在**注册时展开**为有限个具体段序列（N 个可选组 → 至多 2^N 个
候选），请求命中其中任意一个展开结果即命中该路由。

非法语法（不以 `/` 开头、空段 `//`、括号不配对、嵌套可选组、`**` 不在末尾、
畸形参数名、非法正则、重复变量名等）在注册时以 `INVALID_ROUTE` 拒绝。

### 路径变量

- 参数段 `{name}` 与 catch-all（`:name`）的值在命中结果 `pathVariables` 中返回。
- 同一条模式（含展开后的某个具体序列）中变量名不可重复。
- catch-all 捕获剩余段以 `/` 拼接（零段时为空字符串）。

---

## 2. 请求头条件

一条路由可携带若干头部条件，头部名**不区分大小写**：

| `type` | 语义 |
| --- | --- |
| `EXACT` | 请求头必须存在且值与 `value` 相等（值忽略大小写） |
| `PRESENT` | 请求头必须存在（值任意） |
| `ABSENT` | 请求头必须不存在 |
| `REGEX` | 请求头必须存在且值与正则完整匹配 |

方法、路径、头部条件是**联合匹配**：三者全部满足才算该路由完全命中。

---

## 3. 命中优先级（确定且稳定）

当多条路由都完全命中（方法 + 路径 + 头部都满足）时，按以下顺序比较，
直到分出唯一胜者；仍无法区分则返回 **CONFLICT**，绝不静默回退：

1. **显式优先级 `priority`**：数值越大越优先（默认 0）。
2. **路径特异性**（取实际命中的那个具体展开序列计分，每段权重）：
   - 静态段 3 分
   - 参数段 2 分
   - 单段通配 1 分
   - 尾部 catch-all 0 分

   因此在权重相同时，固定前缀越长（catch-all 尾巴越短/无）越优先；
   例如 `/users/me` 胜过 `/users/{id}`，`/users/{id}` 胜过 `/users/*`。
3. **头部条件数量**：满足前提下，条件更具体（数量更多）的路由优先。
4. 以上全部相同 → 判定为歧义，抛出 `ConflictException`（HTTP 409，
   `error=CONFLICT`），响应体 `details` 列出冲突的 handlerId。

> 注意：`priority` 是最强裁决器。只要显式优先级不同，即使路径/头部重叠也
> 一定能分出唯一胜者。
>
> 正常情况下，第 4 步的歧义会在**注册时**就被检测并拒绝（见第 5 节的
> "秩相同"判定，与上述特异性/头部数规则完全一致），因此一个通过校验的
> 路由表通常总能唯一裁决；运行时的 `CONFLICT` 是对不可判定情况的安全兜底，
> 不会静默选择任何处理器。

---

## 4. 未命中 / 方法不允许 / 冲突 — 三类可区分错误

匹配不会静默落到任意处理器，失败类型严格区分：

| 场景 | 异常 | HTTP 状态 | `error` 码 |
| --- | --- | --- | --- |
| 没有任何路由的路径匹配该请求路径 | `NoMatchException` | 404 | `NO_MATCH` |
| 路径有路由匹配，但没有一条接受该方法 | `MethodNotAllowedException` | 405 | `METHOD_NOT_ALLOWED`（`details` 给出允许的方法列表） |
| 路径匹配且方法匹配，但头部条件全部不满足 | `NoMatchException` | 404 | `NO_MATCH` |
| 多条路由完全命中且无法按第 3 节分出胜负 | `ConflictException` | 409 | `CONFLICT` |

---

## 5. 注册、重复注册与冲突检测

所有写操作（新增单条、删除、整表替换）都先对**候选新表整体校验**，
通过后才构建并发布；任一校验失败则**保留原表不变**。

注册时拒绝的情形：

1. 字段非法：handlerId 为空、method/path 缺失、路径语法错、头部条件非法、
   头部正则非法等 → `INVALID_ROUTE`（400）。
2. **handlerId 重复** → `CONFLICT`（409）。
3. **完全重复**：method/path/headers/priority 完全相同的路由 → `CONFLICT`。
   - 但相同 method/path/headers、**不同 priority** 属于允许的显式覆盖，不算重复。
4. **存在某个具体请求会导致秩相同（rank tie）** 的两条同优先级、同方法路由
   → `CONFLICT`。"秩相同"严格按照第 3 节的运行时裁决定义：在某个具体请求上
   两者路径特异性得分相同、头部条件数量相同、且头部条件相互**兼容**
   （存在请求能同时满足两者）。
   - 方法不同：永不冲突。
   - 路径特异性即可稳定裁决的重叠**允许共存**，不算冲突。例如：
     - `/users/me`（静态）与 `/users/{id:[0-9]+}`（参数）：共同请求
       `/users/me` 上静态得 3 分、参数得 2 分，永远有唯一胜者，允许共存。
     - `/users/{id}`（参数，2 分/段）与 `/users/*`（通配，1 分/段）：允许共存。
     - `/files/**`（固定前缀 3 分）与 `/files/x/y`（两段静态共 6 分）：
       共同请求 `/files/x/y` 上 6 分胜过 3 分，允许共存；catch-all 仍负责
       其它尾巴长度的请求。
   - 只有真正无法裁决的才拒绝，例如 `/users/{id}` 与 `/users/{name}`
     （共同请求上同为参数段、同分同头数）、`/files` 与 `/files/**`
     （在零尾巴请求 `/files` 上同 3 分）。
   - 头部条件互斥（如同名头 `EXACT v1` vs `EXACT v2`、`PRESENT` vs `ABSENT`）：
     即使路径重叠也不冲突。
   - 显式 `priority` 不同：永不冲突（由优先级裁决）。

冲突检测基于结构 trie 索引（`OverlapIndex`），每个终端槽位记录该具体替代
序列的特异性得分；插入时沿与每个段结构相容的分支做前沿扩展，只对真正可达
同一槽位、且得分与头部数都相同的路由报冲突。路径互不相交的路由不会被比较，
整表校验复杂度接近"模式段总数 × 每层很小的分支数"，对 2 万条路由的整表
构建为亚秒级。

---

## 6. 热更新的原子性与并发模型

- 已提交的路由表保存在单个 `AtomicReference<RouteTable>` 中，`RouteTable`
  自身**不可变**（含构建期一次性生成的匹配 trie）。
- 读路径只 `get()` 一次快照并在该不可变表上完成整个查找，**不持有任何写
  锁**；并发写不会阻塞读，读者要么看到旧表、要么看到新表，绝不会看到
  "部分规则生效"的中间态。
- 写操作在内部锁上串行：`整体校验 → 构建新表 → 持久化 → 引用发布`。
  校验或持久化失败时引用不切换，线上仍是旧表。
- 每次成功提交 `version` 单调 +1。

### 查找性能

读路径通过 `RouteTrie` 索引访问与请求段相容的分支（字面量 / 参数 / 通配 /
catch-all），访问节点数由请求路径深度与该处分支数决定，与"前缀互不相关的
路由总数"无关。实测（参考 `PerfNumbersTest` / `LargeScalePerformanceTest`）：

| 路由数 | 整表构建 | 单次平均查找 |
| --- | --- | --- |
| 1,000 | ~85 ms | ~1 µs |
| 5,000 | ~45–300 ms | ~0.7–1 µs |
| 20,000 | ~150 ms | ~0.5–1 µs |

---

## 7. 快照、持久化与重启恢复

- 每次提交先把新表**持久化再发布**。快照目录由 `router.snapshot.dir`
  配置（默认 `./data/router`）。
- 文件布局：
  - `routes-v<N>.json`：每个版本不可变的完整快照；
  - `latest.json`：指向最新版本的原子指针；
  - 写入采用"同目录临时文件 + 原子 move"，崩溃不会留下半截快照。
- 启动时（`RouterBootstrap`）自动恢复最近一次已提交版本：
  - `latest.json` 损坏或其指向文件损坏时，自动回退到最新的可读版本文件；
  - 恢复是整体校验 + 整体装入，要么完整恢复某一版本，要么保持空表，
    **不存在半更新状态**。
- 历史版本按 `router.snapshot.keep`（默认 10）有界保留，超出在成功提交后
  清理；可通过 `POST /router/rollback/{version}` 回滚到历史版本——回滚会
  重新校验该版本、作为一个**新版本号**重新持久化并原子发布。
  - 目标版本不存在 → `PersistenceException`（500），当前表不变。

---

## 8. HTTP API

基准路径：`/router`

| 方法与路径 | 说明 |
| --- | --- |
| `GET /router/match?method=GET&path=/a/b`（头部即请求头） | 匹配；返回 `handlerId/version/priority/pathVariables/matchedHeaders` |
| `POST /router/routes` | 原子新增一条路由 |
| `DELETE /router/routes/{handlerId}` | 原子删除；不存在返回 404 |
| `PUT /router/routes` | 原子整表替换（body: `{"routes":[...]}`） |
| `GET /router/routes` | 查看当前版本与全部路由 |
| `POST /router/rollback/{version}` | 回滚到历史快照版本 |

路由 JSON：

```json
{
  "handlerId": "users-v2",
  "method": "GET",
  "path": "/api(/v1)/users/{id:[0-9]+}",
  "priority": 10,
  "headers": [
    {"name": "X-Version", "type": "EXACT", "value": "v2"}
  ]
}
```

`type` 取值 `EXACT | PRESENT | ABSENT | REGEX`。

---

## 9. 端到端示例

```bash
# 注册
curl -X POST localhost:8080/router/routes -H 'Content-Type: application/json' -d '{
  "handlerId":"by-id","method":"GET","path":"/users/{id:[0-9]+}","priority":0}'

# 命中（静态段特异性示例：/users/me 若同时注册会优先于 {id}）
curl 'localhost:8080/router/match?method=GET&path=/users/42'
# {"handlerId":"by-id","version":1,"priority":0,
#  "pathVariables":{"id":"42"},"matchedHeaders":{}}

# 路径不存在 -> 404 NO_MATCH
# 方法不允许 -> 405 METHOD_NOT_ALLOWED（details 为允许方法）
# 同优先级歧义 -> 409 CONFLICT（details 为冲突 handlerId）
```
