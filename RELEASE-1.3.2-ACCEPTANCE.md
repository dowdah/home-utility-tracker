# V1.3.2 验收与交付记录

2026-10-08；基于 `c068082`，继续使用 `codex/fix-conflict-notification-state`。Android **1.3.2 / versionCode 6**，Room schema 3、同步协议2、后端和 token 不变，本轮未部署后端。

## 实现与诊断

修复前，Pixel_10_Pro 真实保存表单的复现证据为 `transaction=true roomFlow=true filter=true visible=false`：事务已经提交，Room 和筛选结果包含新记录，但稳定条目 ID 使列表保留旧滚动锚点，新增项仍在屏幕外。同一用例修复后可视断言通过。

仓储返回实际保存的 ID；ViewModel 捕获本次提交快照，只有成功新增产生可恢复的一次性定位请求。记录页的筛选、排序、空态与定位使用同一列表，等待该 ID 出现在 Room 结果后定位并突出显示约2秒，提供中英文无障碍状态。全部筛选保留；不匹配的表筛选切换到所属表。历史补录和同时间记录均按 ID 定位。编辑、删除、同步回执和远端新增不生成定位请求，稳定条目 ID 保留。

原创水滴镂空闪电标识提供明暗配色、108dp 自适应画布、中央66dp安全区、专用单色层，以及五种密度的普通/圆形兼容资源。夜间自适应入口避免夜间 PNG 覆盖矢量及单色资源。原始 SVG、明暗 SVG 预览、小尺寸与遮罩预览位于 `utility-tracker/artwork/`，可通过生成脚本重建。

## 验收结果

| 检查 | 实际结果与边界 |
| --- | --- |
| 本地 Android | 65项 JVM 单元测试通过，0失败/错误/跳过。Debug、Acceptance、测试 APK 构建通过；最终图标资源修复后构建和 lint 再次通过，0错误、34项既有警告。 |
| 两台 AVD | 指定 Pixel_6_Pro、Pixel_10_Pro 各50项不同测试通过：38项既有核心回归、8项新增读数显示、2项完整 Activity、2项图标资源。Pixel_6_Pro 的核心验收按用户暂停点分24+16项执行，未把中断计为通过；另外10项记录/Activity测试先前已通过。 |
| 记录显示 | 真实表单保存后直接断言目标行可见，断言本身不帮助滚动。覆盖无后端/token、空账本/软删除、长列表顶部/中部、三种表、全部和不匹配筛选、历史及同时间连续新增、失败保留输入、编辑删除、远端新增/回执不抢位置、延迟事务快照及新 ViewModel 恢复请求。 |
| Activity/外观 | 中英文、横竖屏、浅深色四种组合；草稿和保存后可视位置在 Activity 重建后保留。两台 AVD 及真机均执行。不是每一页面的全部笛卡尔组合人工检查。 |
| 图标资源 | 三台设备均通过2项：明暗自适应/单色透明镂空与安全区，20个已打包密度 PNG 的尺寸和内容。独立 drawable 实例避免测试渲染缓存相互污染；边缘透明度允许2/255的抗锯齿差异。 |
| 实际系统显示 | 两台 AVD 的启动器抽屉及系统应用信息显示新标识，明暗系统启动画面分别留证；Pixel Launcher 桌面实际主题着色通过。Xiaomi 13 的桌面和应用信息显示圆角遮罩下的新图标，点击后核对前台确为独立包。未清空桌面数据或修改图标别名。 |
| Xiaomi 13 / Android16 | 独立包 `.acceptancev132`、合成数据，8+2+2共12项不同测试通过。正式应用保留 **1.3.1 / versionCode5**，安装更新时间不变；未安装/卸载/清空正式包或读取、更换 token。 |
| 清理 | 两台 AVD 及真机的本轮验收包/测试包卸载；AVD 夜间模式、主题图标开关恢复。真机临时隔离启动权限恢复，GKD原启用状态、Shizuku用户服务、自动化事件状态及无障碍配置恢复，Shizuku主进程保持原PID。 |

本轮验收 APK SHA-256：`499ea20f3abab4d8c64cf47b6065c45e0cf402072922df81cfe4a07b41082fcb`。不同隔离后缀重新构建会改变 APK 哈希；此值对应 `.acceptancev132`，不是 CI 的正式包名构建。

## 遇到的问题与复测

- Pixel_6_Pro 的 System UI 无响应对话框抢占焦点；保存可视断言通过但取图失败。关闭该模拟器系统故障窗口后，Activity两项重测通过。未将早期失败计为通过。
- 图标测试最初暴露夜间 PNG 的资源优先级使系统读取到 BitmapDrawable；补充夜间自适应入口后，三台设备资源检查通过。另修正测试中共享矢量缓存对逐像素比较的影响。
- 真机第一轮被安全锁屏覆盖，停止测试并清理；用户解锁后重新执行。GKD被重新拉起导致取图时自动化通道冲突，8项记录测试已通过；按此前授权临时停用GKD包，剩余Activity2项及图标2项复测通过。结束恢复原启用状态与服务，设置/规则未清空。

## 复现与边界

```sh
cd utility-tracker
./gradlew -PacceptanceSuffix=.acceptancev132 :app:testAcceptanceUnitTest :app:assembleDebug :app:assembleAcceptance :app:assembleAcceptanceAndroidTest :app:lintDebug
cd ..
python3 tools/reading_acceptance.py --serial emulator-5554 --skip-build
python3 tools/reading_acceptance.py --serial emulator-5556 --skip-build
python3 tools/android_regression.py --serial emulator-5554
python3 tools/android_regression.py --serial emulator-5556
```

专项脚本默认12项，核心脚本现包含全部50项。可以通过 `--class-name` 仅续跑失败/未完成类别。脚本核对包名、APK哈希、真实执行数量、正式包版本/更新时间及清理结果。真机须提供独立核对的 `--expected-device-serial`，连接地址和设备身份不写入公开源码；必要时使用已授权的三个 Xiaomi/root 参数。

主题图标的实际支持以启动器为准；单色层在三台设备中渲染正确，实际系统着色由 Pixel Launcher 验证，没有声称小米桌面存在同样的主题图标开关。小米从应用信息启动的观察路径直接进入页面，没有显示独立的 Android 系统启动图标；AVD 明暗启动标识已分别确认，不人为增加额外启动页。真机系统图标只保存局部，应用测试截图仅包含合成窗口。

本轮没有重新进行生产应用升级、真实云备份、双设备生产同步或长时间后台提醒；相关历史证据仍在原V1.3/V1.3.1报告。后端沿用此前部署revision `51236f64b95c1b32cbac8df1b400f2c331a91f00`，不得用之后的squash提交号代替实际部署记录。

阶段提交：`05f69a2`（记录显示修复），`6a5076a`（图标资源），版本与验收记录作为最后一个提交。推送及最终CI以 [PR #5](https://github.com/dowdah/home-utility-tracker/pull/5) 为准，保持打开等待用户squash merge，不自动合并或删除分支。忽略的本地前端状态与V1.3.2摘要在PR更新之后同步，私有证据不提交。

## 2026-10-08 后续首页排序调整

按用户后续要求，首页复用统计页排序规则，显示电、冷水、热水；每组实际读数、预测和月度汇总整体移动。数据库及其他页面顺序不变，版本号保持1.3.2/versionCode6。此补丁没有安装到正式手机。

`Pixel_10_Pro` 与 `Pixel_6_Pro` 使用独立包 `.acceptancehomeorder`、合成账本各完成中文竖屏和英文横屏的实际界面检查：竖屏滚动读取标题顺序为电→冷水→热水；横屏根据显示坐标确认同序且三栏同一行，分别显示111kWh、22t、33t，未串表。已目视复核截图。构建与diff检查通过，临时包卸载、AVD方向设置恢复、已有应用版本及安装时间未变。这里只记录此次定向布局验收，不把此前每台50项全量回归写成重新执行；当前提交的CI结果见PR。
