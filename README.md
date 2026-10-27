# 多线程试手：不到六百路视频拉流

> 一个二十来个文件的 Spring Boot 小服务：每十分钟把 600 路国标摄像头的流**真实拉一遍**、拉完立刻关掉，
> 再用拉流结果去对账平台上报的"在线"。

[![CI](https://github.com/baijin-chao/-600---/actions/workflows/ci.yml/badge.svg)](https://github.com/baijin-chao/-600---/actions/workflows/ci.yml)
[![Java](https://img.shields.io/badge/Java-8-blue)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.6.6-green)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

> 仓库地址：<https://github.com/baijin-chao/-600--->

---

## 1. 起因：平台说在线，客户端却黑屏

LiveGBS 的通道列表里 `Status=ON`，意思是**信令在线**——设备心跳还在、SIP 注册没掉。
但它不代表你能拉到流：编码器可能卡死、平台出流通道可能被占满、媒体端口可能不通。

于是业务方看到"在线"，点开却黑屏。让人一路一路点播去验证？600 路要点到下班。

所以需要一个东西：**不相信平台上报的状态，自己拉一次**。

这个服务就是干这个的：

```
登录 → 拉全量通道 → 逐路真实取流(stream/start) → 立即关流(stream/stop) → 聚合结果 → 对外查询
```

每路通道输出两个状态：

| 字段 | 含义 | 来源 |
| --- | --- | --- |
| `platformStatus` | 平台上报的信令状态 | LiveGBS channellist |
| `probeStatus` | 真实取流结论 | 本服务拉流结果 |

两者不一致（平台 `ON`、实际 `OFFLINE`），就是**假在线**。

---

## 2. 场景与约束

先把题面写清楚，后面所有选择都是从这里推出来的：

| 维度 | 实际情况 |
| --- | --- |
| 通道规模 | 不到 600 路 |
| 单路成本 | 1 次开流 + 1 次关流，两次 HTTP 往返 |
| 周期 | 10 分钟一轮 |
| 部署 | 单机、单进程 |
| 任务特征 | IO 密集型、互相独立、幂等、无顺序要求 |
| 下游 | 一台 GB28181 平台，**它不是无限的** |

最后一条最关键：并发拉流会吃平台的 CPU、带宽和出流许可。
所以这不是"线程开得越多越好"的题，而是**既要快、又不能把平台打挂**的题。

---

## 3. 结构

```
                  ┌──────────────────────────────┐
   @Scheduled ───▶│                              │  ① 取 token（带缓存）
   POST /probe ──▶│        探测编排器             │  ② 拉全量通道
                  │   (单飞 + 防重入)             │  ③ 扇出：逐路取流
                  └──────────────┬───────────────┘  ④ 汇聚：等待全部完成
                                 │                 ⑤ 逐路立即关流
                ┌────────────────┴─────────────────┐
                ▼                                  ▼
        ThreadPoolExecutor(64)              Semaphore(保护下游)
                │                                  │
                └────────────────┬─────────────────┘
                                 ▼
                     快照 Map ── 原子替换 ──▶ GET /api/v1/cameras
```

分层：

- `web/` —— HTTP 协议层，只做参数与响应
- `service/` —— 编排：任务生命周期、token、结果归类、快照发布
- `client/` —— 接口抽象：`LiveGbsGateway`（真实实现 / mock 实现）
- `commons/` —— 横切：统一响应、异常、并发模板

并发逻辑**不放**在 service 里，而是抽成 `BatchTaskTemplate`。
理由是：那段代码的正确性论证（三条不变量）和业务无关，混在一起两边都看不清。

---

## 4. 多线程部分

### 4.1 四个工具，各管一件事

| 工具 | 职责 | 如果没有它 |
| --- | --- | --- |
| `ThreadPoolExecutor` | 谁去干活 | 每路一个 `new Thread`，线程数失控 |
| `Semaphore` | 同时最多几个请求打到下游 | 线程池一调大，平台就被打挂 |
| `CountDownLatch` | 怎么知道整批干完了 | 只能轮询，或者提前返回半成品 |
| `ConcurrentLinkedQueue` | 结果往哪放 | 普通 `ArrayList` 并发写会丢数据 |
| `AtomicBoolean` | 怎么防止两轮重叠 | 定时和手动同时触发，跑两轮互相覆盖 |

> 线程数和并发数故意拆成两个配置：`thread-count` 管本机并行能力，
> `max-concurrency` 管下游压力。默认都给了 64，但它们是两个概念。

### 4.2 三条不变量

整个模板的正确性只靠这三条，其他都是细节：

```java
// ① 提交与放行配对：不管走哪条路，finally 里必然 countDown 一次
try {
    acquired = permits.tryAcquire(permitWaitMillis, TimeUnit.MILLISECONDS);
    if (!acquired) { /* 记为失败 */ return; }
    result = task.apply(item);
} catch (Throwable ex) {
    /* 记为失败，绝不让异常逃逸出线程 */
} finally {
    if (acquired) permits.release();
    latch.countDown();          // ← 唯一出口
}

// ② 许可获取与释放配对：acquired 标志保证只释放"真的拿到过"的那次

// ③ 每一层都有上界：拿许可超时、等整批超时、队列有界
acquired = permits.tryAcquire(permitWaitMillis, TimeUnit.MILLISECONDS);
latch.await(batchTimeoutMillis, TimeUnit.MILLISECONDS);
new ThreadPoolExecutor(..., new ArrayBlockingQueue<>(queueCapacity), ..., new BackpressurePolicy());
```

这三条不是我拍脑袋想的，是第一版全部踩过之后反推出来的——见下一节。

---

## 5. 踩过的五个坑

> 下面每一段都是这个项目第一版的真实写法（后来重写了）。
> 留在这里，是因为"知道会怎样坏"比"知道怎么写对"更难得到。

### 坑 1：信号量泄漏，让服务**静默停摆**

```java
// 第一版
channelInfoList.forEach(channel -> executor.execute(() -> {
    try { semaphore.acquire(); } catch (InterruptedException ex) { log.error(...); }
    Map<String, String> result = start(...);   // ← 这里可能抛未捕获异常
    cdl.countDown();
    semaphore.release();
}));
cdl.await();   // 没有任何超时
```

`start()` 里只要抛出一个没被捕获的异常（比如错误响应里取状态码时 NPE），
`countDown()` 和 `release()` **都不会执行**：

- 闭锁计数永远差几个 → `await()` 永久阻塞；
- 许可漏掉几个 → 多发几十路之后，后面的任务全卡在 `acquire()`；
- 更糟的是"运行中"标志位永远停在 false 状态 → 之后每一轮都被"禁止重复执行"挡掉。

结果：**服务进程活着，日志也不报错，但再也不会同步任何数据了。**
这种"静默失效"比直接崩溃难查得多。

修法就是 4.2 的第 ① ② 条：`finally` 收口 + `acquired` 标志 + 等待超时。

### 坑 2：用裸 `boolean` 做防重入

```java
if (threadPoolIsTerminated) {
    threadPoolIsTerminated = false;
    // ... 跑一轮
}
```

`boolean` 既没有 `volatile` 的可见性保证，"读—判断—写"也不是原子操作。
定时线程和 HTTP 线程完全可能同时进来，一起把两轮探测的请求打到平台上。

改成 `AtomicBoolean.compareAndSet`，一行解决：

```java
if (!running.compareAndSet(false, true)) return busyReport();
try { ... } finally { running.set(false); }
```

### 坑 3：每轮 `new` 一个线程池，还是无界队列

```java
ThreadPoolExecutor executor = new ThreadPoolExecutor(100, 100, 0L,
        TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
// ... 用完
executor.shutdown();
```

两个问题：

- 每 10 分钟创建、销毁 100 个线程，纯浪费；
- `LinkedBlockingQueue` 无界，意味着**永远不会拒绝**。下游变慢时任务只会无限堆积，
  最后以 `OutOfMemoryError: unable to create new native thread` 收场——README 里那条真实故障就是这么来的。

改成进程内单例池 + 有界队列 + 背压式拒绝策略：队列满时由提交线程自己执行，压力依然在提交侧暴露，但不会把任务丢掉。（这条是写测试时抓到的：100 个任务用 `AbortPolicy` 只成功了 99 个——超出"线程数 + 队列容量"的那部分被静默丢弃，报告里只留一条失败记录，很容易被当成下游偶发失败。）

### 坑 4：结果只 `put` 不 `remove`

```java
CAMERA_STATUS_MAP.put(key, result);   // 每轮只写，从不清理
```

删掉的、下线的、改过编号的通道会永远留在 Map 里，变成"僵尸通道"。
下游拿到一份看起来很正常、其实包含早已不存在设备的数据。

改成**每轮构建新 Map，整体替换引用**：

```java
this.snapshot.set(Collections.unmodifiableMap(batch.toValueMap()));
```

读方永远看到一份完整快照，不会看到"清空到一半"的中间态。

### 坑 5：把"平台故障"记成"设备离线"

这是最隐蔽的一个。

第一版只在批次开始时登录一次拿 token，然后 600 路共用。
一批跑得久了 token 过期，后半批全部取流失败——而这些失败被统一记成了"摄像头离线"。

**基础设施故障被伪装成业务数据**。它不会触发任何告警，只会悄悄输出一份错的数据。

修法分两层：

```java
// 1. 拿到 401/403（或响应体暗示 token 失效）时，刷新 token 并只重试这一路
catch (LiveGbsException ex) {
    if (!ex.isAuthError()) throw ex;
    token = tokenProvider.getToken(true);
    result = gateway.startStream(token, channel);
}

// 2. 结论分三类，而不是两类
//    ONLINE  取流成功
//    OFFLINE 平台明确拒绝（设备离线 / 资源不足）—— 是业务结论
//    ERROR   超时、5xx、网络异常 —— 结论不可信，不要当成"离线"
builder.probeStatus(ex.isPlatformError() ? ProbeStatus.ERROR : ProbeStatus.OFFLINE);
```

---

## 6. 几个顺手的决定

| 决定 | 原因 |
| --- | --- |
| token 缓存 + 单飞登录 | token 失效瞬间若并发刷新，会把平台打出一串登录请求；`synchronized` 双重检查只登一次 |
| `GET` 触发改成 `POST` + `202` | 原实现用 GET 触发全量拉流，会被浏览器预取、监控探活、爬虫误触发；而且同步等 600 路，HTTP 早就超时了 |
| 上一轮没结束返回 `409` | 排队只会把问题推后，不如立刻告诉调用方 |
| 关流放在 `finally` | 探测不能把平台的出流通道占着；失败路径更容易漏 |
| 异常不再把堆栈返回给调用方 | 原实现把完整 stacktrace 塞进 `data`，既泄露内部结构又让响应体没法看 |
| 日志关闭 Forest 的响应内容打印 | token 在 URL 上，打日志等于把凭据写进磁盘 |
| 实体配置全部走环境变量 | 仓库里不该出现真实 IP 和密码 |

---

## 7. 跑起来

### 环境要求

- JDK 8 及以上（本项目在 JDK 17.0.10 上编译并跑通全部测试，字节码目标 1.8）
- Maven 3.6+

### 7.1 演示模式（不需要 LiveGBS）

内置 600 路模拟通道，单次调用模拟 20ms 延迟，每 7 路里有 1 路平台侧离线、每 13 路里有 1 路"假在线"。

```bash
git clone https://github.com/baijin-chao/-600---.git
cd livegbs-camera-status

mvn spring-boot:run -Dspring-boot.run.profiles=mock
```

另开一个终端：

```bash
# 触发一轮探测（立即返回 202）
curl -X POST http://localhost:8080/livegbs-camera-status/api/v1/probe

# 看这一轮的统计
curl http://localhost:8080/livegbs-camera-status/api/v1/probe/latest

# 看全量快照
curl http://localhost:8080/livegbs-camera-status/api/v1/cameras
```

### 7.2 接真实平台

```bash
export LIVEGBS_BASE_URL=http://192.168.1.10:10000
export LIVEGBS_USERNAME=admin
# LiveGBS 的 password 参数是 32 位大写 MD5，不是明文
export LIVEGBS_PASSWORD=$(printf '%s' 'your-password' | md5sum | awk '{print toupper($1)}')

mvn spring-boot:run
```

Windows PowerShell：

```powershell
$md5 = [System.BitConverter]::ToString(
    [System.Security.Cryptography.MD5]::Create().ComputeHash(
        [Text.Encoding]::UTF8.GetBytes('your-password'))).Replace('-','')
$env:LIVEGBS_PASSWORD = $md5
```

打包运行：

```bash
mvn -DskipTests package
java -jar target/livegbs-camera-status.jar
```

### 7.3 Docker

```bash
docker build -t livegbs-camera-status:1.0.0 .

docker run -d --name livegbs-camera-status -p 8080:8080 \
  -e LIVEGBS_BASE_URL=http://192.168.1.10:10000 \
  -e LIVEGBS_USERNAME=admin \
  -e LIVEGBS_PASSWORD=0123456789ABCDEF0123456789ABCDEF \
  -e CAMERA_STATUS_ADMIN_TOKEN=change-me \
  livegbs-camera-status:1.0.0
```

或者复制 `.env.example` 为 `.env`，然后 `docker compose up -d`。

---

## 8. 接口

统一前缀 `/livegbs-camera-status/api/v1`，写操作（POST / DELETE）需要 `X-Admin-Token` 头
（未配置 `camera-status.admin-token` 时退化为不校验，启动会打 WARN）。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/cameras` | 全量快照 + 汇总统计 |
| GET | `/cameras/{deviceId}/{channelId}` | 单路状态 |
| POST | `/probe` | 触发一轮探测，202 已提交 / 409 上一轮未结束 |
| GET | `/probe/latest` | 最近一轮报告 |
| DELETE | `/cameras` | 清空快照 |
| GET | `/health/livegbs` | 平台连通性（会真实登录一次） |
| GET | `/actuator/health` | 应用健康检查 |

返回体统一为：

```json
{
  "success": true,
  "code": "0",
  "message": "处理成功",
  "data": {},
  "timestamp": 1761300000000,
  "requestId": "b7c1f2a3d4e54f60"
}
```

完整字段说明见 [docs/API.md](docs/API.md)。

---

## 9. 观测

一轮探测结束会打出这样一行：

```
[camera-probe] 批次开始，任务总数 600，可用并发许可 64
[7f2c1a9b3d40] 探测结束：总数 600，取流在线 552，取流离线 46，探测异常 2，耗时 1876 ms
```

想定位具体哪一路失败：

```bash
grep "任务执行异常" logs/livegbs-camera-status.log | head
```

`GET /api/v1/probe/latest` 里的 `status` 有四种取值：

| 值 | 含义 | 该干什么 |
| --- | --- | --- |
| `SUCCESS` | 全部通道都有结论 | 正常 |
| `PARTIAL` | 有任务级异常（极少见） | 看日志 |
| `EMPTY` | 平台没返回任何通道 | 检查设备是否接入平台 |
| `FAILED` | 登录或拉列表就失败了 | 检查 `base-url` / 账号 / 网络 |

---

## 10. 实验：并发度到底给多少

不给结论，给算式。单轮耗时取决于三件事：

```
单轮耗时 ≈ 通道数 × 单路往返次数 × 单次 RTT ÷ 并发度
```

按 600 路、单路 2 次往返估算（**理论下界**，假设平台不排队、无锁竞争）：

| 并发度 | RTT=20ms | RTT=50ms |
| ---: | ---: | ---: |
| 1 | 24.0 s | 60.0 s |
| 8 | 3.0 s | 7.5 s |
| 16 | 1.5 s | 3.8 s |
| 32 | 0.8 s | 1.9 s |
| 64 | 0.4 s | 0.9 s |

真实环境一定比这慢，因为**瓶颈在平台侧**：它要真的去跟设备协商、开 RTP 端口、推流。

所以调参顺序是：

1. 从 `thread-count=8, max-concurrency=8` 起步；
2. 看 `/probe/latest` 的 `durationMs`，逐步往上加；
3. 同时盯住平台的 CPU、带宽和"取流失败率"——如果失败率随并发上升，说明已经把平台压出问题了，退回去；
4. 真实的 600 路环境里，往往 16~32 就够用了，64 只是本项目的默认值。

复现方法见 [docs/BENCHMARK.md](docs/BENCHMARK.md)。

---

## 11. 项目结构

```
src/main/java/com/wy
├── LiveGBSCameraStatusApplication.java
├── camera/status
│   ├── client/          # LiveGBS 接口抽象 + Forest 实现 + 异常翻译
│   ├── config/          # 配置绑定、线程池、鉴权拦截器
│   ├── mock/            # 演示用网关（profile=mock 才会加载）
│   ├── model/           # 领域模型：CameraStatus / ProbeReport / CameraSnapshot
│   ├── service/         # 编排、token 管理
│   └── web/             # REST 接口
└── commons
    ├── concurrent/      # BatchTaskTemplate：扇出-汇聚模板
    ├── config/          # Tomcat 特殊字符放行 + 拦截器注册
    ├── exception/       # 全局异常处理
    └── response/        # 统一响应体与错误码
```

测试：

```bash
mvn test
```

- `BatchTaskTemplateTests` —— 模板的 6 条行为约束（异常隔离、重入拒绝、并发上限、超时降级…）
- `MockProbeTests` —— 17 路模拟通道跑完整链路，验证"假在线"能被识别
- `ForestGatewayTests` —— 用 JDK 自带 `HttpServer` 假装 LiveGBS，验证 URL 拼装与异常翻译

---

## 12. 已知限制

诚实一点，这些都没做：

- **单机内存态**：快照在 `ConcurrentHashMap` 里，重启即空；多副本会各自探测、互相不理。
- **探测成本高**：每轮对全量通道真实开流，对设备和带宽是实打实的压力。更省的做法是先用平台轻量接口粗筛，只对可疑通道做真实取流。
- **没有退避**：已确认离线且短时间不会恢复的设备，仍然每轮都被探测一遍。
- **没有指标暴露**：目前只有日志和 `/probe/latest`，没有 Prometheus metrics。
- **没有前端**：纯接口服务。

Roadmap（如果有人一起用的话）：

- [ ] 失败退避：连续 N 轮离线则降低探测频率
- [ ] 增量探测：只探测状态变化的通道
- [ ] 快照落 Redis，支持多副本只跑一份
- [ ] Micrometer 指标：`probe_duration`、`probe_failed_total`
- [ ] 单路探测接口，方便排障

---

## 13. License

[MIT](LICENSE)

> 项目里对 LiveGBS 的使用仅限其公开 HTTP API，不包含任何平台代码或协议栈实现。
