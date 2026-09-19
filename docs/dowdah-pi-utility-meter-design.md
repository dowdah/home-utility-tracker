# 家庭水电抄表与同步系统：实地核验后的实施方案

> 调查时间：2026-09-19（只读 SSH）。本文件把已核验的机器事实、产品边界、同步协议和部署方案分开写；未核验的公网/DNS 结论不会当作既成事实。

## 结论

项目应维持为一个**本地优先的个人抄表系统**，而不是通用能源平台：Android 负责所有录入、历史和统计；树莓派是多设备同步、导出和备份的权威端。第一版只服务电、冷水、热水三类累计表读数。

这台 Pi 已具备部署条件，但不是一台空闲专机。因此建议以 `uv` 管理一个原生 Python/FastAPI 服务，由 systemd 托管单个 Uvicorn worker；API 只发布到本机回环地址，并通过 ECS 上的 Nginx + 独立反向 SSH 隧道提供 HTTPS。该路径已经按现有摄像头隧道和 ECS 的实际能力核验为**架构可行**，但 `.200` 尚未获授新的受限隧道密钥；它不是立即可启动的部署项。这既避免新的公网监听端口，也不改动现有 Nextcloud、aria2、Samba、DDNSTO、SakuraFrp 或 OilWell edge-agent 的业务配置。

## 已核验的主机事实

| 项目 | 观察结果 | 对设计的影响 |
|---|---|---|
| 设备 | Raspberry Pi 4 Model B Rev 1.5，4 核 Cortex-A72，arm64 | 选择多架构/arm64 镜像；FastAPI + SQLite 负载远低于现有能力。 |
| 系统 | Debian 13.4，内核 `6.12.75+rpt-rpi-v8`，Python 3.13.5，Docker 29.4.0 | 使用 Python 3.13、`uv` 和 systemd 原生部署；不新增容器或镜像验证负担。 |
| 内存 | 约 1.8 GiB RAM，约 1.2 GiB 可用；zram swap 1.8 GiB | 不上 PostgreSQL、Elasticsearch、监控全家桶或常驻图表服务。单个 Uvicorn worker 足够。 |
| 磁盘 | `/dev/mmcblk0p2` 为 470 GiB ext4；已用 413 GiB（92%），余约 39 GiB；`/srv/sharedfiles` 占约 381 GiB | 小型数据库本身没有容量问题，但下载数据使整体余量偏低；必须有备份保留上限和低空间告警。 |
| 网络 | `eth0` 为 `192.168.50.200/24`，有公网 IPv6；时间同步正常 | 局域网访问存在，但公网可达性和 DNS/TLS 映射尚未在本次调查中验证。 |
| 既有服务 | Docker（Nextcloud、aria2/AriaNg、Portainer、edge-agent）、Samba、Filebrowser、Cloudflared、DDNSTO、SakuraFrp | 新项目不得复用或改写既有 Compose 网络、共享目录权限或隧道配置。 |
| 已占端口 | 22、139/445、6880、6888、9000、9443、23333、9090；回环 6800、9999、20241 | 本次快照中 `127.0.0.1:8088` 空闲；部署前仍应重新检查。 |
| SSH | 仅公钥认证，`AllowTcpForwarding yes`，`GatewayPorts no` | `.200` 可作为反向 SSH 客户端；必须使用独立私钥和独立 systemd unit，不能借用摄像头 Pi 的 key 或 service。 |
| Cloudflare | `cloudflared.service` 正在运行，版本 2026.3.0；存在配置与 tunnel 凭据文件 | 保留为后备部署路径；本方案不再把它作为 V1 主路径，也不读取或改写其凭据。 |
| Samba | `sharedfiles` 导出 `/srv/sharedfiles`，可写；另有用户家目录共享 | SMB 只放 CSV 和已生成的备份副本；绝不直接开放正在使用的 SQLite 数据库。 |
| SQLite | Python 运行时为 SQLite 3.46.1 | 先使用默认 rollback journal / 单进程写入；不要未核实安全补丁就启用 WAL。SQLite 官方在 2026-03 披露的 WAL-reset 问题覆盖若干旧版本，部署前应核实 Debian 是否已回补，或升级到官方修复版本。 |

本机的 nftables 快照主要是 Docker 的转发规则，未见限制主机 INPUT 的通用规则。因此把应用端口绑定到 `0.0.0.0` 会扩大暴露面；这也是选择 `127.0.0.1` 绑定的直接原因。

### 反向 SSH / ECS 补充核验（2026-09-19）

`.250` 是摄像头生产 Pi，而非本项目的部署主机。其 `camera-reverse-tunnel.service` 是已验证过的模式：以专用 `camera-tunnel` 身份向 ECS 的 `127.0.0.1:18080` 和 `127.0.0.1:18888` 建立 `ssh -R`；该 unit 目前 inactive，历史失败包括 ECS 残留端口占用和 SSH 超时。它不能承载本项目，也不应被启动、改写或复用。

阿里云客户端核验的 ECS 为运行中的 `dowdah-ecs`（上海 B、Ubuntu 24.04 x86_64、2 vCPU/2 GiB、EIP `101.133.108.172`、100 Mbps 按流量）；到期日为 2027-04-14。云助手的只读命令确认：ECS `sshd` 有 `AllowTcpForwarding=yes`、`GatewayPorts=no`、`PermitOpen=any`，且当前 `18088` 未监听；Nginx active，并在 `[::]:443` 监听。`GatewayPorts=no` 正是此设计需要的安全默认值：反向端口只能由 ECS 本机进程访问（预期为 Nginx），不能被公网直接访问。

`.200` 上的 `127.0.0.1:8088` 当前空闲；它有一个名为 `ecs-camera-tunnel` 的私钥，但其公钥指纹为 `SHA256:qlYjmVJCbJYUUaUGMbp3WZ6vUfEsMCCydbWNDJRo/UA`，与 `.250` 已用于摄像头的 `SHA256:UsepbbBXb/e5CU0agMs7o2LLw+/vJsFF7Pf3lpPY26A` 不同。使用 `.200` 现有 key 对 `camera-tunnel@ECS` 认证已被拒绝。这是正确的隔离结果，不是网络或端口故障；部署前仍需在 ECS 上显式授予**新的**抄表隧道公钥。

## 范围与非目标

V1 包含：三类表、手动累计读数、本地编辑/软删除、价格历史、月/年统计、简单趋势图、多个后端地址、手动和后台同步、服务器 SQLite、API token、CSV 导出、健康和同步状态。

V1 明确不包含：OCR、账单解析、复杂阶梯价格、预测/异常检测、Home Assistant、用户注册、多租户、跨服务器自动合并、让 SMB 成为同步协议。

## Android 架构

采用 Kotlin、Jetpack Compose、Navigation Compose、Room、Coroutines/Flow、Hilt、Retrofit/OkHttp、kotlinx.serialization、WorkManager；图表库可在实现统计页时再选。

UI 只读取 Room 的 `Flow`，从不直接读取网络响应。任何新增、修改或删除都必须在同一 Room 事务里先更新本地实体并写入 outbox；网络同步只会将服务器确认或远端变化合并回 Room。这样断网数天也可完整录入和查看历史，符合 Android 官方 offline-first 的本地数据源为唯一上层读取源的模式。[Android offline-first 指南](https://developer.android.com/topic/architecture/data-layer/offline-first)

建议四个一级页面：

1. **首页**：三个当前表读数、距上次记录时间、本月归集消耗/费用、同步摘要。
2. **记录**：新增、修改、软删除和按表筛选的历史。
3. **统计**：月/年消耗与费用、趋势图，并显示统计归集规则。
4. **设置**：价格历史、服务器地址、当前端点、token、同步诊断和 CSV 导出。

### 本地数据表

`Meter` 不做三个独立表。服务器初始化三个活跃 meter：`ELECTRICITY`（kWh）、`COLD_WATER`（t）、`HOT_WATER`（t）。`meter_id` 是 UUID；保留 `meter_type` 是为了展示和筛选。以后换表时新建同类型、递增 `generation` 的 meter，而不是让累计数回退污染同一条序列。

```text
meters: id, meter_type, generation, unit, active, created_revision, deleted
readings: id, meter_id, value_decimal, recorded_at, note, deleted,
          created_at, updated_at, server_revision, updated_by_device_id
tariffs: id, meter_id, price_decimal, currency, effective_from,
         deleted, server_revision
outbox: operation_id, entity_type, entity_id, mutation_kind, base_revision,
        payload_json, created_at, attempt_count, last_error
sync_state: backend_instance_id, cursor_revision, last_success_at, last_error
```

金额和读数均使用定点 `Decimal`：读数/价格在 API 中以字符串传输，SQLite 中以整数最小单位或规范十进制文本保存；禁止 `Float/Double` 参与费用计算。`recorded_at` 用 UTC RFC 3339；客户端显示时转换本地时区。`created_at`/`updated_at` 仅供审计和 UI 排序，**不用于冲突胜负判断**。

录入时应用提示“低于上一条读数”，但允许用户确认继续。普通编辑不应破坏历史；换表则用新 generation。服务器只做数值、单位、时间和 meter 归属校验，不把“单调”作为不可绕过的硬约束。

### 低频水表与费用归集

原始消费永远是同一个 meter 内相邻有效累计读数的差：`current.value - previous.value`。负值标为“需核对”，不静默计入消费。

V1 采用明确且可解释的 **end-of-interval** 规则：一段读数差的全部消耗和费用归到较晚读数所在的自然月，并采用该时点生效的 tariff。统计 UI 必须显示“按本期抄表日归集”，不能暗示这是每日真实消耗。

这是对低频水表最诚实的 V1：例如 8 月 1 日到 9 月 18 日才抄一次，系统知道总用水，**不知道** 8 月和 9 月各用了多少。未来若确有需要，可加入标为“估算”的按时间线性分摊；它不应覆盖原始读数或冒充真实日用量。电价在区间中变更时同样遵从 V1 归集规则；更精确的账单分段属于 V2。

## 同步协议：以服务器 revision 为准

不要用手机 `updated_at` 做 last-write-wins。每次服务器接受一项变更，在单个 SQLite 事务中写入实体、追加 change log，并分配全局单调递增 `server_revision`。客户端只推进已完整落盘的游标。

### 请求与响应

单一接口较适合个人项目并能消除 push/pull 调用间的竞态：

```text
POST /api/v1/sync
Authorization: Bearer <per-device token>

{
  "backend_instance_id": "uuid obtained from /meta",
  "cursor_revision": 1042,
  "device_id": "installation UUID",
  "mutations": [
    {
      "operation_id": "UUID stable across retries",
      "entity_type": "reading",
      "entity_id": "UUID",
      "kind": "upsert | tombstone",
      "base_revision": 1038,
      "payload": { "...": "..." }
    }
  ]
}
```

响应携带每个 `operation_id` 的 `accepted` / `duplicate` / `conflict` 结果、冲突时的权威实体，以及 `changes`、`next_cursor_revision`、`has_more`。每页响应在服务器端先取得 high-water mark；客户端把 ack、远端变化和新 cursor 在**一笔 Room 事务**中落盘，随后才请求下一页。网络超时重试同一个 `operation_id`，服务器从 operation log 返回原结果，因此不会重复写入。

服务端接收规则如下：

| 情形 | 行为 |
|---|---|
| 新 UUID | 接受，分配 revision。 |
| `base_revision` 等于实体当前 revision | 接受，分配新的 revision。 |
| 同一 `operation_id` 重试 | 幂等返回第一次结果。 |
| `base_revision` 落后 | 返回 `409 conflict` 和当前权威实体；不静默覆盖。 |
| 删除 | 写 tombstone，照常递增 revision、参与 pull。 |

冲突在此项目中很少见，但不能靠时钟猜胜负：客户端保留本地草稿，显示“另一设备已修改”，用户选择保留服务器版本、覆盖服务器（以新的 `base_revision` 重试）或手工合并。初版可先只提供前两项。Tombstone V1 不做自动物理清理；数据量极小，正确同步比节省几 KB 更重要。未来清理前须确认每个仍活跃设备的游标已越过 tombstone，并保留一次可恢复备份。

同步触发：点击“立即同步”、应用启动、录入后和 `NetworkType.CONNECTED` 的唯一 WorkManager 任务。使用 `enqueueUniqueWork(..., KEEP, ...)` 防止同一安装并发跑两个 sync；对 5xx/网络错误 `Result.retry()` 走指数退避，对 401/409 不盲目重试。WorkManager 适合跨进程退出和重启仍需可靠完成的同步，但不是“立刻执行”的保证。[Android WorkManager 文档](https://developer.android.com/develop/background-work/background-tasks/persistent)

## 后端与数据安全

### FastAPI 服务

建立独立的原生 Python 项目，例如 `/home/utility-sync/utility-sync/`：使用 `uv sync --frozen` 安装锁定依赖，并由专用 Unix 服务帐号的 systemd unit 执行 `uv run --frozen uvicorn utility_sync.api:app --host 127.0.0.1 --port 8088 --workers 1`。先使用一个 Uvicorn worker；FastAPI 只提供 `/healthz`、`/meta`、`/sync`、`/exports/*.csv` 和本地 CLI 管理命令。数据库访问使用一个明确的写事务队列或短事务，避免长读事务。

持久数据建议放在新建的非 SMB 可写目录，例如 `/srv/utility-meter/`，容器以专用非 root 用户写入：

```text
/srv/utility-meter/
  data/utility.sqlite3
  exports/                 # 原子生成的 CSV，供 SMB 只读查看
  backups/                 # SQLite online backup 产物与 SHA-256
  config/                  # 仅根/服务帐号可读的 token hash 与环境配置
```

不要把运行中的 `utility.sqlite3`、`-wal` 或 `-shm` 文件当作普通 SMB 文件来复制。若使用 WAL，这些同目录伴随文件也是数据库状态的一部分。[SQLite WAL 文档](https://www.sqlite.org/wal.html) 当前运行时 SQLite 为 3.46.1；在确认 Debian 已回补 WAL-reset 安全修复前，部署默认使用 rollback journal。后续若升级到官方修复版本并启用 WAL，备份仍必须走 SQLite online backup API/`sqlite3 .backup`，不能直接拷贝主数据库文件。

认证使用每安装一个 token，而不是所有手机共用一个长期秘密。服务器只存 token 的加盐哈希，可单独吊销；token 有最小 `sync:read/write` 权限。Android 端用 Android Keystore 保护的密钥加密 token（例如 DataStore Tink 的 Keystore master key），不写入 Room、日志、截图、Git 或 CSV。Android 官方也建议以 Keystore 和强加密保护 API 密钥。[Android 安全清单](https://developer.android.com/privacy-and-security/security-tips)

### HTTPS 与外网访问：推荐路径

```text
Android
  └─ HTTPS: https://meter.<your-domain>/api/v1
       └─ ECS Nginx :443（现有 TLS 终止点）
            └─ proxy_pass http://127.0.0.1:18088
                 └─ 独立 SSH -R，仅监听 ECS 回环地址
  └─ .200 的 127.0.0.1:8088（systemd 托管的原生 Uvicorn，仅回环监听）
                           └─ FastAPI + SQLite
```

部署必须新建 ECS 帐户 `utility-tunnel` 和一把**只属于 `.200` 的新 ED25519 key**；不要把 `.250` 的 private key 复制过去，也不要为方便起见把 `.200` 的既有、未授权 key 加入摄像头帐户。ECS 的该帐户应只允许 public-key 认证、禁止密码/TTY/X11/agent forwarding，并限制为 remote forwarding、`GatewayPorts no` 和 `PermitListen 127.0.0.1:18088`。为此应在 `sshd_config` 的 `Match User utility-tunnel` 中配置这些服务端限制；授权 key 也应使用 `restrict,port-forwarding,permitlisten="127.0.0.1:18088"` 等最小权限选项。配置后必须用 `sshd -t` 验证，保留现有 SSH 会话，且只 reload `sshd`，不能靠重启 ECS 验证。

`.200` 端建立全新的 `utility-meter-reverse-tunnel.service`，其唯一职责为：

```text
/usr/bin/ssh -N \
  -i /home/dowdah/.ssh/utility-meter-tunnel \
  -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes \
  -o UserKnownHostsFile=/home/dowdah/.ssh/utility-meter-known_hosts \
  -o ExitOnForwardFailure=yes \
  -o ServerAliveInterval=30 -o ServerAliveCountMax=3 \
  -R 127.0.0.1:18088:127.0.0.1:8088 \
  utility-tunnel@101.133.108.172
```

使用 `Restart=always` 与 `RestartSec=5`，但与 `camera-reverse-tunnel.service` 完全分离。`ExitOnForwardFailure` 是必需的：若 ECS 上端口被占用，unit 必须失败并被监控，而不是显示“已连接”却未建立转发。反向端口绝不能写成 `0.0.0.0:18088`，也不应在阿里云安全组中开放 18088；安全组只需保持既有的 HTTPS 443（以及受限管理 SSH）规则。

ECS Nginx 应新增一个独立 hostname 的 TLS server block，明确 `server_name`、现有证书续期方式、`proxy_pass http://127.0.0.1:18088`、Host/Forwarded headers、合理的 connect/read timeout 和小的请求体上限。先通过 `nginx -t`，再 reload Nginx。此调查已确认 Nginx active 和 443 监听，**尚未确认**哪个域名可用、该域名的 DNS/证书续期来源，或阿里云安全组的现有 443 来源策略；这些是修改 Nginx/DNS 前必须检查的前置项。

Cloudflare Tunnel 可保留为今后的应急路径，但 V1 不应同时运行两条对同一 API 的公网入口，避免 endpoint/证书/日志诊断变得含混。

后端地址的产品模型略作修正：每个地址首次连接 `/meta` 都取得不可变 `backend_instance_id`。不同 ID 是不同数据源，游标绝不共用；若“局域网”和“公网”确实是同一服务，界面可把它们列为同一实例的端点候选并共享游标。V1 仍可让用户选择当前端点，但切换到不同实例必须显示明确的“切换数据源”确认，而不是自动合并。

### 备份、导出与空间控制

* 每天运行一次 SQLite online backup，备份完成后校验可打开、记录 SHA-256，再原子移动到备份目录。
* 导出 CSV 也从一致性只读事务生成到临时文件后原子改名；SMB 只发布 `exports/` 和已完成的备份副本，不发布 `data/`。
* 初始保留策略：14 个日备份、12 个每月备份；删除旧备份前重新列出精确文件名并记录操作。不得使用宽泛 glob 清理。
* 低于 20 GiB 可用空间时备份任务报错并生成可见告警；低于 10 GiB 时同步服务只允许读取/导出，并提示先处理 `sharedfiles` 的大文件。实际清理需另行授权，绝不由应用自行删除用户下载内容。
* 每个备份至少进行一次恢复演练：在临时目录打开副本、运行完整性检查，并对比读数/修订数量；健康检查 200 不是恢复能力的证明。

## API 最小面与运维边界

| 路径 | 目的 | 认证 |
|---|---|---|
| `GET /healthz` | 进程、数据库可打开、剩余空间摘要；不返回秘密 | 仅供 ECS Nginx 探测，或同样 token 化 |
| `GET /api/v1/meta` | `backend_instance_id`、API/schema 版本、功能标志 | Bearer token |
| `POST /api/v1/sync` | 原子 mutation + 增量 pull | Bearer token |
| `GET /api/v1/exports/readings.csv` | 用户导出 | Bearer token |

服务日志只记录 request ID、状态、耗时、revision 范围和错误类别；不得记录 Authorization 头、读数备注中的敏感信息、token 或完整请求体。`/docs` 在公网部署中默认关闭或同样认证。CORS 只为 Android 原生客户端时并不需要放宽为 `*`。

## 实施顺序与验收门槛

1. **确认部署前置条件**：重新核验 `.200` 的端口 8088、剩余空间和 SQLite/Debian 安全补丁；在 ECS 审阅现有 Nginx vhost、证书续期、DNS 与安全组 443 规则。创建并授权独立 `utility-tunnel` key，但不触碰 `.250` 摄像头 tunnel。
2. **后端最小闭环**：schema migration（建议 Alembic）、token hash、`/meta`、`/sync`、单机 SQLite 事务与 change log。为同一 `operation_id` 重试、版本冲突、tombstone、分页写集成测试。
3. **Android 本地闭环**：Room migration、读数录入/编辑/删除、价格历史、end-of-interval 统计，确保断网时所有页面仍可读写。
4. **同步闭环**：两台设备离线各自新增后同步、同条记录并发编辑产生可见 conflict、删除离线再同步、网络超时后幂等重试、应用重启后 WorkManager 恢复。
5. **受限部署**：以 `uv sync --frozen` 安装锁定依赖，启用专用 systemd service 并发布 API 到 `.200` 的 `127.0.0.1:8088`，先以 Pi 本机 curl 验证；建立 ECS 回环 `127.0.0.1:18088` 的独立反向隧道，确认 ECS 本机 curl 后再添加 Nginx TLS vhost。以 systemd daily timer 运行 SQLite online backup。依次验证 TLS、401、有效 token、手机实际同步。任何一层失败都不称为上线完成。
6. **数据保障验收**：CSV 打开正确、SQLite 备份校验和恢复演练通过、备份保留按精确文件清单执行、磁盘告警可见。

## 尚待用户决定的产品项

* 费用显示货币及小数位；默认可按 CNY、金额保留两位。
* V1 是否接受“按较晚抄表日归集”的月度水费显示；若不接受，需要明确采用“线性估算”还是等待账单周期的人工结算。
* ECS Nginx 已管理的域名中，哪一个可为此 API 新增独立 hostname，以及该 hostname 对应的证书续期机制；本方案不假设或修改现有 DNS/证书。
* 是否要让局域网也直连。安全优先的 V1 只走同一个 HTTPS hostname；若要求纯 LAN 直连，需要另外布置可信本地 TLS/证书，而不是明文 HTTP 携带 Bearer token。
