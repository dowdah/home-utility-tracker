# V1.2 本地验收与邮件配置交接

记录日期：2026-10-02。Android `versionName=1.2`、`versionCode=3`；同步协议仍为 2。

**状态：四阶段实现与本地验收完成，按约定暂停等待私有 SMTP 配置。真实邮件、生产部署和生产验收尚未执行；分支尚未 push，也未发起 PR。**

## Git 与实施范围

开始时工作区干净，核对本地/远端 `main` 均为 `1ac32577aae26f8a40af2cc29eeeaa552143ac53`，远端没有其他分支、没有打开的 PR；同步后创建 `codex/feature/data-protection-sync-alerts`。

| 提交 | 内容 |
| --- | --- |
| `b526784` | Android 数据库备份白名单、WAL 检查点、恢复清理与身份迁移 |
| `03e13b0` | 本地/服务器 CSV 导出、前台刷新、15 分钟周期同步 |
| `1988227` | 独立于主服务的 SMTP 监控、持久化通知状态、私有配置与部署保护 |
| `3ba94f7` | 校验持久化通知状态字段，避免异常状态文件泄露诊断内容 |
| `ece6fb2` | KSP2、AGP 内置 Kotlin/新 DSL、lint 与隔离验收脚本整理 |

HTTP/HTTPS、端点规范化和明文 HTTP 策略保持原有行为。服务器 CSV 路由和业务列保持兼容，`sync_status` 只属于新增的本地导出格式。Room schema 与基线相同，通知状态没有引入 Alembic 业务表迁移。

## 已通过的本地检查

| 检查 | 实际结果与边界 |
| --- | --- |
| 后端 | 36 项 Pytest 通过；Ruff 检查、格式检查通过。仍有框架依赖的弃用提示。 |
| Android JVM | 36 项通过，0 失败、0 错误、0 跳过。 |
| Android 构建 | Debug APK、Acceptance APK 和测试 APK 构建通过。Gradle 9.6.0、AGP 9.4.1、Kotlin 2.2.21、KSP 2.3.6、Hilt 2.59.2。Gradle JVM 25、Java/Kotlin 编译目标 24；实际解析的 Kotlin Gradle 插件为 2.2.21。 |
| Android lint | 0 错误、34 警告；主要为依赖更新和 API 使用建议。旧 DSL/KAPT 兼容开关已删除，相关警告已移除；不宣称所有依赖/弃用警告消失。 |
| Pixel_6_Pro | `emulator-5554`，每个测试类单独调用并核验实际执行数量：24 项回归通过，没有跳过。 |
| Pixel_10_Pro | `emulator-5556`，24 项回归通过，没有跳过。 |
| 真实版本升级 | 使用基线 `1ac3257` 构建的 1.1 隔离 APK，在两台 AVD 实际升级到 1.2，验证已落盘的账本、token 和安装身份保留。 |
| Android 本地备份传输 | 两台 AVD 分别验证加密标志、设备迁移标志和不加密拒绝，共三种模式。实际备份、清除隔离包数据、恢复，核对 WAL 最新数据、队列顺序/操作 ID、原子组、冲突、后端身份和游标。恢复清除 token/旧身份及缓存健康状态；新身份重新生成。 |
| 离线导出 | 无端点/token 的本地快照导出三类业务记录，覆盖待同步、冲突优先、软删除、多行/引号/逗号备注、小数精度与快照冻结。双 AVD 实际 SAF 保存/取消、Activity 重建、文件无法写入时失败状态与忙碌状态解除通过。 |
| 界面 | 双 AVD 导出页中文/横屏/深色和英文/竖屏/浅色组合通过；原有表单和图表回归通过。没有将这些组合声称为所有页面、所有组合的人工视觉验收。 |
| 双 AVD 同步 | 本机隔离 FastAPI 后端，22 个步骤通过，覆盖新增、编辑、删除、充值原子组、两种冲突处理、重启后待同步队列及服务器导出。 |
| 周期/前台调度 | 确定性调度测试验证唯一任务、UPDATE、网络约束和 60 秒前台合并。真实周期执行让后台设备收到另一台新增记录，实测等待 `901630 ms`，未用手动同步代替。Android 后续仍可能延迟调度。 |
| SMTP | 模拟和本机 TLS SMTP 接收端验证 STARTTLS/隐式 TLS、告警、去重、六小时提醒、两次恢复、认证失败、超时、退避与状态重载。数据库不可读或主服务停止时仍生成通知。没有联系真实邮箱服务。 |
| 数据保护脚本 | 校验覆盖充值、原子组、操作请求指纹和原有数据列，测试能发现充值/请求内容变化。此项是本地脚本验收，生产备份恢复演练仍待执行。 |

### Android 回归的实际执行数

每台设备：DatabaseMigration 1、LedgerRepository 8、BackupRecovery 2、OfflineExport 1、WorkScheduler 1、RechargeForm 2、StatisticsScreenCharts 1、DailyRemainingUi 3、IntervalAverageTrendUi 4、ExportUiAcceptance 1，共 24。

最后补充的目标文件写入失败断言并入 OfflineExport 用例，并在两台 AVD 分别重跑该用例通过。构建时该新增断言的参数顺序错误已修正，随后测试 APK 构建通过。

初次升级夹具的旧版 token 使用 `SharedPreferences.apply()`，替换 APK 前没有确认落盘，导致升级检查失败；夹具增加读回断言和同步落盘后，真实 1.1→1.2 升级在两台设备通过。没有将夹具修正记作生产凭据问题修复。

### 本地证据与重跑入口

- 最终 JVM XML：`utility-tracker/app/build/test-results/testAcceptanceUnitTest/`。
- Lint：`utility-tracker/app/build/reports/lint-results-debug.html` / `.xml`。
- 本地临时证据目录：`utility-v12-regression-7vgkyh7k`、`utility-v12-regression-lychde7v`、`utility-ledger-acceptance-dvvjlceg`（位于本机系统临时目录，可能被系统清理）。本文件保存脱敏结论。
- 构建日志：`/private/tmp/utility-v12-final-build.log`；补充导出失败日志：`/private/tmp/utility-v12-export-failure-emulator-5554.txt`、`/private/tmp/utility-v12-export-failure-emulator-5556.txt`。

```sh
# Android 项目内
./gradlew :app:testAcceptanceUnitTest :app:assembleDebug :app:assembleAcceptanceAndroidTest :app:lintDebug

# 仓库根目录；显式指定 emulator serial，仅使用隔离验收包
python3 tools/android_regression.py --serial emulator-5554
python3 tools/android_regression.py --serial emulator-5556
python3 tools/backup_acceptance.py --serial emulator-5554
python3 tools/backup_acceptance.py --serial emulator-5556
# 真实升级需额外传入同签名、同隔离命名空间且 versionCode 更低的基线 APK：--previous-apk PATH
utility-sync/.venv/bin/python tools/live_acceptance.py --mode local --serial-a emulator-5554 --serial-b emulator-5556 --include-periodic
```

备份脚本在结束后恢复原传输、启用状态和本地传输参数。最终回归/备份/同步验收包已清理，临时 token 已撤销；原有应用和此前其他验收包保留。测试文件只删除本次生成的 UUID 文件。真实 Google 云备份、用户的其他设备未作验收。

## 现在需要填写的私有配置

本机文件：`utility-sync/.local-config/notifications.env`。目录权限 `0700`、文件权限 `0600`，已被 Git 忽略。模板当前禁用发送；不要把填写后的文件或密码贴进聊天、提交或命令参数。

填写方式：每行 `KEY=value`，值按字面读取，不加引号、不作变量展开、不加行尾注释。

| 字段 | 填写内容 |
| --- | --- |
| `SMTP_ENABLED` | 配置完成后改为 `true` |
| `SMTP_HOST` | 邮箱服务的 SMTP 主机 |
| `SMTP_PORT` | STARTTLS 通常为 `587`；隐式 TLS 通常为 `465`，按服务商要求填写 |
| `SMTP_SECURITY` | `starttls` 或 `ssl`，始终校验证书 |
| `SMTP_USERNAME` / `SMTP_PASSWORD` | 服务商认证用户名及密码/应用专用密码；无认证时两者一起留空 |
| `SMTP_FROM` | 发件人地址 |
| `SMTP_TO` | 收件人地址，多个地址用逗号分隔 |

填写后通知继续。恢复执行时先运行 `notify-check`（不发邮件），再发送明确标注的验收邮件。**SMTP 接受投递与收件人实际收到是两项证据，后者由用户确认。**

监控只运行在树莓派本机，每五分钟检查；完全断电或完全失联时不能即时发邮件。没有增加 ECS 外部探测。邮件只含服务标识、异常代码、时间及必要诊断。SMTP 故障不阻止账本同步；SMTP 已接受但通知状态尚未落盘时崩溃，可能重复通知。

## 配置完成后的剩余验收与交付

1. 重新核对分支/工作区、生产目标、运行源码和当前数据；校验私有 SMTP 配置并执行真实验收邮件，等待用户确认收到。
2. 在线 SQLite 备份、校验和、完整性与副本恢复演练；保存旧源码。部署前后核对原有读数、充值、费率、token、后端身份、操作历史/请求指纹和原子组，避免覆盖现有账本。
3. 用已提交 revision 部署后端；SMTP 配置经 SSH 标准输入安装至生产私有配置，仅监控/显式配置 CLI 读取。检查主服务、隧道、定时器及脱敏 `/api/v1/status`。
4. 双 AVD 以临时 token 和标记测试记录进行生产验收；结束后只软删除本次测试记录、撤销临时 token。后续已有新写入时，采用保留当前账本的修复方式。
5. 完成必要回归和真实邮件/生产验收记录，阶段提交最终脱敏报告；全部必要验收通过后 push，等待 GitHub CI 通过。
6. 创建目标为 `main` 的正常 PR，列明部署 revision、数据保护和验收证据，并附加到当前聊天；保持打开，等待用户安排 squash merge。

真实邮件、生产验收、GitHub CI、push 和 PR 均保持待完成，不能依据本次本地结果记为通过。历史 [V1.1 验收报告](RELEASE-ACCEPTANCE.md) 保留为历史记录。
