# Agent API（供本地/局域网 AI Agent 读写笔记）

memoX 内置一个带版本的 HTTP API，供桌面 MCP adapter、脚本或其他终端上的 AI Agent 调用。默认关闭；开启后由前台服务维持，应用进程被系统回收时 API 随之停止。

> 当前实现位于 `app/src/main/java/cool/hin/memox/data/agent/AgentApiServer.kt`，桌面适配器位于 `mcp-server/memox-mcp.mjs`。

## 1. 监听地址与端口

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| 监听地址 | `127.0.0.1` | 仅本机（配合 `adb forward`） |
| 端口 | `47823` | 固定端口 |
| 监听地址（局域网） | `0.0.0.0` | 手机与 Agent 在同一 Wi-Fi 时，用手机局域网 IP 访问 `http://<手机IP>:47823` |

- 地址在 **设置 → 数据与隐私 → 本地 Agent API → 监听地址** 中修改，支持回环地址、`0.0.0.0` 或具体网卡 IP。
- 修改后自动重启前台服务；端口被占用或地址非法时，开关会自动关闭并在日志中记录 `AgentApiServer`。
- 绑定到 `0.0.0.0` 会把 API 暴露给同一局域网。**必须**同时依赖 Bearer Token 鉴权；不要在不可信网络（公共 Wi-Fi）开启。
- 明文 HTTP 仅用于可信局域网；公网访问请自行置于 TLS 隧道/VPN 之后，不要直接端口映射。

## 2. 鉴权

每个请求都必须带配对令牌：

```http
Authorization: Bearer <token>
```

- 令牌在首次开启 API 时生成，保存在加密偏好 `agentApiToken` 中；设置页弹窗会显示。
- 令牌错误返回 `401`，响应体为 `{"error":"unauthorized"}`。
- 只读操作不需要额外头；读取加密笔记正文时需额外传 `X-Memox-Recovery: <归档密码>`。

## 3. 端点

所有响应均为 JSON。成功响应包裹为 `{"data": ...}`，错误为 `{"error": "..."}`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/v1/status` | 版本、同步状态、笔记总数 |
| `POST` | `/v1/sync` | 立即触发一次 WebDAV/OneDrive 同步 |
| `GET` | `/v1/notes` | 列出笔记；支持 `query`、`label`、`from`、`to`、`offset`、`limit`（1–200，默认 50）、`trash=true` |
| `GET` | `/v1/notes/{uuid}` | 读取单条笔记，含正文 |
| `POST` | `/v1/notes` | 新建笔记（`title`、`body`、`labels`、`format`=`RICH_TEXT`/`MARKDOWN`） |
| `PATCH` | `/v1/notes/{uuid}` | 更新标题/正文/标签；可带 `expectedVersion` 做乐观锁，冲突返回 `409` |
| `DELETE` | `/v1/notes/{uuid}` | 移入回收站；`?permanent=true` 直接删除并写墓碑 |

### 3.1 示例

```bash
# 状态
curl -H "Authorization: Bearer $MEMOX_TOKEN" http://127.0.0.1:47823/v1/status

# 新建 Markdown 笔记
curl -X POST http://127.0.0.1:47823/v1/notes \
  -H "Authorization: Bearer $MEMOX_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title":"Agent 写入","body":"# 标题\n来自其他终端","format":"MARKDOWN","labels":["agent"]}'

# 搜索并读取
curl -H "Authorization: Bearer $MEMOX_TOKEN" \
  "http://127.0.0.1:47823/v1/notes?query=Agent&limit=20"
```

## 4. 桌面 MCP adapter

```json
{
  "mcpServers": {
    "memox": {
      "command": "node",
      "args": ["/absolute/path/to/mcp-server/memox-mcp.mjs"],
      "env": {
        "MEMOX_API_URL": "http://192.168.1.20:47823",
        "MEMOX_TOKEN": "<配对令牌>"
      }
    }
  }
}
```

- `MEMOX_API_URL` 默认 `http://127.0.0.1:47823`；LAN 场景改成手机 IP。
- 走 ADB 时先执行 `adb forward tcp:47823 tcp:47823`，URL 保持回环地址。

## 5. 安全边界

- 受保护（加密）笔记：列表只返回元数据；正文需 `X-Memox-Recovery`；`PATCH` 与永久删除会被拒绝（`409`），必须在设备上认证后操作。
- 请求体上限 1 MB；请求超时 15 秒。
- 写操作支持 `Idempotency-Key`，10 分钟内重复请求返回首次结果。
- API 不提供附件上传、文件夹/标签管理、账号登录；这些仍只在应用内完成。
- 关闭开关或停止前台服务后端口立即释放。

## 6. 已知限制 / 后续

- [ ] 端口可配置（当前固定 `47823`）。
- [ ] TLS 与访问日志（当前依赖可信局域网 + Token）。
- [ ] `PATCH` 目前只覆盖标题/正文/标签，颜色、置顶、文件夹等字段暂不支持。
- [ ] MCP adapter 目前是薄 HTTP 封装，工具描述可按 Agent 场景继续细化。
