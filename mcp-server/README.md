# memoX 本地 Agent API / MCP adapter

手机端默认关闭，默认监听 `127.0.0.1:47823`；可在 **设置 → 数据与隐私 → 本地 Agent API → 监听地址** 改为 `0.0.0.0`，让同一局域网内的桌面 Agent 直接访问 `http://<手机IP>:47823`。无论回环还是局域网，都必须携带配对令牌。

完整端点、字段与安全边界见 [`docs/agent-api.md`](../docs/agent-api.md)。

## 1. 手机端

1. memoX → 设置 → 数据与隐私 → “本地 Agent API”，打开开关。
2. 弹窗会显示配对令牌；令牌保存在应用的加密首选项中。
3. 局域网直连：把“监听地址”改为 `0.0.0.0`，手机与电脑连同一 Wi-Fi，`MEMOX_API_URL` 填 `http://<手机IP>:47823`。
4. 或用 USB + ADB 端口转发（只监听回环地址时）：

```bash
adb forward tcp:47823 tcp:47823
```

修改监听地址会自动重启前台服务；地址非法或端口被占用时开关自动关闭。`0.0.0.0` 会暴露到局域网，公共 Wi-Fi 下不要开启。关闭开关或停止前台服务会立即关闭监听并清理已解密的附件会话缓存。

## 2. 桌面 MCP 配置

```json
{
  "mcpServers": {
    "memox": {
      "command": "node",
      "args": ["H:/Github/memox/mcp-server/memox-mcp.mjs"],
      "env": {
        "MEMOX_API_URL": "http://127.0.0.1:47823",
        "MEMOX_TOKEN": "<手机设置里显示的令牌>"
      }
    }
  }
}
```

## 3. 工具

| 工具 | 作用 |
|---|---|
| `memox_list_notes` | 按关键词、标签、创建时间范围、回收站分页列出笔记 |
| `memox_get_note` | 读取单篇；加密笔记需 `recovery_password` |
| `memox_create_note` | 新建普通笔记（`RICH_TEXT` / `MARKDOWN`） |
| `memox_update_note` | 按 `expected_version` 更新，过期返回 409 |
| `memox_trash_note` | 移入回收站；永久删除受保护笔记会被拒绝 |
| `memox_sync` | 触发已配置的 WebDAV / OneDrive 同步 |
| `memox_status` | 查询同步状态与笔记数量 |

写操作支持 `Idempotency-Key`（adapter 串行执行，重复请求不会重复写）。受保护笔记的正文只在提供恢复密码时于内存中短暂解密，不会被写入桌面文件或日志。
