# http-router-java

HTTP 请求路由与匹配服务：接收请求方法、路径与头部条件，返回命中的处理器
标识；命中不确定时给出可区分、可解释的未命中或冲突原因。

## 能力

- 静态段、参数段（含正则约束）、单段通配 `*`、尾部 catch-all `**`、
  括号可选片段的组合匹配，提取路径变量。
- 请求方法 + 路径 + 头部条件（EXACT / PRESENT / ABSENT / REGEX）联合匹配。
- 确定且稳定的命中优先级：显式 priority → 路径特异性 → 头部条件数；
  无法区分时返回冲突，绝不静默回退。
- 未命中（404）/ 方法不允许（405）/ 匹配冲突（409）三类错误严格区分。
- 路由表热更新原子生效：读路径无锁、不被写阻塞；读者只见旧表或新表。
- 重复注册与同优先级模式重叠在注册期检测，冲突被拒绝且保留原表。
- 快照持久化（原子写）、启动自动恢复最近已提交版本、历史版本回滚，
  失败恢复不产生半更新状态。
- 大规模路由集的索引化查找与构建（trie），含并发与性能自动化测试。

## 构建与测试

```bash
mvn test
```

关键测试：

- `PathPatternParserTest` / `RouteTableMatchTest`：组合匹配与优先级
- `RouteValidatorTest`：重复注册与冲突拒绝
- `ConcurrencyTest`：并发热更新下读不阻塞、只见整代表
- `LargeScalePerformanceTest` / `PerfNumbersTest`：大规模路由集性能
- `FileSnapshotStoreTest` / `RestartRecoveryIntegrationTest`：
  持久化、损坏回退与重启恢复
- `RouterControllerIntegrationTest`：HTTP 端到端

## 文档

匹配语法、优先级规则、冲突策略、并发模型与持久化语义见
[`docs/routing-semantics.md`](docs/routing-semantics.md)。
