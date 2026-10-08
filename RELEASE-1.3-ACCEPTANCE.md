# V1.3 余额预测与提醒验收

日期：2026-10-03（Asia/Shanghai）。Android 1.3 / versionCode 4，Room schema 3；同步协议仍为 2，后端业务 schema 仍为 `0002_recharges`。

## 交付内容

- 首页保留实际读数，另显当前余量和可用天数估算。完整区间按经过时间加权，考虑已记录充值、本地未同步数据、冲突和异常区间。电表窗口约30天、最小24小时，7天提示陈旧、30天停止预测；水表窗口约180天、最小7天，30天提示陈旧、90天停止预测。实际覆盖时长可见，充值不重置抄表年龄。
- 本机每日提醒默认15:00、7天阈值，各表可选余量阈值；每天最多一条汇总通知，更新静默，手动消除后当日不重发。独立无网络约束的小时任务、所选时间的延迟任务及数据/前台变化触发检查。手机通知需用户主动开启并授予权限；Android可能延迟执行。
- 提醒阈值和时间随Room账本备份，通知启用/去重不备份。恢复后需重新启用通知，不跨设备同步设置。
- 冲突页优先显示服务器已删除状态；取消过时邮件事件时清理相关发送错误，保留其他待发送事件和真实投递时间。

## 验收结果

| 检查 | 结果与边界 |
| --- | --- |
| 后端 | 41项Pytest、Ruff检查和格式检查通过；2项既有框架依赖弃用提示。SMTP使用本机TLS夹具，没有新增真实测试邮件。 |
| Android单元/构建 | 52项单元测试，0失败/错误/跳过；Debug、Acceptance、测试APK构建通过。 |
| Android lint | 0错误、34项警告，和基线警告数相同。 |
| 双AVD回归 | emulator-5554 / Pixel_6_Pro、emulator-5556 / Pixel_10_Pro各30项通过，逐类核对实际执行数量，无跳过。涵盖账本、迁移、备份、导出、表单、图表及新增预测/提醒/冲突显示。 |
| 迁移与真实升级 | 1→2→3和2→3迁移通过；两台AVD均以`be075e1`构建的1.2同签名隔离包实际升级至1.3，核对账本、token、安装身份、队列/组、冲突与后端身份。 |
| 实际备份传输 | 两台AVD均通过加密、本地设备迁移和拒绝未加密三种模式；设置保留、设备提醒状态清空。仅Android本地传输，不是真实Google云上传。 |
| 本地双设备同步 | 临时FastAPI后端19个步骤通过：充值/读数、两种冲突处理、水表、删除传播、队列/进程恢复、服务器导出及清理。临时token和隔离包已清理。未重复V1.2的15分钟真实同步观察，本次没有更改该周期。 |
| 真实离线提醒 | 两台AVD先验证权限拒绝，再在飞行模式且Wi-Fi关闭、应用后台时等待实际WorkManager通知，分别观察204.5秒、163.5秒。静默更新、消除后不重发及进程重启去重通过。使用临近测试时间；默认仍为15:00。 |
| UI | 冲突及预测中英文、浅深色/方向资源组合通过；提醒设置草稿重建/校验通过；检查了隔离账本首页截图。没有声称所有页面组合的人工视觉验收。 |
| 个人设备 | 没有安装、卸载、清空或启动个人手机应用。交付本机同签名Debug APK，真实手机升级不属于本次验收。 |

新增确定性测试覆盖天数/余量阈值、跨日/改时区、权限关闭、静默更新、消除、零消耗、窗口边界、过期边界、异常和冲突隔离。所有预测使用单次一致Room快照，不写入账本/outbox。

### 验收中发现的夹具问题

第二台AVD的SAF返回测试在Activity尚未重建时过早查询Compose层级；等待条件现在在层级暂不可用时继续等待，随后整套回归通过。清除了该次失败留下的零字节合成CSV。

首轮真实提醒夹具的两次读数相隔23小时59分59秒，应用按24小时最小跨度正确拒绝预测，故无通知。改为两天并新增“可预测且达到阈值”的前置断言，同时等待延迟任务入队落盘。无效试跑不计通过；之后两台真实后台验收通过。每轮均恢复模拟器网络设置并清理隔离包。

## 生产部署

实际部署revision：`51236f64b95c1b32cbac8df1b400f2c331a91f00`。此次部署用于邮件状态修复，保留既有SMTP私有配置，不新增业务预测接口、不注入生产故障或测试账本。

- 部署前在线副本迁移演练、旧代码验证备份、生产迁移后的全部原有行哈希核对通过，后端身份保持不变。基线revision为62；meters 3、readings 26、tariffs 5、recharges 4、operations 63、tokens 8、changes 62。
- 验证备份SHA-256：`3ddcd66767fd76eafacd4d77834798aa47760b502f9d62a46e2f63f601bfb991`，SQLite完整性为`ok`；既有备份保留策略照常执行。
- 实际导入：`/opt/utility-sync/src/utility_sync/notifications.py`，SHA-256为`108ed8379be4344dd79f053433adca83c0b872558aeeebbb225fa144aa20df0a`，与本地一致。
- 受保护HTTPS `/api/v1/status` 返回200；主服务、专用隧道、backup timer及monitor timer均active，数据库可读且允许写入，监控告警为空，通知enabled/configured为true、错误码为空。
- 状态验收使用一个临时只读token，完成后已撤销，所有原有token行保持不变。生产故障/恢复邮件逻辑以隔离测试验证；本次未要求人工确认新的测试邮件。
- 旧源码及核对记录保留于`/srv/utility-meter/deployment-51236f64b95c/`，其中`v13-verification.json`为脱敏部署后验证结果。后续发生写入后不得使用旧数据库覆盖当前账本。

这些是本次部署时点证据，不代表持续在线监测或真机验收。

## 重跑与本地证据

```sh
# utility-sync目录
.venv/bin/python -m pytest -q
.venv/bin/ruff check .
.venv/bin/ruff format --check .
# utility-tracker目录
./gradlew :app:testAcceptanceUnitTest :app:assembleDebug :app:assembleAcceptanceAndroidTest :app:lintDebug
# 仓库根目录，明确选取隔离AVD
python3 tools/android_regression.py --serial emulator-5554
python3 tools/android_regression.py --serial emulator-5556
python3 tools/reminder_acceptance.py --serial emulator-5554
python3 tools/reminder_acceptance.py --serial emulator-5556
python3 tools/backup_acceptance.py --serial emulator-5554 --previous-apk PATH_TO_1_2_APK --previous-test-apk PATH_TO_1_2_TEST_APK
utility-sync/.venv/bin/python tools/live_acceptance.py --mode local --serial-a emulator-5554 --serial-b emulator-5556
```

实际本机临时报告目录：`utility-v12-regression-xbrqb2s2`、`utility-v12-regression-v34xjzc7`、`utility-v13-reminders-0zfb2k9l`、`utility-v13-reminders-xg_j_x3b`、`utility-ledger-acceptance-m501nz51`。目录位于系统临时区，可能被系统清理；本报告保存脱敏结论。升级基线来自`be075e1`的临时独立源码副本。

GitHub CI及PR结果以该分支精确提交的Checks为准；PR保持打开，由用户安排squash merge。PR创建后再更新本地忽略的docs状态文档。
