# 怎么测出你自己的数字

本文不提供"官方跑分"，因为并发性能高度依赖平台、网络和机型。
这里只给可复现的步骤。

## 一、先确认你在测什么

单轮耗时由三部分构成：

```
单轮耗时 = max(任务排队时间, 下游处理时间) + 调度开销 + 汇总开销
```

对 600 路、每路 1 次开流 + 1 次关流：

```
下游处理时间 ≈ 通道数 × 2 × RTT ÷ 并发度
```

代码里的 `ProbeReport.durationMs` 就是端到端耗时，直接拿来用，不用自己掐表。

## 二、用 mock 模式压一轮（不需要真平台）

```bash
# 600 路，每次调用模拟 20ms
mvn spring-boot:run -Dspring-boot.run.profiles=mock

curl -X POST http://localhost:8080/livegbs-camera-status/api/v1/probe
curl -s http://localhost:8080/livegbs-camera-status/api/v1/probe/latest | grep durationMs
```

改并发度对比：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=mock \
  -Dspring-boot.run.arguments=--camera-status.max-concurrency=8
```

或者直接改 `application-mock.yml` 里的 `latency-ms` 模拟不同的平台响应速度。

> mock 测出来的是**本机调度与模板开销**，不含真实网络和平台排队。
> 它的价值是：验证并发控制是否生效、有没有线程泄漏、耗时是否随并发线性下降。

## 三、接真实平台时的测法

1. 从 `max-concurrency=8` 起步；
2. 每轮后读 `durationMs` 和 `offline` / `error` 的数量；
3. 并发翻倍，记录三个数：`durationMs`、`error`、平台的 CPU 与上行带宽；
4. 出现下面任一情况就说明过头了，退回上一档：
   - `error` 明显上升（平台开始 5xx 或超时）
   - `offline` 突然变多（平台出流许可被占满，被拒绝的不一定是真离线）
   - 平台 CPU 打到 80% 以上

## 四、记录模板

| 并发度 | 通道数 | RTT | durationMs | online | offline | error | 备注 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 8 | 600 | | | | | | |
| 16 | 600 | | | | | | |
| 32 | 600 | | | | | | |
| 64 | 600 | | | | | | |

## 五、几个容易踩的观测误区

- **别用 `time curl` 测**：`/probe` 是异步的，它返回的是"已提交"。
- **别在探测期间调 `/health/livegbs`**：它会强制登录一次，可能让你误判 token 失效逻辑。
- **`offline` 变多不等于性能问题**：可能是平台真的扛不住了，也可能是那批设备本来就不在线，
  要看同一批通道在不同并发下的对比，而不是绝对值。
- **关注 `probeCostMs` 的分布**：单路耗时从 40ms 涨到 2s，说明平台在排队，这时候加并发只会更慢。
