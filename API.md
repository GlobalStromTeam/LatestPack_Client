# LatestPack Client - 后端 API 接口文档

## 基础信息

- 所有接口路径前缀：`/api/client`
- 响应格式：JSON
- 字符编码：UTF-8
- 所有请求均携带 `User-Agent: LeastPack_Client/v1.0.0`

---

## 1. 获取最新版本

客户端调用此接口与本地版本比对，判断是否需要更新。

**请求**

```
GET /api/client/latest
```

**响应**

```json
{
  "version": "1.2.0",
  "timestamp": 1719000000
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| version | String | 是 | 版本号，字符串类型，由后端自行定义规则，需保证唯一且递增 |
| timestamp | Long | 是 | 该版本发布的时间戳（毫秒级 Unix 时间戳），客户端用于拼接下载URL |

---

## 2. 获取更新版本列表

客户端传入当前本地版本，后端返回**所有晚于该版本**的版本列表（不包含当前版本本身）。若 `from` 参数为空或未传，则返回所有版本（用于首次全量更新）。

**请求**

```
GET /api/client/updates?from={当前版本}
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| from | String | 否 | 客户端当前版本号。为空时返回全部版本 |

**响应**

```json
{
  "versions": [
    {
      "version": "1.1.0",
      "timestamp": 1718000000,
      "changes": [
        { "action": "add", "path": "mods/newmod.jar" },
        { "action": "modify", "path": "config/settings.json" }
      ]
    },
    {
      "version": "1.2.0",
      "timestamp": 1719000000,
      "changes": [
        { "action": "modify", "path": "mods/newmod.jar" },
        { "action": "delete", "path": "mods/oldmod.jar" }
      ]
    }
  ]
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| versions | Array | 是 | 版本列表，按时间**从早到晚**排列 |
| versions[].version | String | 是 | 版本号 |
| versions[].timestamp | Long | 是 | 该版本发布的时间戳（毫秒级 Unix 时间戳） |
| versions[].changes | Array | 是 | 该版本中变更的文件列表 |
| versions[].changes[].action | String | 是 | 操作类型，枚举值：`add`（新增文件）、`modify`（修改文件）、`delete`（删除文件） |
| versions[].changes[].path | String | 是 | 文件相对路径，使用 `/` 分隔，如 `mods/newmod.jar`、`config/settings.json` |

**客户端合并逻辑说明：**

客户端会对多版本间的文件操作进行合并优化，规则如下：
- 同一文件在多个版本中被修改 → 只下载最新版本的该文件
- 较早版本添加/修改了某文件，较晚版本删除了该文件且之后没有再添加 → 客户端自动忽略该文件（不下载，本地若存在则删除）
- 因此后端只需如实记录每个版本的实际操作，合并逻辑由客户端处理

---

## 3. 下载文件

客户端根据更新列表中的操作，逐个请求此接口下载文件内容。用于 `add` 和 `modify` 操作的文件。

**请求**

```
GET /api/client/update/download/{timestamp}?path={文件路径}
```

| 参数 | 位置 | 类型 | 必填 | 说明 |
|------|------|------|------|------|
| timestamp | URL路径 | Long | 是 | 该文件所属版本的**时间戳**（毫秒级），客户端从更新列表中获取 |
| path | Query参数 | String | 是 | 文件相对路径，需 URL 编码，如 `mods%2Fnewmod.jar` |

**响应**

- 成功：返回文件二进制内容，`Content-Type` 根据文件类型设置或使用 `application/octet-stream`
- 建议响应头包含 `Content-Length`（文件总大小，字节），客户端用于显示下载进度

**大文件分片下载（必须支持）**

客户端对大于 32MB 的文件会使用 HTTP Range 请求进行分片下载，后端**必须**支持：

```
GET /api/client/update/download/1719000000?path=mods%2Flarge.jar
Range: bytes=0-8388607
```

后端应返回：

```
HTTP/1.1 206 Partial Content
Content-Range: bytes 0-8388607/104857600
Content-Length: 8388608
```

| 要点 | 说明 |
|------|------|
| HEAD 请求 | 客户端会先发 HEAD 请求获取 `Content-Length`，后端需支持 |
| Range 请求 | 客户端发送 `Range: bytes=start-end` 请求部分内容 |
| 206 响应 | 返回 `HTTP 206 Partial Content`，并附带 `Content-Range` 头 |
| Content-Range 格式 | `bytes start-end/total`，如 `bytes 0-8388607/104857600` |
| 分片大小 | 客户端每次请求 8MB 的分片 |

---

## 完整调用流程

```
客户端启动
    │
    ├─ 读取本地 version.txt（不存在则视为首次更新）
    │
    ├─ GET /api/client/latest ──→ 获取最新版本号
    │
    ├─ 比对版本号
    │     ├─ 相同 → 无需更新，结束
    │     └─ 不同 → 继续更新流程
    │
    ├─ GET /api/client/updates?from=当前版本 ──→ 获取待更新版本列表
    │
    ├─ 合并多版本文件操作（客户端本地处理）
    │
    ├─ 执行删除操作（本地删除文件）
    │
    ├─ 并行下载所有 add/modify 文件
    │     ├─ 文件 ≤ 32MB → 直接下载
    │     └─ 文件 > 32MB → HEAD 获取大小 → 分片并行下载 → 拼接
    │
    └─ 写入最新版本号到 version.txt，更新完成
```

---

## 错误处理

建议后端对错误情况返回标准 JSON 格式：

```json
{
  "error": "错误描述",
  "code": 400
}
```

客户端当前仅根据 HTTP 状态码判断请求是否成功（200 即为成功），后续可扩展错误码处理。
