# 接口说明

统一前缀：`/livegbs-camera-status/api/v1`

所有响应都是同一个信封：

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

- `code` 为 `"0"` 表示成功，其余见[错误码](#错误码)
- `timestamp` 为服务器毫秒时间戳，`requestId` 可用于和日志对照
- 时间字段统一为 ISO-8601（UTC），例如 `2026-10-25T11:16:31.123Z`

## 鉴权

读接口（`GET`）公开访问。

写接口（`POST`、`DELETE`）需要请求头：

```
X-Admin-Token: <camera-status.admin-token 配置的值>
```

未配置 `camera-status.admin-token` 时不校验，启动日志会打 WARN。
比较使用 `MessageDigest.isEqual`，避免逐字节短路。

---

## GET /cameras

全量快照 + 汇总统计。

```json
{
  "success": true,
  "code": "0",
  "message": "处理成功",
  "data": {
    "total": 600,
    "online": 552,
    "offline": 46,
    "error": 2,
    "falseOnline": 3,
    "updatedAt": "2026-10-25T11:16:31.123Z",
    "cameras": {
      "34020000001320000001_34020000001310000001": {
        "deviceId": "34020000001320000001",
        "channelId": "34020000001310000001",
        "name": "大门口",
        "platformStatus": "ON",
        "probeStatus": "ONLINE",
        "httpCode": 200,
        "probeCostMs": 43,
        "syncedAt": "2026-10-25T11:16:30.980Z",
        "falseOnline": false
      }
    }
  },
  "timestamp": 1761300000000,
  "requestId": "b7c1f2a3d4e54f60"
}
```

| 字段 | 说明 |
| --- | --- |
| `total` | 快照里的通道数 |
| `online` / `offline` | 真实取流成功 / 平台明确拒绝 |
| `error` | 探测本身失败（超时、5xx），结论不可信 |
| `falseOnline` | `platformStatus=ON` 且 `probeStatus=OFFLINE` 的数量 |
| `updatedAt` | 快照内最新一次同步时间，为空表示还没探测过 |
| `cameras` | 键为 `设备编号_通道编号` |

单路字段：

| 字段 | 说明 |
| --- | --- |
| `platformStatus` | 平台上报的信令状态 `ON` / `OFF` |
| `probeStatus` | `ONLINE` 取流成功 / `OFFLINE` 平台拒绝 / `ERROR` 探测失败 |
| `httpCode` | 取流接口返回码；平台拒绝时为 200（业务失败），HTTP 错误时为实际码 |
| `errorMessage` | 失败原因，成功时省略 |
| `probeCostMs` | 单路耗时，含开流与关流 |
| `falseOnline` | 是否属于"假在线" |

---

## GET /cameras/{deviceId}/{channelId}

单路查询。未命中返回 `code=4040`。

```
GET /livegbs-camera-status/api/v1/cameras/34020000001320000001/34020000001310000001
```

```json
{
  "success": true,
  "code": "0",
  "message": "处理成功",
  "data": {
    "deviceId": "34020000001320000001",
    "channelId": "34020000001310000001",
    "probeStatus": "OFFLINE",
    "platformStatus": "ON",
    "httpCode": 400,
    "errorMessage": "取流失败：HTTP 400 stream start error, invite sms failed, res[400] rtp server is busy",
    "probeCostMs": 87,
    "syncedAt": "2026-10-25T11:16:31.020Z",
    "falseOnline": true
  },
  "timestamp": 1761300000000,
  "requestId": "c1d2e3f4a5b6c7d8"
}
```

---

## POST /probe

触发一轮探测，**立即返回**，不等待结果。

```bash
curl -X POST -H "X-Admin-Token: change-me" \
  http://localhost:8080/livegbs-camera-status/api/v1/probe
```

已提交（HTTP 202）：

```json
{
  "success": true,
  "code": "0",
  "message": "探测任务已提交",
  "timestamp": 1761300000000,
  "requestId": "d2e3f4a5b6c7d8e9"
}
```

上一轮未结束（HTTP 409）：

```json
{
  "success": false,
  "code": "4090",
  "message": "上一轮探测尚未结束，请稍后再试",
  "timestamp": 1761300000000,
  "requestId": "e3f4a5b6c7d8e9f0"
}
```

> 为什么是 POST + 202：GET 触发有副作用会被浏览器预取、监控探活、爬虫误触发；
> 同步等待 600 路则会直接把 HTTP 连接拖到超时。

---

## GET /probe/latest

最近一轮报告。从未执行过时 `data` 为空、`message` 为"尚未执行过探测"。

```json
{
  "success": true,
  "code": "0",
  "message": "处理成功",
  "data": {
    "runId": "7f2c1a9b3d40",
    "startedAt": "2026-10-25T11:16:29.501Z",
    "finishedAt": "2026-10-25T11:16:31.377Z",
    "durationMs": 1876,
    "total": 600,
    "online": 552,
    "offline": 46,
    "error": 2,
    "platformOnline": 555,
    "platformOffline": 45,
    "status": "SUCCESS",
    "message": "OK"
  },
  "timestamp": 1761300000000,
  "requestId": "f4a5b6c7d8e9f0a1"
}
```

`status` 取值：

| 值 | 含义 |
| --- | --- |
| `SUCCESS` | 全部通道都有结论 |
| `PARTIAL` | 有任务级异常 |
| `EMPTY` | 平台没返回任何通道 |
| `FAILED` | 登录或拉列表失败，快照保持上一轮 |

---

## DELETE /cameras

清空快照。用于排障或切换平台前重置。

```bash
curl -X DELETE -H "X-Admin-Token: change-me" \
  http://localhost:8080/livegbs-camera-status/api/v1/cameras
```

```json
{
  "success": true,
  "code": "0",
  "message": "处理成功",
  "data": true,
  "timestamp": 1761300000000,
  "requestId": "a5b6c7d8e9f0a1b2"
}
```

---

## GET /health/livegbs

平台连通性检查。**会真实登录一次**，不复用缓存 token。

```json
{
  "success": true,
  "code": "0",
  "message": "处理成功",
  "data": true,
  "timestamp": 1761300000000,
  "requestId": "b6c7d8e9f0a1b2c3"
}
```

`data=false` 表示登录失败，具体原因在服务端日志。

---

## GET /actuator/health

Spring Boot Actuator 标准健康检查，供容器与负载均衡探活：

```json
{ "status": "UP" }
```

---

## 错误码

| code | HTTP | 说明 |
| --- | --- | --- |
| `0` | 200 | 成功 |
| `4000` | 200 | 请求方法不支持 |
| `4001` | 200 | 请求参数格式错误 |
| `4010` | 200 | 未授权（`X-Admin-Token` 不匹配） |
| `4040` | 200 | 资源不存在（接口或通道） |
| `4090` | 409 | 上一轮探测未结束 |
| `5000` | 200 | 服务内部异常（详情见日志，不返回堆栈） |
| `5020` | 200 | 调用 LiveGBS 失败 |
| `5030` | 200 | LiveGBS 平台不可用 |

> 除 `409` 外，业务失败一律返回 HTTP 200 + 业务码，调用方需判断 `success`。
> 这是刻意选择（前端只解析一层），代价是不符合严格 REST 语义。
