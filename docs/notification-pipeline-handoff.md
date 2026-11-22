# 通知监听 → 创建提醒：完整实现说明（供外部代码审查）

项目：`C:\Users\lenovo\Documents\deepseek-harness\default-workspace\NotiAlarm`
包名：`cn.notialarm`
版本：0.3.2（versionCode 8）
本文只描述“从系统通知到提醒卡片”的链路，不涉及闹钟响铃 UI。

---

## 0. 一句话概括

`NotifyListener` 接收系统通知，先按“用户勾选的 App”过滤，再从通知 extras 提取正文，做群聊汇总/常驻/黑名单过滤，然后交给 `Processor`；`Processor` 用本地规则或大模型解析出时间，去重后调用 `Reminders.create` 写入 SharedPreferences 并排闹钟。

链路上任何一步 `return`，用户看到的结果都是“没有创建提醒”；但不同步骤的副作用不同（有的写“未记录”，有的什么都不写），这是排查的关键。

---

## 1. 权限与系统绑定

### 1.1 Manifest 声明

`app/src/main/AndroidManifest.xml:62-70`：

```xml
<service
    android:name=".NotifyListener"
    android:exported="true"
    android:label="@string/app_name"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
</service>
```

- 组件全名：`cn.notialarm/cn.notialarm.NotifyListener`
- `exported=true` 是系统绑定所必需；`android:permission` 保证只有系统能绑定。
- 没有额外声明 `BIND_NOTIFICATION_LISTENER_SERVICE` 的 uses-permission，这是正常的：该权限由系统持有，服务端声明即可。

### 1.2 用户授权

`ui/Permissions.kt:28-45`：

```kotlin
fun listenerGranted(activity: Activity): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(activity).contains(activity.packageName)
```

- 只判断“包名在已启用的监听器集合里”，不判断具体组件是否绑定成功。
- 授权入口：`Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`。
- 设置页顶部的“通知使用权”行会显示：
  - 未授权：`⚠️ 通知使用权`
  - 已授权但 `NotifyListener.connected == false`：`已授权，但监听服务没有连上`
  - 已连接：`✅ 通知使用权 / 监听服务运行中`

`connected` 是进程内静态变量，进程被杀/覆盖安装后会回到 `false`，即使系统权限仍然开着。

### 1.3 生命周期

`NotifyListener.kt:34-58`：

```kotlin
override fun onListenerConnected() {
    connected = true
    notifyState()
    Diag.onConnected(this)
    Reminders.housekeeping(this)
    if (!replayed) {
        replayed = true
        replayActive()
    }
}

override fun onListenerDisconnected() {
    connected = false
    notifyState()
    runCatching { requestRebind(ComponentName(this, NotifyListener::class.java)) }
}

override fun onNotificationPosted(sbn: StatusBarNotification) {
    if (!connected) {
        connected = true
        notifyState()
    }
    handle(sbn, replay = false)
}
```

- `onListenerConnected` 只代表“系统把监听服务绑上了”，不代表用户已经勾选微信。
- `onListenerConnected` 里会做两件事：
  1. `Reminders.housekeeping()`：归档过期、清理旧数据；
  2. 第一次连接时 `replayActive()`：把当前通知栏里 24 小时内、仍在的通知重新处理一遍。
- `replayActive()` 用 `activeNotifications`，不会触发 `Diag.onReceived`（`handle(..., replay = true)` 时跳过计数），所以诊断里的“收到 0 条、进入解析很多条”可能表示：**这次解析全部来自重放，而不是新的 `onNotificationPosted` 回调**。
- `onListenerDisconnected()` 会立即 `requestRebind`；设置页/主界面还有手动 `rebind()`：
  - `NotifyListener.rebind()` 先禁用组件、再启用、再 `requestRebind`（`NotifyListener.kt:251-259`）。
  - 部分厂商 ROM 对“禁用再启用”处理不同，可能导致监听服务短暂不可用，需重新打开通知使用权。

---

## 2. 单条通知的处理入口：`handle()`

`NotifyListener.kt:78-162`，按执行顺序：

### 2.1 跳过本应用自己的通知

```kotlin
if (sbn.packageName == packageName) {
    if (sbn.id == Notifier.ID_LISTENER_TEST) {
        lastTestAck = System.currentTimeMillis()
        runCatching { cancelNotification(sbn.key) }
    }
    return
}
```

- 只有“测试通知监听”用的 `ID_LISTENER_TEST = 2` 会被确认，其他本应用通知直接忽略。
- 如果自检通知收不到，说明系统没有把通知回调给本应用，或者监听服务没绑定；这不是解析问题。

### 2.2 读取偏好和来源名

```kotlin
val n = sbn.notification
val prefs = Prefs(this)
val app = appLabel(sbn.packageName)
if (!replay) Diag.onReceived(this, app)
```

- `Diag.onReceived` 只统计“实时回调”，不统计重放。
- `appLabel` 取应用名，失败回退包名。

### 2.3 用户勾选的 App 过滤（最可疑的一步）

```kotlin
val explicitlyMonitored = sbn.packageName in prefs.monitoredPackages
if (!prefs.monitorAll && !explicitlyMonitored) return
```

- `monitoredPackages` 来自 SharedPreferences 文件 `settings.xml` 的字符串集合键 `pkgs`（`data/Prefs.kt:15-17`）。
- 默认值：`com.tencent.mm`、`com.tencent.mobileqq`、`com.tencent.tim`（`Prefs.kt:151`）。
- `monitorAll` 来自 `settings.xml` 的布尔键 `monitor_all`，默认 `false`。
- 如果微信的 `sbn.packageName` 不在 `pkgs` 里，且 `monitor_all=false`，这里**直接 return，不写“未记录”，不写任何日志**。
- 这是“微信完全没反应”的第一优先级检查点：
  - 微信/QQ 是否被用户取消勾选；
  - 设置页勾选后是否按了“保存”（勾选框本身只改内存集合，见第 6 节）；
  - 微信是否是工作资料/分身/平行空间，包名是否仍是 `com.tencent.mm`；
  - 保存时是否因为 App 列表尚未加载完，把 `selected` 覆盖成空集或不含微信的集合。

### 2.4 提取标题、会话名、正文

```kotlin
val extras = n.extras
val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
val messaging = runCatching {
    NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
}.getOrNull()
val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
    ?.toString()?.trim().orEmpty()
    .ifBlank { messaging?.conversationTitle?.toString()?.trim().orEmpty() }
    .ifBlank { extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty() }
    .ifBlank { rawTitle }
val text = extractText(n, messaging)
```

`extractText()` 在 `NotifyListener.kt:193-221`，依次加入：

1. `MessagingStyle` 最近 3 条消息的 `text`；
2. 系统 `Notification.EXTRA_MESSAGES` 中最近 3 个 `Bundle` 的 `"text"`（兼容微信/QQ 自定义 Parcelable）；
3. `EXTRA_BIG_TEXT`；
4. `EXTRA_TEXT`；
5. `EXTRA_SUB_TEXT`；
6. `EXTRA_TEXT_LINES` 的每一行；
7. `tickerText`。

最后去掉“被其他片段完整包含”的短句，用换行拼接。

### 2.5 标志位与时间提示

```kotlin
val groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0
val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
val hasTime = TimeParser.hasTimeHint(text)
```

- `hasTime` 只检查拼好的正文，不检查标题/会话名/`EXTRA_MESSAGES` 之外的自定义字段。
- 如果微信把时间只放在标题（例如“明天下午3点开会”作为 title），正文为空或只有“1条新消息”，`hasTime` 可能为 `false`。

### 2.6 正文为空

```kotlin
if (text.isBlank()) {
    drop(..., "通知没有正文（可能关闭了“通知显示消息详情”）", recordDrop = explicitlyMonitored)
    return
}
```

- 如果用户明确勾选了微信，会进入“未记录的通知”，理由是“通知没有正文”；
- 如果没有勾选微信（且 `monitorAll=false`），前面已经 return，不会看到这条记录。

### 2.7 群聊汇总过滤

```kotlin
if (groupSummary && !hasTime) {
    drop(..., "群聊汇总里没有可识别的时间", recordDrop = explicitlyMonitored)
    return
}
```

- 微信/QQ 群聊常见行为：只发一条 `FLAG_GROUP_SUMMARY` 的汇总通知（“3 条新消息”），子通知可能不单独回调。
- 如果汇总里没有时间，这里会挡掉；用户看到“未记录：群聊汇总里没有可识别的时间”。
- 如果汇总通知本身带时间（例如“张三: 明天下午3点开会”），会继续解析。

### 2.8 黑名单

```kotlin
val hit = prefs.blacklistHit(sbn.packageName, conversation, text)
if (hit != null) {
    Diag.onDropped(this)
    return
}
```

- 命中关键词/会话/应用任一规则就静默丢弃，**不写 Dropped**（避免用户自己屏蔽的内容越积越多）。
- 如果用户曾经误加过“微信”或某个关键词，通知会在这里消失。

### 2.9 常驻通知过滤

```kotlin
if (ongoing && !hasTime) {
    drop(..., "常驻通知里没有可识别的时间", recordDrop = explicitlyMonitored)
    return
}
```

- 微信“正在运行”的前台服务通知、播放器通知会在这里被挡；
- 如果常驻通知确实带时间，会继续。

### 2.10 交给解析线程

```kotlin
SourceLauncher.remember(this, sbn.key, n.contentIntent)
Diag.onAllowed(this)
Processor.submit(
    applicationContext,
    Incoming(
        pkg = sbn.packageName,
        app = app,
        conversation = conversation,
        title = rawTitle,
        text = text,
        notifKey = sbn.key,
        contentIntent = n.contentIntent,
        postedAt = sbn.postTime,
        recordDrop = explicitlyMonitored,
    ),
)
```

- `SourceLauncher.remember` 保存通知的 `contentIntent`，用于“打开原通知”。
- `Diag.onAllowed` 在这里才加 1；“进入解析”计数不包含被群聊/常驻/黑名单/空正文挡掉的。
- `Processor.submit` 是单线程 `Executor`，异步执行。

---

## 3. 解析与创建：`Processor`

`Processor.kt:25-102`：

### 3.1 线程模型

```kotlin
private val executor = Executors.newSingleThreadExecutor()
fun submit(ctx, incoming, onDone) {
    executor.execute {
        val (r, msg) = runCatching { process(ctx, incoming) }
            .getOrElse { null to "出错：${it.message}" }
        onDone?.invoke(r, msg)
    }
}
```

- 所有通知串行解析；大模型请求会阻塞后续通知（最长 10s connect + 30s read）。
- `process` 抛出的异常会被 `runCatching` 捕获，只写 ActivityLog“出错”，不会写 Dropped。这是排查时容易漏掉的分支。

### 3.2 手动/重试跳过去重

```kotlin
val manual = (incoming.pkg.isBlank() && incoming.notifKey.isBlank()) || incoming.force
if (!manual && fastDuplicate(incoming)) return null to "重复通知，已忽略"
```

- 主界面“试一试”的 `pkg`、`notifKey` 都为空，算手动；
- “未记录”里的“重试识别”用 `force=true`；
- 手动/重试不写 `SeenStore`，也不检查 `SeenStore`。

### 3.3 解析模式

```kotlin
val wantLlm = prefs.llmReady && TimeParser.hasTimeHint(text)
val result = when (prefs.parseMode) {
    Prefs.MODE_RULE -> TimeParser.parse(text, now)
    Prefs.MODE_LLM -> (if (wantLlm) llm() else null) ?: TimeParser.parse(text, now)
    else -> TimeParser.parse(text, now) ?: if (wantLlm) llm() else null
}
```

- `parseMode` 默认 `hybrid`（`settings.xml` 的 `mode`）。
- `hybrid`：先本地规则；只有本地规则返回 null 且 `wantLlm` 时才请求大模型。
- `wantLlm` 还要求 `TimeParser.hasTimeHint(text)`；如果正文没有时间提示，不会请求大模型。
- 大模型出错只记 `llmError`，最终可能仍返回 null。

### 3.4 解析失败

```kotlin
if (result == null) {
    ActivityLog.add(... "未识别：$brief")
    if (incoming.recordDrop) {
        DroppedStore.add(... reason = "没有识别到时间$why")
        Diag.onDropped(ctx)
    }
    return null to "没有识别到时间$why"
}
```

- 明确勾选的 App 会进“未记录”；
- 未勾选（`recordDrop=false`）只写 ActivityLog，不写 Dropped；
- 如果 `process` 抛异常，走 `runCatching` 的“出错”，既不是 Dropped，也不是正常“未识别”。

### 3.5 标题、时间、去重

```kotlin
val title = result.title.ifBlank { incoming.title.ifBlank { incoming.source } }.ifBlank { "通知" }
val seenKey = "${incoming.pkg}|$title|${result.time}"
if (!manual && SeenStore.isSeen(ctx, seenKey)) { ... return null to "已有相同提醒" }

val r = Reminders.create(ctx, incoming, title, result.time, result.assumed)
if (r == null) { ... return null to "已有相同提醒" }
if (!manual) SeenStore.mark(ctx, seenKey)
```

三道去重：

1. `fastDuplicate`：90 秒内同 `pkg + normalize(text)`；
2. `SeenStore`：7 天内同 `pkg + title + time`；
3. `Reminders.create` 内 `ReminderStore.isDuplicate`：**当前 active** 中同 `time` 且标题互相包含。

注意：`SeenStore` 在用户删除提醒后仍然保留 7 天；如果用户用同一条微信消息反复测试，第二次可能被它挡住（“已有相同提醒”）。主界面“试一试”和“重试识别”已绕过。

---

## 4. 写入提醒：`Reminders.create`

`Reminders.kt:23-50`：

```kotlin
if (ReminderStore.isDuplicate(ctx, title, time)) return null
val r = Reminder(
    id = ReminderStore.newId(ctx),
    title = title,
    time = time,
    offsets = offsets,
    source = incoming.source.ifBlank { incoming.app }.ifBlank { "通知" },
    text = incoming.text,
    pkg = incoming.pkg,
    conversation = incoming.conversation,
    notifKey = incoming.notifKey,
    timeAssumed = timeAssumed,
)
ReminderStore.save(ctx, r)
AlarmScheduler.schedule(ctx, r)
Prefs(ctx).lastCreatedId = r.id
Notifier.recordedSummary(ctx)
changed()
```

- `ReminderStore` 用 SharedPreferences 文件 `reminders.xml`，键 `list`，全量 JSON 数组（`data/Reminder.kt:64-69`）。
- `isDuplicate` 只看 active，不看 archived（`Reminder.kt:97-98`）。
- `AlarmScheduler.schedule` 对 `archived` 直接 return，正常提醒按 offsets 排多个 `AlarmManager` 闹钟（`alarm/AlarmScheduler.kt:33-40`）。
- `Notifier.recordedSummary` 是通知栏的“N 条待处理”汇总，不是闹钟；0.3.2 已改为低优先级/静音。

---

## 5. 状态与数据文件（排查用）

| 文件 | 键 | 含义 |
|---|---|---|
| `shared_prefs/settings.xml` | `pkgs` | 用户勾选监听的包名集合 |
| 同上 | `monitor_all` | 是否监听所有 App |
| 同上 | `mode` | `rule` / `hybrid` / `llm` |
| 同上 | `llm_key` / `llm_url` / `llm_model` | 大模型配置 |
| `shared_prefs/reminders.xml` | `list` | 所有提醒 JSON |
| `shared_prefs/dropped.xml` | `list` | “未记录的通知” |
| `shared_prefs/seen.xml` | `keys` | 7 天去重表（`SeenStore`，文件名 `seen`） |
| `shared_prefs/diag.xml` | `day` / `received` / `allowed` / `created` / `dropped` | 当日诊断计数（`Diag`，文件名 `diag`） |

> 排查时可直接 `adb shell run-as cn.notialarm ls shared_prefs` 查看实际文件名。

`Diag.summary()` 的“收到”只统计实时 `onNotificationPosted`，不统计 `replayActive()`；“进入解析”在 `Diag.onAllowed` 才加。因此：
- “收到 0、进入解析 112”不等于没有通知，可能是 112 条都来自重放；
- 要判断实时回调是否工作，测试时看“收到”是否增加。

---

## 6. 设置页勾选 App 的真实行为

`ui/SettingsActivity.kt:396-430`：

```kotlin
selected.clear()
selected += prefs.monitoredPackages
val firstTime = !prefs.packagesConfigured
Thread {
    val apps = pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
        .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .distinctBy { it.first }
        .filter { it.first != packageName }
    if (firstTime) {
        apps.filter { label 含 哈工大/工大/HIT/hit }.forEach { selected += it.first }
    }
    runOnUiThread {
        for ((pkg, label) in apps) {
            container.addView(CheckBox(this).apply {
                text = "$label\n$pkg"
                isChecked = pkg in selected
                setOnCheckedChangeListener { _, on -> if (on) selected += pkg else selected -= pkg }
            })
        }
    }
}.start()
```

关键点：

- 勾选框只改内存里的 `selected`，**只有点“保存”才会写入 `prefs.monitoredPackages = selected.toSet()`**（`SettingsActivity.kt:268-270`）。
- App 列表来自 `queryIntentActivities(ACTION_MAIN + CATEGORY_LAUNCHER)`：
  - 没有桌面图标的 App、工作资料里的 App、部分分身 App 可能不在这里；
  - 微信正常有桌面图标，通常可见。
- `firstTime` 只在 `pkgs` 键不存在时为 true；第一次打开会自动勾选名称含“哈工大/工大/HIT/hit”的 App，其他 App 需要用户手动勾选。
- 如果用户在列表加载完成前就点“保存”，`selected` 可能只有旧值/默认值；此时可能把微信从勾选集合中覆盖掉。
- `monitorAll` 开关也只会在保存时写入。

---

## 7. 这条链路最可能出问题的位置（按优先级）

> 以下是基于代码的假设，不是已确认结论；需要用第 8 节的诊断逐条排除。

### H1. 微信不在 `monitoredPackages`，或保存没生效

证据链：
- 用户截图里“未记录”出现 QQ，但没有微信；
- 如果微信被明确勾选且正文为空/群聊汇总，应该会在“未记录”留下记录；
- 完全没有微信记录，最符合“第 2.3 步直接 return”。

要检查：
- `settings.xml` 的 `pkgs` 是否含 `com.tencent.mm`；
- 设置页微信复选框是否勾选、是否点过“保存”；
- `monitor_all` 是否为 true；
- 微信是否工作资料/分身，包名是否仍是 `com.tencent.mm`。

### H2. 微信通知只发群聊汇总，且汇总没有时间

- `groupSummary && !hasTime` 会整条丢弃；
- 如果微信只回调汇总、不回调子消息，就永远不会创建；
- 需要确认 `onNotificationPosted` 收到的微信通知 `flags` 是否含 `FLAG_GROUP_SUMMARY`，以及 `extras` 里有没有 `EXTRA_MESSAGES` / `EXTRA_TEXT`。

### H3. 微信通知正文被系统/微信脱敏

- 锁屏“隐藏通知内容”、微信内“通知显示消息详情”关闭、Android 的“敏感通知”脱敏，都会让 `EXTRA_TEXT` / `EXTRA_MESSAGES` 为空；
- 此时如果微信被勾选，会进“未记录：通知没有正文”；
- 如果没有勾选，则完全静默。

### H4. 监听服务没有实时回调，只有重放

- `Diag` 的“收到 0、进入解析 N”是典型信号；
- 可能是覆盖安装后服务未重连、厂商后台限制、`requestRebind` 失败；
- 需要看 `enabled_notification_listeners`、`dumpsys activity services`，以及测试通知是否能收到。

### H5. 解析失败 / 去重

- 微信文本是“明天下午3点”但没有事件词，`TimeParser` 可能返回 null；
- 同标题同时间已被 `ReminderStore.isDuplicate` 或 `SeenStore` 挡住；
- 这类会在 ActivityLog 里留下“未识别”/“已有相同提醒”，但不会创建。

### H6. 其他可能

- 用户勾选的 App 列表被 `loadApps` 的异步加载/保存时序覆盖；
- 微信消息通知的 `packageName` 是分身/平行空间包名；
- 黑名单误命中（会话名/关键词）；
- `Processor` 线程被大模型请求阻塞，用户以为“没反应”；
- `onNotificationPosted` 内部抛异常（目前没有 try/catch 包住整个 `handle`，只有局部 `runCatching`）。

---

## 8. 建议的 5 分钟诊断顺序

在用户手机上（debug 包可用 `run-as`）：

### 第 1 步：确认系统把通知交给谁

```bash
adb shell settings get secure enabled_notification_listeners
adb shell dumpsys activity services cn.notialarm | grep -i -A3 NotifyListener
```

期望：
- 第一行包含 `cn.notialarm/cn.notialarm.NotifyListener`；
- 第二行能看到服务已绑定（`ServiceRecord` / `app=ProcessRecord`）。

### 第 2 步：确认实时回调是否工作

设置页点“测试通知监听”：
- 成功 Toast“监听正常” → `onNotificationPosted` 能收到本应用通知；
- 失败 → 服务绑定/回调问题，先解决 H4，不要继续查解析。

### 第 3 步：确认微信包名和勾选状态

```bash
adb shell run-as cn.notialarm cat shared_prefs/settings.xml
```

看：
- `pkgs` 是否包含 `com.tencent.mm`；
- `monitor_all` 是否为 true。

再让用户发一条微信，观察设置页诊断“收到”是否 +1：
- 不增加 → 微信通知没到 `onNotificationPosted`（H1/H4/工作资料）；
- 增加但“进入解析”不增加 → 在 `handle` 的过滤分支（群聊/常驻/空正文/黑名单）；
- “进入解析”增加但“创建”不增加 → `Processor` 解析失败或去重。

### 第 4 步：看“未记录”与 ActivityLog

- 未记录里有微信 + 原因 → 直接定位到对应分支（空正文/群聊汇总/常驻）；
- 未记录没有微信，但“收到”增加 → 检查 `pkgs` / `monitor_all` / 黑名单；
- ActivityLog 有“未识别/已有相同提醒” → 解析/去重问题。

### 第 5 步：需要时加临时日志

当前代码没有按阶段打 `Log.d`，只有计数。建议在以下位置加日志（debug 构建）：

- `handle` 入口：`pkg`, `id`, `key`, `flags`, `extras.keySet()`, `text` 前 80 字；
- 第 2.3 步 return 前：`monitoredPackages`, `monitorAll`；
- 空正文/群聊/常驻/黑名单分支：命中的 reason；
- `Processor.process`：`parseMode`, `result`, `seenKey`, `isDuplicate`；
- `Reminders.create`：`isDuplicate` 返回值。

---

## 9. 给外部 AI 的具体问题

请它重点回答：

1. 微信在 Android 上发通知时，`StatusBarNotification.packageName` 是否一定等于 `com.tencent.mm`？工作资料/分身/平行空间下有什么不同？
2. 微信聊天通知通常使用 `MessagingStyle` 还是自定义 `RemoteViews`？`EXTRA_MESSAGES` 的 `Bundle` 键是否稳定是 `"text"`？
3. 微信群聊是否只发 `FLAG_GROUP_SUMMARY` 汇总、子通知是否还会单独回调？如果只发汇总，汇总的 `extras` 通常包含什么？
4. 微信通知在锁屏/后台/前台三种状态下，`EXTRA_TEXT` 是否会被脱敏？
5. 当前 `handle` 的过滤顺序（包名 → 空正文 → 群聊汇总 → 黑名单 → 常驻）是否有逻辑错误？例如是否应该先取 `EXTRA_MESSAGES` 再判断 `groupSummary`？
6. `conversation` 的回退顺序（`EXTRA_CONVERSATION_TITLE` → MessagingStyle → `EXTRA_SUB_TEXT` → `EXTRA_TITLE`）是否会把群名/联系人搞错，从而影响黑名单匹配？
7. `TimeParser.hasTimeHint` 只看正文，是否会漏掉“时间只在 title”的微信通知？
8. `SettingsActivity.loadApps` 的异步加载 + `selected` 内存集合 + 仅“保存”落盘，是否存在竞态或用户误操作导致微信被取消勾选？
9. `NotifyListener.rebind()` 的“禁用组件再启用”在主流 OEM 上是否安全？是否可能造成监听服务长时间不重连？
10. `onNotificationPosted` 没有整体 try/catch，是否有任何微信通知字段会触发未捕获异常，导致后续通知全部丢失？

---

## 10. 用户现场截图提供的证据

用户提供的截图（2026-11-22 15:52）：

- 顶部计数 `待处理 11 / 归档 25`；
- 列表里能看到来源为 `QQ · 哈工大雪协群5` 的提醒，说明：
  - 通知监听服务至少对 QQ 是工作的；
  - 解析、创建、列表显示链路对 QQ 是通的；
- “未记录的通知”里只有 `QQ / 我的电脑:[文件]`，**没有微信条目**；
- 没有看到“微信”来源的提醒。

这组证据把问题范围缩小到：
1. 微信通知没有被回调给本应用（包名/工作资料/OEM 限制）；
2. 微信通知被回调了，但在 `handle` 的“包名过滤 / 空正文 / 群聊汇总 / 常驻 / 黑名单”中被静默挡掉；
3. 微信通知解析失败，但因为没有勾选微信（`recordDrop=false`）而没有出现在“未记录”。

需要特别确认：QQ 能用，说明 `NotificationListenerService` 本身不是完全没绑定；但不能证明微信包名一定在 `pkgs` 里。

---

## 11. 关键源码索引

| 文件 | 作用 |
|---|---|
| `app/src/main/java/cn/notialarm/NotifyListener.kt` | 通知入口、过滤、正文提取、重放、重连 |
| `app/src/main/java/cn/notialarm/Processor.kt` | 去重、规则/大模型解析、创建 |
| `app/src/main/java/cn/notialarm/Reminders.kt` | 创建/更新/删除/归档、排闹钟 |
| `app/src/main/java/cn/notialarm/data/Incoming.kt` | 监听层传给解析层的数据 |
| `app/src/main/java/cn/notialarm/data/Prefs.kt` | 勾选包、monitorAll、黑名单、解析模式 |
| `app/src/main/java/cn/notialarm/data/Reminder.kt` | 提醒存储、去重、归档、清理 |
| `app/src/main/java/cn/notialarm/parse/TimeParser.kt` | 本地中文时间解析 |
| `app/src/main/java/cn/notialarm/parse/LlmParser.kt` | OpenAI 兼容大模型解析 |
| `app/src/main/java/cn/notialarm/ui/SettingsActivity.kt` | 勾选 App、保存偏好、测试监听 |
| `app/src/main/java/cn/notialarm/ui/Permissions.kt` | 通知使用权检测与入口 |
| `app/src/main/java/cn/notialarm/alarm/AlarmScheduler.kt` | AlarmManager 排程 |
