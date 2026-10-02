# http-router-java

HTTP 请求路由与匹配服务：接收 **方法 + 路径 + 头部条件**，返回唯一命中的处理器标识；
命中不确定时明确返回「未命中」或「冲突」，不会静默回退到任意处理器。

- 路径模式：静态段、参数段 `{id}`、正则约束 `{id:\d+}`、通配 `*`、可选尾段 `{id?}`
- 头条件：精确 / 取反 / 存在 / 正则（`v`、`!v`、`*`、`~regex`）
- 稳定、可解释的匹配优先级；注册时做重复与冲突检测
- 路由表热更新：一次更新原子生效，读路径无锁、不被写入阻塞
- 快照回滚与 JSON 持久化，重启恢复最近一次已提交的路由表

## 1. 快速开始

```bash
mvn spring-boot:run
```

注册一条路由：

```bash
curl -X POST localhost:8080/router/routes -H 'Content-Type: application/json' -d '{
  "id": "user-detail",
  "method": "GET",
  "pattern": "/users/{id:\\d+}",
  "headers": {"X-Version": "1"},
  "handlerId": "UsersHandler",
  "priority": 0
}'
```

匹配：

```bash
curl -X POST localhost:8080/router/match -H 'Content-Type: application/json' -d '{
  "method": "GET", "path": "/users/42", "headers": {"X-Version": "1"}
}'
# {"status":"HIT","routeId":"user-detail","handlerId":"UsersHandler", ...}
```

## 2. 路径模式语法

| 语法 | 含义 | 示例 | 匹配 | 不匹配 |
|---|---|---|---|---|
| `/a/b` | 静态段，逐段精确匹配 | `/users/list` | `/users/list` | `/users/42` |
| `/{name}` | 参数段，匹配任意一个非空段 | `/users/{id}` | `/users/42` | `/users`、`/users/42/x` |
| `/{name:regex}` | 正则参数段，值需被正则**整段**匹配 | `/items/{id:\d+}` | `/items/123` | `/items/ab` |
| `/*` | 通配段，匹配**零个或多个**剩余段，只能位于末尾 | `/files/*` | `/files`、`/files/a/b` | `/other` |
| `/{name?}` | 可选参数段，只能出现在尾部（可连续多个） | `/users/{id?}` | `/users`、`/users/7` | `/users/7/8` |

约束：模式必须以 `/` 开头；不允许空段（`/a//b`）；`*` 后不能再有段；
非尾段不允许是可选段。请求路径末尾的 `/` 会被归一化去掉，`?` 之后的查询串会被忽略。

## 3. 头部条件语法

头部名称大小写不敏感（内部统一小写）。每个头对应一个条件表达式：

| 表达式 | 语义 |
|---|---|
| `v` | 精确等于 `v`（头部不存在则不满足） |
| `!v` | 不等于 `v`；头部**不存在也视为满足** |
| `*` | 头部必须存在 |
| `~regex` | 头部值被正则整段匹配 |

同一路由内自相矛盾的条件（如 `X-Version=1` 与 `X-Version=2`）注册时即被拒绝。

## 4. 匹配结果（三态，绝不静默兜底）

- `HIT`：唯一命中，返回 `routeId` / `handlerId`
- `NO_MATCH`：未命中，`reason` 可区分原因：
  - 路径上有路由但方法都不匹配（会列出已注册的方法）
  - 方法匹配但头部条件不满足
  - 路径完全无路由
- `CONFLICT`：多条路由以相同的最高优先级同时命中，返回全部候选 `candidates`。
  正常情况下注册阶段就会拒绝产生该歧义的规则；此状态是安全兜底。

方法或头条件不匹配的请求只会得到 `NO_MATCH`（原因可区分），不会被误判为命中或冲突。

## 5. 优先级规则

多个路由同时命中一个请求时，按以下顺序逐条比较，先区分出高低者为准：

1. **显式优先级 `priority`**：数值越大越优先（默认 0）。用于人工消歧。
2. **路径特异性**：从左到右逐段比较 rank，第一段不同即决定结果：
   `静态(5) > 正则(4) > 参数(3) > 可选(2) > 通配(1)`。
   - 因此 `/a/b` 胜过 `/a/{x}` 胜过 `/a/*`；`/items/{id:\d+}` 胜过 `/items/{id}`。
   - `/a/{x}` 与 `/{y}/b` 同时匹配 `/a/b` 时，第一段不同：静态在前的 `/a/{x}` 胜出
     （「靠前的段优先」，规则稳定且可解释）。
   - 一条模式是另一条的前缀、且另一条多出的段全部可跳过（可选段或通配段）时，
     **更短的精确模式优先**：`/users` 胜过 `/users/{id?}` 对 `/users` 的匹配；
     `/files` 胜过 `/files/*` 对 `/files` 的匹配（通配段匹配零个段时仍让位于精确规则）。
     该前缀规则与逐段比较永远给出一致结论：只有逐段比较完全并列时才进入前缀判定。
   - 该次序与路径段数、注册顺序、请求方法、头条件均无关；对同一对规则，
     任何匹配到的公共请求都命中同一个获胜方。
3. **头条件数量**：仍然并列时，头条件更多的路由更具体、优先。
4. **方法特异性**：具体方法（如 `GET`）优先于 `ANY`。

以上全部相同仍并列 → `CONFLICT`，不做任意选择，也不依赖注册顺序。

## 6. 注册校验、冲突检测与原子更新

每次新增 / 删除（支持批量）都会先基于旧表构建并**完整校验**新表，然后再切换：
注册阶段与查找阶段使用**同一套优先级语义**（第 5 节），因此任意两条请求集合重叠的
规则只有两种结局：注册时被拒绝（旧表完整保留），或被允许共存且所有公共请求都命中
优先级获胜方——不存在「注册放行、查找却按相反次序命中」的组合。

- 路由 `id` 重复 → 拒绝（409，Duplicate route id）
- 方法 + 路径模式 + 头条件完全相同 → 拒绝（409，Duplicate registration）
- 模式可能匹配同一请求、方法可能重叠、头条件可同时满足，且第 5 节的
  优先级**无法分出高低**（priority 相同、特异性相同、头条件数相同、方法特异性相同）
  → 拒绝（409，conflicts ...）
- 以下情况不算冲突，允许共存：
  - 方法不重叠（`GET /x` 与 `POST /x`）
  - 模式不可能匹配同一路径（静态前缀互斥、正则与静态值确定不兼容等）
  - 头条件互斥（一个要求 `X-Version=1`，另一个要求 `X-Version=2`）
  - 显式 `priority` 不同，或路径特异性可分高低——包括精确规则与覆盖它的
    通配/可选尾段规则（如 `/files` 与 `/files/*`、`/files` 与 `/files/{id?}`），
    它们的公共请求恒命中精确规则
- 删除不存在的 `id` → 404。
- 批量更新中任意一条不合法 → **整批拒绝，旧表完整保留**。
- 删除任意一条规则后，其余规则相互间的命中结果不变（优先级是逐对比较的全序）。

正则段之间的重叠判定采用保守策略：两段正则字符串不同即视为「可能重叠」
（一般正则的交集不可判定），必要时可用显式 `priority` 消歧。

## 7. 热更新与并发模型

- 当前路由表保存在 `volatile` 引用中，指向**不可变** `RouteTable`。
- 读路径只做一次 volatile 读，随后在不可变对象 + 段 trie 上查找，
  **不持有任何锁，也不会被写入阻塞**；读到的要么是旧表、要么是新表，不会有部分规则生效。
- 写路径串行化：构建并校验新表 → 原子落盘 → 引用切换。校验失败时旧表与文件都不变。
- 查找基于段 trie，典型复杂度为 `O(路径深度 × 正则分支数)`，与路由总量基本无关。
  在开发机上 10,000 条路由的实测平均查找约 0.9 µs（见
  `LargeScalePerformanceTest`）。
- 写校验复用同一套 trie 遍历：注册新路由时只收集 trie 上可能与其模式重叠的候选做
  成对判定，因此单次插入校验同样是路径深度量级。实测一次性提交 20,000 条互斥路由
  约 0.2 秒（含原子落盘）。

## 8. 持久化、恢复与回滚

- 已提交的路由表以 JSON 保存到 `router.snapshot-file`（默认 `data/router-snapshot.json`）；
  回滚历史保存在同目录的 `*.history.json`。
- 写入采用「同目录临时文件 + 原子 move」，崩溃后只会留下旧文件或新文件，不会有半写状态。
- 启动时自动恢复最近一次已提交的表与回滚历史；文件不存在则从空表启动；
  主表文件损坏则快速失败（抛异常），绝不加载部分状态。
- `POST /router/rollback` 在提交历史中回退到上一版（可连续回退到初始表，重启后仍可继续回滚），
  回滚结果同样原子落盘并在重启后生效；回退后再提交会清空 redo 分支。
- 主表文件始终是路由表的唯一事实来源；历史文件为最佳努力一致性：若崩溃恰好发生在
  主表已切换、历史文件尚未切换之间，重启后回滚历史可能缺少最后一步（不影响当前表正确性）。

## 9. HTTP API

| 方法与路径 | 说明 |
|---|---|
| `POST /router/match` | 匹配查询，body：`{method, path, headers}` |
| `POST /router/routes` | 新增单条路由（201），冲突返回 409 |
| `DELETE /router/routes/{id}` | 删除路由，不存在返回 404 |
| `POST /router/routes/batch` | 批量原子更新，body：`{additions:[...], removals:[id...]}` |
| `GET /router/routes` | 列出当前表全部路由 |
| `POST /router/rollback` | 回滚到上一版快照 |

路由对象字段：`id`（可省略，自动生成）、`method`（GET/POST/.../ANY，`*` 同 ANY）、
`pattern`、`headers`（头名 → 条件表达式）、`handlerId`（省略时与 id 相同）、
`priority`（默认 0）。

错误响应统一为 `{"error": "..."}`，状态码：400（模式/表达式非法）、
404（删除目标不存在）、409（重复 / 冲突 / 无历史可回滚）。

## 10. 配置

| 属性 | 默认值 | 说明 |
|---|---|---|
| `router.snapshot-file` | `data/router-snapshot.json` | 路由表快照文件路径 |

## 11. 测试

```bash
mvn -o clean test
```

覆盖：

- `PatternMatchingTest`：静态/参数/正则/通配/可选段、方法与头条件组合
- `PriorityAndConflictTest`：全部优先级规则、运行期冲突、注册期重复/冲突拒绝、整批原子拒绝
- `OverlapPriorityTest`：重叠规则命中次序（精确 vs 通配尾段、精确 vs 可选尾段、正则 vs 参数、
  参数 vs 通配、尾部斜杠变体），注册与查找两阶段判定一致性，删除规则后命中不变
- `HotReloadConcurrencyTest`：并发热更新原子可见性、并发冲突注册裁决、持续写入下读性能、
  重叠规则在热更新与拒绝写入下的命中一致性
- `PersistenceRecoveryTest`：重启恢复、拒绝更新不落盘、损坏快照快速失败、回滚、批量持久化
- `LargeScalePerformanceTest`：10,000 条路由的批量注册、查找正确性与性能、大规模下冲突检测、
  含重叠组合的大规模场景
- `RouterApiIntegrationTest`：REST 端到端（201/400/404/409、三态匹配结果、
  精确路径与通配路径重叠用例）

手工复现「精确 vs 通配」场景（验收用）：

```bash
mvn spring-boot:run
curl -X POST localhost:8080/router/routes -H 'Content-Type: application/json' \
  -d '{"id":"wild","method":"GET","pattern":"/files/*","handlerId":"WildcardHandler"}'
curl -X POST localhost:8080/router/routes -H 'Content-Type: application/json' \
  -d '{"id":"exact","method":"GET","pattern":"/files","handlerId":"ExactHandler"}'
curl -X POST localhost:8080/router/match -H 'Content-Type: application/json' \
  -d '{"method":"GET","path":"/files","headers":{}}'
# 期望：{"status":"HIT","routeId":"exact","handlerId":"ExactHandler", ...}
```
