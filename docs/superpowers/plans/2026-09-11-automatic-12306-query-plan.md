# 12306 任务信息自动查询 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让已保存的任务信息自动驱动官方 12306 的查询表单、车次、席别和乘车人操作，并在提交后停在待支付页面。

**Architecture:** 在现有 `TicketAccessibilityService` 状态机前增加官方查询表单阶段。纯 Kotlin 匹配器负责日期、车站候选项、控件标签和状态转换决策；Android 交互层只执行经过唯一性验证的 `AccessibilityNodeInfo` 动作。所有不确定页面、字段或动作都写入脱敏诊断并进入人工接管。

**Tech Stack:** Kotlin 2.0.21、Android API 26-36、`AccessibilityService`、Jetpack Compose、JUnit 4、现有 `TaskStore`/`TicketTask` 持久化。

**Spec:** `docs/superpowers/specs/2026-09-11-automatic-12306-query-design.md`

## Global Constraints

- 不输入密码、短信验证码或支付信息，不绕过验证码、身份核验、风控和候补协议。
- 不使用固定屏幕坐标或全页面第一个同名文本；控件必须通过资源 ID、标签、属性和层级关系联合确认。
- 提交锁只在找到唯一可点击“提交订单”控件后获取；点击返回 `false` 必须释放锁，返回 `true` 只代表动作已派发。
- 诊断不得导出姓名、账号、证件号、订单号、金额或完整页面文本。
- 在真实设备和官方 12306 页面树验收前，新版本标记为诊断版本，不宣称可靠完成订单。

---

### Task 1: 执行状态与任务快照

**Files:**
- Modify: `app/src/main/java/com/example/ticketassistant/data/Models.kt`
- Modify: `app/src/main/java/com/example/ticketassistant/data/TaskStore.kt`
- Modify: `app/src/main/java/com/example/ticketassistant/notifications/TaskRestoreReceiver.kt`
- Modify: `app/src/main/java/com/example/ticketassistant/notifications/TaskAlarmReceiver.kt`
- Modify: `app/src/main/java/com/example/ticketassistant/MainActivity.kt`
- Test: `app/src/test/java/com/example/ticketassistant/data/TaskStoreStateTest.kt`

**Interfaces:**
- Produces `TaskStatus.OPENING_SEARCH`, `FILLING_DEPARTURE`, `FILLING_ARRIVAL`, `FILLING_DATE`, `SUBMITTING_SEARCH`。
- Produces `taskSnapshotKey(task: TicketTask): String`，由无障碍服务比较任务是否在一次执行中被编辑。

- [ ] **Step 1: Write failing state mapping tests**

```kotlin
@Test fun `query stages are active and snapshot changes are detectable`() {
    val task = TicketTask(
        taskId = "task-1",
        date = "2026-09-19",
        from = Station("汉口", "HKN"),
        to = Station("潜江", "QJN"),
        train = Train("D637", "汉口", "潜江", "07:25", "08:16", "00:51", emptyMap()),
        seat = "二等座",
        passengerName = "测试乘客",
        saleDateTime = null
    )
    assertTrue(TaskStatus.OPENING_SEARCH.isAccessibilityActive())
    assertTrue(TaskStatus.SUBMITTING_SEARCH.isAccessibilityActive())
    assertNotEquals(taskSnapshotKey(task), taskSnapshotKey(task.copy(seat = "一等座")))
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.example.ticketassistant.data.TaskStoreStateTest`

Expected: FAIL because the new statuses and snapshot helper do not exist.

- [ ] **Step 3: Add statuses and migration-safe persistence**

Add the five statuses before the existing `VALIDATING_SEARCH_RESULT` status. Include them in `TaskStore` active-status migration, `TaskRestoreReceiver` resume rules, and `MainActivity` status labels. Import `Station`, `Train`, and `TicketTask` in the test above. Keep unknown persisted enum values mapped to `ENABLED`/`DRAFT` using the existing defensive parsing.

Implement:

```kotlin
internal fun TaskStatus.isAccessibilityActive(): Boolean = this in setOf(
    TaskStatus.WAITING_OFFICIAL_PAGE,
    TaskStatus.OPENING_SEARCH,
    TaskStatus.FILLING_DEPARTURE,
    TaskStatus.FILLING_ARRIVAL,
    TaskStatus.FILLING_DATE,
    TaskStatus.SUBMITTING_SEARCH,
    TaskStatus.VALIDATING_SEARCH_RESULT,
    TaskStatus.SELECTING_TRAIN_SEAT,
    TaskStatus.SELECTING_PASSENGER,
    TaskStatus.VALIDATING_ORDER,
    TaskStatus.SUBMIT_ACTION_SENT,
    TaskStatus.WAITING_SERVER_RESULT
)

internal fun taskSnapshotKey(task: TicketTask): String = listOf(
    task.taskId, task.date, task.from.name, task.to.name,
    task.train.trainNo, task.train.depart, task.train.arrive, task.seat
).joinToString("|")
```

- [ ] **Step 4: Run the focused test and verify it passes**

Run the same Gradle test command. Expected: PASS.

- [ ] **Step 5: Commit**

```text
git add app/src/main/java/com/example/ticketassistant/data app/src/main/java/com/example/ticketassistant/notifications app/src/main/java/com/example/ticketassistant/MainActivity.kt app/src/test/java/com/example/ticketassistant/data/TaskStoreStateTest.kt
git commit -m "feat: add persisted search execution stages"
```

### Task 2: 查询表单纯匹配器

**Files:**
- Create: `app/src/main/java/com/example/ticketassistant/automation/SearchFormMatcher.kt`
- Create: `app/src/test/java/com/example/ticketassistant/automation/SearchFormMatcherTest.kt`

**Interfaces:**
- `enum class SearchField { DEPARTURE, ARRIVAL, DATE }`
- `fun dateVariants(date: String): Set<String>`
- `fun exactStationCandidate(text: String, stationName: String): Boolean`
- `fun matchesTravelDate(text: String, date: String): Boolean`
- `fun fieldLabelMatches(text: String, field: SearchField): Boolean`
- `fun actionLabelMatches(text: String, action: SearchAction): Boolean`

- [ ] **Step 1: Write failing matcher tests**

```kotlin
@Test fun `date matcher accepts official display variants`() {
    assertTrue(matchesTravelDate("2026年9月19日", "2026-09-19"))
    assertTrue(matchesTravelDate("09/19", "2026-09-19"))
    assertFalse(matchesTravelDate("2026年9月18日", "2026-09-19"))
}

@Test fun `station candidate requires exact normalized name`() {
    assertTrue(exactStationCandidate("汉口", "汉口"))
    assertFalse(exactStationCandidate("汉口站", "汉口"))
    assertFalse(exactStationCandidate("汉阳", "汉口"))
}
```

- [ ] **Step 2: Run matcher tests and verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.example.ticketassistant.automation.SearchFormMatcherTest`

Expected: FAIL because `SearchFormMatcher` does not exist.

- [ ] **Step 3: Implement normalized matching**

Normalize whitespace and punctuation, support `yyyy-MM-dd`, `yyyy年M月d日`, `yyyy/M/d`, `M月d日`, and `M/d`. Do not infer the year for a text that contains a different explicit year. Labels must match known Chinese synonyms only; a generic “下一步” must not be treated as a search action.

- [ ] **Step 4: Run matcher tests and verify they pass**

Run the same focused test command. Expected: PASS.

- [ ] **Step 5: Commit**

```text
git add app/src/main/java/com/example/ticketassistant/automation/SearchFormMatcher.kt app/src/test/java/com/example/ticketassistant/automation/SearchFormMatcherTest.kt
git commit -m "feat: add exact search form matching"
```

### Task 3: 无障碍查询表单交互器

**Files:**
- Create: `app/src/main/java/com/example/ticketassistant/automation/OfficialSearchInteractor.kt`
- Create: `app/src/test/java/com/example/ticketassistant/automation/OfficialSearchInteractorTest.kt`

**Interfaces:**
- `enum class SearchAction { OPEN_TICKETS, SELECT_DEPARTURE, SELECT_ARRIVAL, OPEN_DATE, SUBMIT_SEARCH }`
- `enum class InteractionResult { DONE, WAITING, FAILED }`
- `data class NodeDescriptor(val text: String?, val contentDescription: String?, val resourceId: String?, val className: String?, val editable: Boolean, val clickable: Boolean, val parentIndex: Int?)` for pure matcher tests.
- `fun findUniqueField(nodes: List<NodeDescriptor>, field: SearchField): NodeDescriptor?`
- `fun findExactCandidate(nodes: List<NodeDescriptor>, stationName: String): NodeDescriptor?`
- `class OfficialSearchInteractor` methods:
  - `fun openTickets(root: AccessibilityNodeInfo): InteractionResult`
  - `fun fillStation(root: AccessibilityNodeInfo, field: SearchField, stationName: String): InteractionResult`
  - `fun fillDate(root: AccessibilityNodeInfo, date: String): InteractionResult`
  - `fun submitSearch(root: AccessibilityNodeInfo): InteractionResult`

- [ ] **Step 1: Add pure action-plan tests**

Test `NodeDescriptor` lists through `findUniqueField(nodes, field)` and `findExactCandidate(nodes, stationName)`: editable fields are accepted only when the field label/resource ID is unique; station suggestions must have exact text and a clickable node/parent; duplicate candidates return `FAILED`; no candidate returns `WAITING` for one event cycle and then `FAILED` at the service timeout.

- [ ] **Step 2: Run the focused test and verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.example.ticketassistant.automation.OfficialSearchInteractorTest`

Expected: FAIL because the interactor and descriptor helpers do not exist.

- [ ] **Step 3: Implement node traversal and guarded actions**

Use `viewIdResourceName`, `className`, `isEditable`, `isClickable`, `hintText`, `text`, `contentDescription`, and up to five parent levels. For editable fields call `ACTION_FOCUS` followed by `ACTION_SET_TEXT` with a `Bundle`; after setting text, wait for an exact clickable suggestion. For date controls first try an editable date field; otherwise click a uniquely labeled date control and select a matching date node only when month/year context is present. Never use screen coordinates.

Record only action names and `DONE/WAITING/FAILED`; do not include entered station text in diagnostics emitted by this class.

- [ ] **Step 4: Run the focused test and verify it passes**

Run the same focused test command. Expected: PASS.

- [ ] **Step 5: Commit**

```text
git add app/src/main/java/com/example/ticketassistant/automation/OfficialSearchInteractor.kt app/src/test/java/com/example/ticketassistant/automation/OfficialSearchInteractorTest.kt
git commit -m "feat: automate guarded 12306 search form actions"
```

### Task 4: 无障碍服务状态机接入自动查询

**Files:**
- Modify: `app/src/main/java/com/example/ticketassistant/automation/TicketAccessibilityService.kt`
- Modify: `app/src/main/java/com/example/ticketassistant/automation/PageStateClassifier.kt`
- Modify: `app/src/main/java/com/example/ticketassistant/automation/OfficialAppLauncher.kt`
- Test: `app/src/test/java/com/example/ticketassistant/automation/PageStateClassifierTest.kt`

**Interfaces:**
- `TicketAccessibilityService` consumes `OfficialSearchInteractor` and `taskSnapshotKey`.
- `enum class SearchStage { OPEN_SEARCH, DEPARTURE, ARRIVAL, DATE, SUBMIT_SEARCH, RESULT }` is the pure form-flow stage type.
- `fun nextSearchStage(stage: SearchStage, page: OfficialPageState, action: InteractionResult): SearchStage` is pure and is used by transition tests.

- [ ] **Step 1: Add failing state transition tests**

Cover: a home page with the task enabled moves to `FILLING_DEPARTURE`; a stale result page is navigated back to the ticket form; a result page reached after the app fills the form can continue even when one field is not repeated in the WebView text; unknown/login/captcha/popup still enters takeover.

- [ ] **Step 2: Run existing and new classifier tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.example.ticketassistant.automation.PageStateClassifierTest`

Expected: new transition assertions fail until the service logic is changed.

- [ ] **Step 3: Refactor event handling**

On the first event, compare the current task snapshot with the active snapshot. Handle `HOME_PAGE` and form pages before the expected-page guard. Drive departure, arrival, date and query actions in order, persisting the matching `TaskStatus` after each successful action. Keep a bounded form timeout and event retry count; on timeout call `takeover("官方查询表单控件未能确认")`.

For an already visible `SEARCH_RESULT`, use task evidence plus exact train-container matching. If fields are absent only because the official WebView does not expose them, accept the fields that were successfully set in this session and require exact train/seat/container evidence before clicking. If the result belongs to a different train or task snapshot, use a single guarded back/ticket-tab navigation attempt; if it cannot reach the form, take over.

Keep existing passenger/order/submit/result safeguards. Do not loosen captcha, login, identity, risk-control, popup, duplicate-order, payment, or unknown-result handling.

- [ ] **Step 4: Run all unit tests**

Run: `.\gradlew.bat :app:testDebugUnitTest`

Expected: all tests pass, including the new form and transition cases.

- [ ] **Step 5: Commit**

```text
git add app/src/main/java/com/example/ticketassistant/automation/TicketAccessibilityService.kt app/src/main/java/com/example/ticketassistant/automation/PageStateClassifier.kt app/src/main/java/com/example/ticketassistant/automation/OfficialAppLauncher.kt app/src/test/java/com/example/ticketassistant/automation/PageStateClassifierTest.kt
git commit -m "feat: drive 12306 search from saved task"
```

### Task 5: 诊断状态和任务页文案

**Files:**
- Modify: `app/src/main/java/com/example/ticketassistant/MainActivity.kt`
- Modify: `app/src/main/java/com/example/ticketassistant/diagnostics/DiagnosticReport.kt`
- Test: `app/src/test/java/com/example/ticketassistant/diagnostics/DiagnosticReportTest.kt`

**Interfaces:**
- Diagnostic reports expose current stage/status and redacted action outcome.

- [ ] **Step 1: Add failing report assertions**

Assert that `FILLING_DEPARTURE`, `FILLING_DATE`, and `SUBMITTING_SEARCH` are rendered as automatic actions, while passenger names, entered station text from action messages, order numbers, prices, and account strings remain absent.

- [ ] **Step 2: Implement labels and redaction**

Update task status labels to distinguish “正在自动填写查询条件” from “正在核对查询结果”。Add a fixed redacted action vocabulary; when old tasks contain a sensitive value in `lastAction` or `lastEvent`, export only “已记录（内容已脱敏）” as before.

- [ ] **Step 3: Run diagnostics tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.example.ticketassistant.diagnostics.DiagnosticReportTest`

Expected: PASS.

- [ ] **Step 4: Commit**

```text
git add app/src/main/java/com/example/ticketassistant/MainActivity.kt app/src/main/java/com/example/ticketassistant/diagnostics/DiagnosticReport.kt app/src/test/java/com/example/ticketassistant/diagnostics/DiagnosticReportTest.kt
git commit -m "feat: expose automatic search diagnostics"
```

### Task 6: 回归、静态检查和发布配置

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `README.md`
- Create: `docs/diagnostics/2026-09-11-automatic-query-validation.md`

- [ ] **Step 1: Update version and documentation**

Increment `versionCode` to `15`, set `versionName` to `0.3.3-auto-query`, and document that the app now attempts automatic form filling but stops when official controls cannot be uniquely verified. Keep the release signing configuration unchanged.

- [ ] **Step 2: Run unit tests and lint**

Run:

```text
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
```

Expected: all tests pass and lint has no errors.

- [ ] **Step 3: Build and inspect the release APK**

Run: `C:\Gradle\bin\gradle.bat :app:assembleRelease` from the ASCII build junction `C:\codex-ticket-build` with the existing release signing properties. Verify `debuggable=false`, package name `com.example.ticketassistant`, `versionCode=15`, and signer digest equal to the previous release.

- [ ] **Step 4: Record validation limits**

Write the exact test results, APK SHA-256, lack of connected Android device, and the required real-device flow into `docs/diagnostics/2026-09-11-automatic-query-validation.md`.

- [ ] **Step 5: Commit**

```text
git add app/build.gradle.kts README.md docs/diagnostics/2026-09-11-automatic-query-validation.md
git commit -m "release: prepare 0.3.3 automatic query diagnostics"
```

### Task 7: GitHub 发布与更新通道核验

**Files:**
- No source files; use the signed APK from Task 6.

- [ ] **Step 1: Push the version tag**

Run: `git push origin v0.3.3-auto-query`.

- [ ] **Step 2: Create a GitHub Release**

Create a release for tag `v0.3.3-auto-query` with the APK asset `ticket-assistant-0.3.3-auto-query.apk`. Mark it as a pre-release until the user confirms the automatic form path on the real device; do not silently replace the latest stable channel.

- [ ] **Step 3: Verify asset integrity and update behavior**

Check the public release API reports the expected tag, asset size and SHA-256. Check `releases/latest` remains the previously verified stable release. A pre-release will not trigger the current in-app `latest` checker; the handoff must provide a manual download link and state this explicitly.

- [ ] **Step 4: Commit no generated artifacts**

Keep APKs outside the repository unless the existing release workflow explicitly tracks them; leave the working tree clean.
