'use strict';

// 页面只保留当前登录会话中的结果；浏览器存储中只保存可恢复的批次 ID。
const $ = (id) => document.getElementById(id);
const BATCH_KEY = 'holmes-log-batch-id';
const TERMINAL = new Set(['COMPLETED', 'PARTIAL', 'FAILED']);
const NARRATIVE_ACTIVE = new Set(['PENDING', 'RUNNING']);
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const state = {authenticated: false, authenticationRequired: false, loggingIn: false, config: null, report: null, batchId: '', selectedTask: '', generation: 0,
  timer: null, submitting: false, asking: false, narrativeRequest: null, automaticRequest: null, controllers: new Set()};
const TEXT = {
  home: ['Holmes home', 'Holmes 首页'], mainNavigation: ['Main navigation', '主导航'], language: ['Language', '语言'],
  logAnalysis: ['Frequent errors', '高频错误'], generalAsk: ['Ask Holmes', '通用问答'], logout: ['Sign out', '退出'],
  loginTitle: ['Sign in to Holmes', '登录 Holmes'], loginIntro: ['Enter your access password to continue.', '输入访问密码后继续。'],
  password: ['Access password', '访问密码'], login: ['Sign in', '登录'], loggingIn: ['Signing in…', '正在登录…'],
  logIntro: ['Automatically discover common errors in failed-task samples and give engineers an evidence chain.', '自动发现失败任务样本中的高频错误，将证据链交给工程师排查。'],
  automaticPolicy: ['Automatically check the last 24 hours, up to 50 failed tasks; sort by affected task count.', '自动检查最近24小时，最多50个失败任务；按影响任务数排序'],
  automaticModelRequest: ['Clicking Discover frequent errors also requests model-led analysis. {scope}', '点击“发现高频错误”即请求模型自主分析。{scope}'],
  automaticModelSummary: ['{model} · {destination} · Redacted evidence', '{model} · {destination} · 脱敏证据'],
  automaticDemoSummary: ['Local demo · No external model request', '本机演示 · 不请求外部模型'],
  automaticRulesSummary: ['Rule-based analysis only', '仅规则分析'],
  automaticRulesOnly: ['LLM analysis is not configured. Rule-based error cards and evidence chains remain available.', '未配置模型分析，仍会生成规则错误卡片与证据链。'],
  automaticSnapshotPolicy: ['For file data, the server anchors the automatic window to the snapshot observation time.', '文件数据的自动时间窗由服务端以快照观察时间为基准。'],
  samplingWindow: ['Actual sampled log window (UTC): {from} → {to}', '实际采样日志时间窗（UTC）：{from} → {to}'],
  loadingConfig: ['Loading configuration', '正在加载配置'], newAnalysis: ['Discover frequent errors', '发现高频错误'], logSource: ['Log source', '日志来源'],
  selectTasks: ['Select tasks', '选择任务'], latestFailed: ['Latest failed tasks', '最近失败任务'], explicitTasks: ['Specific task IDs', '指定任务 ID'],
  schedulerLatest: ['Recent failed tasks (by creation time)', '最近创建的失败任务（按创建时间）'],
  taskCount: ['Task count', '任务数量'], taskCountHint: ['Up to 50. If fewer failed tasks are available, the actual count is used.', '最多 50 个；不足时按实际失败任务数分析。'],
  taskIdsPlaceholder: ['One per line, or separated by commas', '每行一个，或用逗号分隔'], taskIdsHint: ['Up to 50 unique tasks. Duplicate IDs count once.', '最多 50 个任务；同一 ID 只计一次。'],
  from: ['Start time', '开始时间'], to: ['End time', '结束时间'], timezone: ['Enter times in {zone}. Maximum range: 24 hours.', '按本机时区 {zone} 输入；时间范围最长 24 小时。'],
  archiveChoice: ['Save this collection to FluxDrop', '将本次采集日志保存到 FluxDrop'],
  archiveHint: ['Otherwise, only the report and key excerpts are kept. Archive failures are reported explicitly.', '关闭时仅保留报告和关键摘录；归档失败会明确标记。'],
  startAnalysis: ['Discover frequent errors', '发现高频错误'], creating: ['Creating batch…', '正在创建…'], restoreBatch: ['Open an existing batch', '恢复已有批次'],
  batchId: ['Batch ID', '批次 ID'], openBatch: ['Open batch', '查看批次'], results: ['Analysis results', '分析结果'], downloadReport: ['Download JSON report', '下载 JSON 报告'],
  progressSelect: ['Loading failed tasks', '正在获取失败任务'], progressCollect: ['Collecting and analyzing logs', '正在采集与分析日志'],
  progressArchive: ['Archiving collected logs', '正在归档已采集日志'], progressOrganize: ['Preparing results', '正在整理结果'],
  progressModelWaiting: ['Waiting for model analysis', '等待模型整理结果'], progressModelRunning: ['Model is analyzing evidence', '模型正在分析证据'],
  progressQueued: ['Waiting to process tasks', '等待处理任务'], progressFinished: ['Investigation finished', '调查已结束'],
  progressPartial: ['Investigation ended with evidence gaps', '调查已结束，仍有取证缺口'], progressFailed: ['Investigation stopped', '调查已停止'],
  progressEmpty: ['No failed tasks in this sample', '本次样本中没有失败任务'], progressUnknown: ['Progress details unavailable', '进度详情暂未提供'],
  progressModelIncomplete: ['Model analysis did not finish', '模型整理未完成'], progressWaiting: ['Waiting', '等待中'], progressInProgress: ['In progress', '进行中'],
  progressPercent: ['{percent}% of tasks processed', '任务处理 {percent}%'],
  progressCountUnknown: ['Task-processing count unavailable', '任务处理数暂未提供'], progressNoTasks: ['No tasks selected', '未选中任务'],
  progressCountScope: ['Calculated from processed selected tasks, including no matches, failures, and skips.', '按已选任务的处理数量计算，包含无命中、失败与跳过。'],
  progressSelectCaption: ['Loading failed tasks; processing progress will appear once the list is ready.', '正在读取失败任务，取得名单后显示处理进度。'],
  progressArchiveCaption: ['Archiving is in progress. The bar measures task processing, not upload progress.', '正在归档；进度条表示任务处理数，不代表上传进度。'],
  progressModelCaption: ['Log processing has ended. Analysis and recommendations are being generated from the saved evidence.', '日志处理已结束，正在基于已有证据生成分析与建议。'],
  progressPartialCaption: ['Results are saved. Expand the details to review evidence gaps.', '当前结果已保存，可展开查看取证缺口。'],
  progressStoppedCaption: ['Current results are retained. Review the reason for stopping.', '当前结果已保留，请查看停止原因。'],
  progressEmptyCaption: ['No failed tasks were selected in this query. Check again later.', '本次查询未选中失败任务，可稍后再查。'],
  progressUnknownCaption: ['This report does not provide enough progress information. Saved evidence remains available.', '报告未提供足够的进度信息，已保存证据仍可查看。'],
  progressModelIncompleteCaption: ['Task results are retained. Model analysis did not finish; its details show whether an explicit retry is available.', '任务结果已保留，模型整理未完成；可在模型详情中查看是否可主动重试。'],
  progressStepSelect: ['Load failed tasks', '获取失败任务'], progressStepCollect: ['Collect task logs', '采集关联日志'],
  progressStepAnalyze: ['Analyze errors', '分析错误'], progressStepOrganize: ['Prepare results', '整理结果'],
  progressStepComplete: ['Finished', '已结束'], progressStepActive: ['In progress', '进行中'], progressStepPending: ['Waiting', '等待中'], progressStepStopped: ['Stopped', '已停止'],
  selectionUnrecorded: ['This report did not record whether the task list was confirmed. Review the saved details.', '报告未记录名单确认状态，请查看已保存详情。'],
  samplesZeroPending: ['Preparing this batch’s task results; error counts are not displayed yet.', '正在确认本批任务处理结果，暂不显示错误统计。'],
  samplesQueued: ['The task list is fixed. Error samples will appear after log processing begins.', '任务名单已确定，开始处理日志后显示错误样本。'],
  samplesSelecting: ['Loading the failed-task list. Error samples will appear after log processing begins.', '正在获取失败任务名单，开始处理日志后显示错误样本。'],
  samplesOrganizing: ['No clear error sample has been identified by rules so far; results are still being prepared.', '规则暂未识别到明确错误样本，结果仍在整理中。'],
  samplesNoTasks: ['The confirmed sample contains no failed tasks.', '本次已确认样本中没有失败任务。'],
  taskProgress: ['Task progress', '任务处理进度'], retry: ['Retry', '重新查询'], evidenceQuality: ['Evidence scope and gaps', '取证范围与缺口'],
  technicalDetails: ['Technical details', '技术详情'], failureClusters: ['Failure patterns', '失败签名聚合'],
  formAdvanced: ['Advanced: archive and optional AI', '高级选项：归档与可选 AI'], reportAdvanced: ['All tasks and investigation details', '全部任务与调查详情'],
  optionalNarrative: ['Optional AI explanation', '可选 AI 解释'], typicalErrors: ['Frequent errors', '高频错误'],
  typicalErrorsHint: ['Ranked by affected tasks in this sample, not platform-wide frequency. Grouped by observed error; a shared root cause has not been confirmed.', '按本批样本内影响任务数排序，不代表全平台错误频率。按错误现象分组，尚未确认共同根因。'],
  engineerSummary: ['Sample coverage: {processed} / {selected} tasks processed · {errors} with error samples · {pending} need evidence · {groups} error groups', '本批样本覆盖：已处理 {processed} / {selected} 个任务 · {errors} 个有错误样本 · {pending} 个待补证据 · {groups} 类错误'],
  engineerViewMissing: ['This server did not provide the engineer view. Restart the updated service and reopen the batch; error groups cannot be inferred from this report.', '服务未提供工程师视图，请重启更新后的服务并重新打开批次；不能从当前报告猜测错误分组。'],
  engineerViewWaiting: ['Waiting for the engineer view. If it remains unavailable after processing, restart the updated service and reopen this batch.', '正在等待工程师视图；处理完成后仍未提供时，请重启更新后的服务并重新打开批次。'],
  samplesWaiting: ['Collecting task error logs. Samples will appear as tasks are processed.', '正在采集任务错误日志，处理后将显示典型样本。'],
  samplesEmpty: ['No clear error sample is available yet. Review the tasks needing more evidence below.', '暂无明确错误样本，请查看下方待补证据任务。'],
  groupsLimited: ['Showing {displayed} / {total} error groups. A task can appear in multiple groups; group counts must not be added as a unique task total.', '显示 {displayed} / {total} 类错误。同一任务可出现在多组，不能将各组人数相加当作总人数。'],
  groupOverlap: ['A task may appear in more than one error group.', '同一任务可能出现在多个错误分组中。'],
  affectedTasks: ['{count} sampled tasks with this error', '样本内 {count} 个任务出现此错误'], componentLabel: ['Component', '组件'], nodeLabel: ['Node type', '节点类型'],
  ownerRole: ['Suggested engineer role', '建议工程师角色'], unassigned: ['Unassigned', '待分配'], recommendations: ['Suggested checks', '建议排查'],
  representativeLog: ['Representative log', '代表日志'], representativeUnavailable: ['The representative evidence is not available in this report.', '本报告暂未提供可打开的代表证据。'],
  legacySummaryMissing: ['This older report did not save an error summary; open the original evidence.', '旧报告未保存异常摘要，请打开原始证据。'],
  viewTaskEvidence: ['View full log evidence', '查看完整日志证据'], anotherRepresentativeLog: ['Representative log from another task', '另一个任务的代表日志'],
  moreRepresentativeLog: ['More representative evidence', '更多代表日志'], affectedTaskList: ['View affected tasks', '查看涉及任务'], downloadSample: ['Download engineer brief (.txt)', '下载工程师处理单（.txt）'],
  pendingEvidence: ['Tasks needing more evidence', '待补证据任务'], pendingNoMatch: ['No task-ID log match in this window. Check the actual execution time and Prompt mapping.', '此时间窗内 Task ID 未命中；核对实际执行时间及 Prompt 映射。'],
  pendingQueryFailed: ['Log collection failed. Check source authentication, access, and the collection error before retrying.', '日志采集失败；先检查来源认证、权限与采集错误，再重试。'],
  pendingQuerySkipped: ['Log collection was skipped. Resolve the source failure or collection budget, then start a new batch.', '日志采集已跳过；先处理来源故障或采集预算，再新建批次。'],
  pendingNoError: ['Logs were collected, but no clear error was identified. Review the task evidence and execution window.', '已有日志，未识别到明确报错；查看任务证据并核对执行窗口。'],
  pendingLegacy: ['This older report did not record the collection outcome. Review its recorded details or run a new collection.', '旧报告未记录采集结果；查看已保存详情或新建采集。'],
  pendingUnknown: ['The report does not provide a reason. Review the saved task details.', '报告未提供原因，请查看已保存的任务详情。'],
  exportFacts: ['Observed error facts', '已观察错误事实'], exportTasks: ['Related task IDs', '涉及任务 ID'], exportSource: ['Source batch and evidence', '来源批次与证据'],
  exportScope: ['This brief contains redacted structured evidence only; it does not assign an engineer or confirm final-execution ownership.', '处理单仅包含已脱敏的结构化证据；不代表已分配工程师或已确认最终执行归属。'],
  qualityDetails: ['Execution ownership and quality details', '执行归属与质量详情'],
  groupModelAnalysis: ['Model analysis', '模型分析'], groupModelEvidence: ['Evidence cited by the model', '模型引用的证据'],
  modelInterpretationScope: ['Model interpretation of the cited observations; verify the suggested causes before applying a fix.', '模型基于所引证据解释现象；建议的原因仍需工程师验证。'],
  clusterHint: ['Percentages use all selected failed tasks. A failure signature describes an observation, not a proven shared root cause.', '占比分母为本批次全部选中失败任务。失败签名描述已观察现象，不能直接当作共同根因。'],
  failedTasks: ['Failed tasks', '失败任务'], failureSignature: ['Failure signature', '失败签名'], finalAttempt: ['Final attempt', '最终执行'],
  evidenceStatus: ['Evidence status', '取证状态'], details: ['Details', '详情'], waitingTasks: ['Waiting for task results.', '正在等待任务处理结果。'],
  taskDetails: ['Task details', '任务详情'], close: ['Close', '收起'], logArchive: ['Log archive', '日志归档'],
  askIntro: ['Investigate a question using the configured model and tools.', '通过已配置的模型和工具调查问题。'], yourQuestion: ['Your question', '你的问题'],
  questionPlaceholder: ['Describe the problem and any known clues', '描述要调查的问题和已知线索'], askSubmit: ['Submit question', '提交问题'],
  answer: ['Answer', '回答'], investigationTrace: ['Investigation trace', '查看调查过程'], investigating: ['Investigating. Please wait…', '正在调查，请稍候…'],
  snapshotReady: ['Task snapshot ready · {namespace} · {count} failed tasks · observed {time}', '任务快照已就绪 · {namespace} · {count} 个失败任务 · 观察时间 {time}'],
  snapshotMissing: ['The task snapshot is not ready. Configure a task-state snapshot on the server before starting an analysis.', '任务快照尚未就绪，请先在服务端配置任务状态快照，再开始分析。'],
  schedulerAutomatic: ['Tasks are loaded automatically from the scheduler when an analysis starts.', '开始分析时，将自动从调度器加载任务。'],
  schedulerMissing: ['Automatic scheduler task loading is not configured yet.', '尚未配置调度器自动任务加载。'],
  schedulerSelectionHint: ['Currently failed tasks are selected by creation time.', '按创建时间筛选当前状态为失败的任务。'],
  schedulerTimeHint: ['This is the log search window. Scheduler tasks are filtered by creation time in this range, not failure time. Execution-window and retry details may be unavailable.', '这里设置日志查询时间窗。调度器按该范围内的创建时间筛选任务，并非按失败时间；实际执行时间窗及重试信息可能缺失。'],
  demoBadge: ['Demo · file logs', '演示数据 · 文件日志'], fileBadge: ['File logs', '文件日志'], grafanaBadge: ['Grafana logs', 'Grafana 日志'],
  demoHint: ['Using offline demo data.', '当前使用离线演示数据。'], snapshotHint: ['A task snapshot is required to start.', '任务快照就绪后可提交。'],
  askUnavailable: ['Ask Holmes is not enabled. Configure the model connection in the server .env file and restart. No model key is entered in this page.', '通用问答尚未启用，请在服务端 .env 中配置模型访问参数后重启；无需在网页输入模型 Key。'],
  askDemo: ['Demo mode: answers are for local feature testing.', '当前处于演示模式，问答结果仅用于本地功能验证。'],
  queued: ['Queued', '等待处理'], running: ['Analyzing', '正在分析'], completed: ['Completed', '已完成'], partial: ['Partially completed', '部分完成'], failed: ['Failed', '分析失败'],
  loadingTasks: ['Loading tasks…', '正在加载任务…'], selectionPending: ['Task selection is in progress. Counts are fixed after selection completes.', '正在选择任务，选择完成后固定样本数量。'],
  selectionUnavailable: ['Task selection did not complete. No task results are available yet.', '任务选择未完成，暂时没有可显示的任务结果。'],
  selectionIncomplete: ['Task selection is incomplete. Counts describe only the tasks actually selected.', '任务选择不完整，统计仅描述实际选中的任务。'],
  batchLabel: ['Batch {id}', '批次 {id}'], taskProgressText: ['Tasks processed: {processed} / {total}', '已处理任务：{processed} / {total}'],
  reportSummary: ['Selected failed tasks: {total}. Each task belongs to exactly one primary cluster.', '选中失败任务：{total}。每个任务只计入一个主簇。'],
  reportCounts: ['Log events collected: {logs}; associated error events: {errors}. Error-event count is different from failed-task count.', '已采集日志：{logs} 条；已关联错误事件：{errors} 条。错误事件条数不等于失败任务数。'],
  partialHint: ['Some steps did not complete. Review task and archive statuses.', '部分步骤未完成，请查看任务与归档状态。'],
  technicalHint: ['Additional technical limitations are recorded below and in the JSON report.', '其他技术限制记录在下方详情及 JSON 报告中。'],
  technicalFlag: ['See technical details', '见技术详情'], waitingClusters: ['Clusters appear after the first task is processed.', '等待首个任务处理完成后显示聚合结果。'],
  clusterCount: ['Tasks: {count} · {ratio}', '{count} 个 · {ratio}'], clusterAria: ['{signature}: task count {count} of {total}', '{signature}：{count} / {total} 个任务'],
  noTasks: ['No failed tasks to display in this batch.', '本批次没有可显示的失败任务。'], analyzed: ['Analyzed', '已分析'], incomplete: ['Evidence incomplete', '证据有缺口'],
  ambiguous: ['Ambiguous', '存在歧义'], notProcessed: ['Not processed', '尚未处理'], view: ['View', '查看'], viewTask: ['View details for task {id}', '查看任务 {id} 的详情'],
  notProvided: ['Not provided', '未提供'], unknownPod: ['Unknown Pod', '未知 Pod'], unknownContainer: ['Unknown container', '未知容器'],
  taskLookupDetails: ['Task source and log lookup', '任务来源与日志查询'],
  schedulerTaskType: ['Task type: {type}', '任务类型：{type}'],
  schedulerErrorCode: ['Platform error code (source state, not a root cause): {code}', '平台错误码（源状态，非根因）：{code}'],
  schedulerRecordTimes: ['Platform task record (UTC): created {created}; updated {updated}', '平台任务记录（UTC）：创建 {created}；更新 {updated}'],
  lookupSummary: ['Log lookup: {strategy} · {status} · {count} log-source queries', '日志查询：{strategy} · {status} · {count} 次日志查询'],
  lookupAutomatic: ['Automatic source-task lifecycle window', '按任务来源记录自动选窗'],
  lookupBatch: ['Batch window', '批次时间窗'], lookupVerified: ['Verified attempt window', '已验证执行时间窗'],
  lookupStreamContext: ['Verified attempt stream context', '已验证执行的日志流上下文'],
  lookupFallback: ['Automatic 72-hour ID lookup', '自动回查最多72小时'],
  lookupMatched: ['Logs matched', '命中日志'], lookupNoMatch: ['No log match', '未匹配日志'],
  lookupFailed: ['Query failed', '查询失败'], lookupIncomplete: ['Query incomplete', '查询不完整'],
  lookupMapping: ['ID association: {mapping}', 'ID 关联方式：{mapping}'],
  lookupTaskIdOnly: ['Task ID only; Prompt and attempt ownership remain unverified', '仅 Task ID；Prompt 与执行归属尚未验证'],
  lookupSourceRecordMapping: ['Scheduler Task ID from the source record; attempt remains unverified', '源记录给出的调度器 Task ID；执行轮次未验证'],
  lookupTaskIdMapping: ['Platform task ID {logical} → scheduler task ID {scheduler}; attempt remains unverified', '平台任务 ID {logical} → 调度器任务 ID {scheduler}；执行轮次未验证'],
  lookupVerifiedMapping: ['Verified attempt mapping', '已验证执行映射'],
  lookupAllPods: ['Searched all Pods in the authorized namespace using the recorded IDs.', '已按记录的 ID 查询授权 namespace 内全部 Pod。'],
  lookupNoExecutionClaim: ['No log match does not mean the task did not execute.', '未匹配日志不代表任务未执行。'],
  lookupWindow: ['Window {index} (UTC{basis}): {from} → {to} · {count} events returned · {complete}', '窗口 {index}（UTC{basis}）：{from} → {to} · 返回 {count} 条 · {complete}'],
  lookupComplete: ['Query complete', '本次查询完整'], lookupPartial: ['Query incomplete', '本次查询不完整'],
  lookupCompletenessUnknown: ['Completeness not recorded', '完整性未记录'],
  lookupMoreWindows: ['{count} additional windows are recorded in the JSON report.', '另有 {count} 个时间窗记录在 JSON 报告中。'],
  qualityFlags: ['Quality flags', '质量标记'], noExtraFlags: ['No additional flags', '无额外标记'], refreshTask: ['Refresh task details', '刷新任务详情'],
  candidateEvidence: ['Candidate evidence', '候选证据'], noCandidateEvidence: ['No candidate evidence available', '暂无候选证据'], attemptUnverified: ['Attempt not verified', '执行归属未验证'],
  observedPromptId: ['Observed prompt ID', '观测到的 Prompt ID'],
  observedTaskId: ['Observed Task ID', '观测到的 Task ID'],
  sourceRecordTaskBasis: ['Scheduler Task ID association from the source record; attempt remains unverified', '源记录给出的调度器 Task ID 关联，执行轮次未验证'],
  observedPatterns: ['Observed error patterns', '候选错误现象'], noObservedPatterns: ['No error pattern observed.', '尚未观察到明确错误现象。'],
  legacyExecutionError: ['An execution error was observed, but this older report did not extract an error summary. Run a new analysis to generate one.', '已观测执行报错，旧报告未提取异常摘要；重新分析可生成新摘要。'],
  errorSummary: ['Error summary', '异常摘要'], rawEvidence: ['View original evidence excerpt', '查看原始证据摘录'],
  narrativeChoice: ['Generate an LLM explanation from representative evidence', '使用代表性证据生成 LLM 解释'],
  narrativeTransfer: ['Model: {model}. Destination: {destination}. The LLM can choose read-only analysis tools within this batch’s collected, redacted representative structured logs. Only this evidence is sent; raw logs are not sent. Tools do not query external Grafana, change tasks, assign engineers, or retry tasks.', '模型：{model}。发送目的地：{destination}。LLM 可在本批已采集、脱敏的结构化代表日志内自主调用只读分析工具。仅发送这些证据，不发送原始日志；工具不查询外部 Grafana、不改任务、不自动派工或重试。'],
  narrativeDemoTransfer: ['Offline demo model: {model}. Read-only analysis tools use this batch’s redacted structured evidence locally; no external model request is made. No tasks are changed, assigned, or retried.', '离线演示模型：{model}。只读分析工具在本地使用本批脱敏结构化证据，不请求外部模型，不修改任务、派工或重试。'],
  narrativeUnavailable: ['LLM explanation is disabled in the server configuration.', '服务端配置未启用日志 LLM 解释。'],
  narrativeScope: ['The model can inspect this batch’s representative evidence with read-only tools. Its analysis does not replace rule-based counts or verify final-attempt ownership.', '模型可通过只读工具查看本批代表性证据；分析不替代规则统计，也不会验证最终执行归属。'],
  generateNarrative: ['Generate LLM explanation', '生成 LLM 解释'], retryNarrative: ['Retry LLM explanation', '重试 LLM 解释'],
  narrativeSubmitting: ['Requesting explanation…', '正在提交解释请求…'], narrativePending: ['Queued', '等待解释'], narrativeRunning: ['Generating explanation', '正在生成解释'],
  narrativeAvailable: ['Explanation available', '解释已生成'], narrativeNotRequested: ['Not requested', '尚未请求'], narrativeConfigured: ['Configured; not requested', '已配置，尚未请求'],
  narrativeSkipped: ['Skipped: no evidence', '已跳过：无可用证据'], narrativeDisabled: ['Disabled', '未启用'], narrativeFailed: ['Explanation failed', '解释失败'], narrativeInterrupted: ['Interrupted', '解释已中断'],
  narrativeReadyDetail: ['A model is configured, but no explanation was requested for this batch. Generate one from the saved evidence without recollecting logs.', '模型已配置，但本批次尚未请求解释；可使用已保存证据生成解释，无需重新采集日志。'],
  narrativeNoneDetail: ['No LLM explanation was requested for this batch.', '本批次尚未请求 LLM 解释。'],
  narrativeWaitingDetail: ['The explanation request is queued; rule-based results are retained separately.', '解释请求正在排队，规则分析结果会单独保留。'],
  narrativeRunningDetail: ['The model is interpreting saved representative evidence. Logs are not recollected.', '模型正在解释已保存的代表性证据，不会重新采集日志。'],
  narrativeAvailableDetail: ['This explanation reuses the saved model result; opening this report does not call the model again.', '本页复用已保存的模型解释，打开报告不会再次调用模型。'],
  narrativeNoEvidenceDetail: ['No usable representative log evidence was available. The model request was skipped; no explanation was invented.', '没有可用的代表性日志证据，已跳过模型请求，不会编造解释。'],
  narrativeDisabledDetail: ['LLM explanation was disabled when this batch was handled. You can request it explicitly if the server is now configured.', '处理本批次时未启用 LLM 解释；如果服务端现已配置，可主动请求。'],
  narrativeFailedDetail: ['The explanation did not complete. Rule-based results are unchanged; you can explicitly retry after reviewing the safe error code.', '解释未完成，规则统计保持原样；查看安全错误码后可主动重试。'],
  narrativeInterruptedDetail: ['The explanation was interrupted. It is not retried automatically; request it again when ready.', '解释已中断，不会自动重试；需要时可再次请求。'],
  narrativeFailureInput: ['The saved analysis input is invalid. Check the server report format, then generate again.', '分析输入格式无效；请检查服务端报告格式后重新生成。'],
  narrativeFailureInputBudget: ['Representative evidence exceeded the input budget. Check the server evidence-sampling budget, then generate again.', '代表证据超过输入预算；请检查服务端证据采样预算后重新生成。'],
  narrativeFailureAuth: ['The model service rejected authentication (HTTP 401). Check the server model credentials, then retry.', '模型服务拒绝认证（HTTP 401）；请检查服务端模型凭据后重试。'],
  narrativeFailurePermission: ['The model service denied access (HTTP 403). Check model access permissions, then retry.', '模型服务拒绝访问（HTTP 403）；请检查模型访问权限后重试。'],
  narrativeFailureRate: ['The model service limited this request (HTTP 429). Retry later; check the service quota if it persists.', '模型服务限流（HTTP 429）；请稍后重试，持续出现时检查服务配额。'],
  narrativeFailureRequest: ['The model service rejected the request (HTTP 400). Check the model name and service compatibility, then retry.', '模型服务拒绝请求参数（HTTP 400）；请检查模型名与服务兼容性后重试。'],
  narrativeFailureServer: ['The model service returned HTTP {status}. Retry after the service recovers.', '模型服务暂时故障（HTTP {status}）；请稍后重试。'],
  narrativeFailureHttp: ['The model request failed (HTTP {status}). Check the service destination and model configuration, then retry.', '模型请求失败（HTTP {status}）；请检查服务目的地与模型配置后重试。'],
  narrativeFailureProviderTimeout: ['The model service timed out. Check service availability, then retry with the saved evidence.', '模型服务响应超时；请检查服务可用性，再基于现有证据重试。'],
  narrativeFailureNetwork: ['The server could not reach the model service. Check its network connection, then retry.', '服务器无法连接模型服务；请检查服务端网络后重试。'],
  narrativeFailureResponse: ['The model service returned an unreadable response. Check API compatibility, then retry.', '模型服务返回格式无法读取；请检查接口兼容性后重试。'],
  narrativeFailureToolProtocol: ['The model produced an invalid analysis-tool call. Generate again; check tool-call compatibility if it persists.', '模型生成了无效的分析工具调用；请重新生成，持续出现时检查工具调用兼容性。'],
  narrativeFailureNoEvidence: ['The analysis tools returned no usable evidence. Review log collection, then generate again.', '分析工具未取得可用证据；请先查看日志采集结果，再重新生成。'],
  narrativeFailureContext: ['Analysis reached the context limit. Check the server analysis budget, then generate again.', '分析达到上下文上限；请检查服务端分析预算后重新生成。'],
  narrativeFailureToolBudget: ['Analysis reached the tool-call limit. Review the call trace, then adjust the server budget or retry.', '分析达到工具调用上限；请查看调用过程，再调整服务端预算或重试。'],
  narrativeFailureJson: ['The model result was not valid JSON. Generate again; check structured-output compatibility if it persists.', '模型结果不是有效 JSON；请重新生成，持续出现时检查结构化输出兼容性。'],
  narrativeFailureSchema: ['The model result did not match the report format. Generate again; check structured-output compatibility if it persists.', '模型结果不符合报告格式；请重新生成，持续出现时检查结构化输出兼容性。'],
  narrativeFailureCitation: ['The model result referenced unavailable evidence. Generate again; the saved evidence remains usable.', '模型结果引用了不可用证据；请重新生成，原证据仍可查看。'],
  narrativeFailureOutput: ['The model output was incomplete. Generate again from the saved evidence.', '模型输出未完成；可基于现有证据重新生成。'],
  narrativeFailureUnrecorded: ['This report did not record a specific failure reason. Generate once more to identify it.', '报告没有记录具体失败原因，需要重新生成一次才能定位。'],
  narrativeFailureTimeout: ['Analysis exceeded this batch’s time limit. Generate again from the saved evidence.', '模型整理超过本批处理时限；可基于现有证据重新生成。'],
  narrativeFailureReportLimit: ['The model result exceeded the saved-report size limit. Check the server report budget, then retry.', '模型结果超过报告保存容量；请检查服务端报告预算后重试。'],
  narrativeFailureInterrupted: ['Model analysis was interrupted. Generate again when the service is ready.', '模型分析已中断；请在服务恢复后重新生成。'],
  narrativeFailureRulesInterrupted: ['The investigation stopped before model analysis finished. Review the stop reason, then generate from available evidence.', '基础调查中断，模型分析未完成；请查看停止原因，再基于可用证据生成。'],
  narrativeFailureDisabled: ['Model analysis is disabled. Check the server configuration before generating again.', '模型分析未启用；请检查服务端配置后再生成。'],
  narrativeFailureUnknown: ['Model analysis failed without a recognized reason. Review the server diagnostic record, then retry.', '模型分析失败，报告未提供可识别的原因；请查看服务端诊断记录后重试。'],
  narrativeErrorCode: ['Explanation error code: {code}', '模型解释错误码：{code}'], narrativeMissingContent: ['This report records an available explanation but provides no readable content.', '报告记录解释已生成，但未提供可显示的内容。'],
  narrativeSummary: ['Summary', '摘要'], narrativeFindings: ['Evidence-based findings', '基于证据的发现'], narrativeRecommendations: ['Recommended next steps', '建议的下一步'], narrativeLimitations: ['Limitations and unknowns', '限制与未知项'],
  narrativeGeneratedAt: ['Generated at', '生成时间'], narrativeModel: ['Model', '模型'], narrativeReference: ['Open evidence {id}', '查看证据 {id}'], narrativeReferenceMissing: ['Evidence reference unavailable: {id}', '证据引用不可用：{id}'],
  narrativeInputTruncated: ['The model input was limited to representative evidence. Some evidence was omitted by the input budget.', '模型输入受代表性证据预算限制，部分证据未进入本次解释。'],
  narrativeIncomplete: ['The model explanation is incomplete. Review the limitations alongside the original rule-based results.', '模型解释不完整，请结合限制说明和原始规则结果判断。'],
  narrativeToolSummary: ['Model-led analysis · {rounds} rounds · {calls} read-only evidence calls', '模型自主分析 · {rounds} 轮 · {calls} 次只读取证'],
  narrativeToolSteps: ['View read-only analysis steps', '查看只读分析过程'], narrativeToolSamples: ['Inspect error samples', '查看错误样本'],
  narrativeToolTimeline: ['Read task timeline', '读取任务时间线'], narrativeToolEvidence: ['Read evidence', '读取证据'], narrativeToolCompare: ['Compare tasks', '对比任务'],
  narrativeToolUnknown: ['Unrecognized tool', '未识别工具'], narrativeToolOk: ['Completed', '已完成'], narrativeToolRejected: ['Rejected', '已拒绝'],
  narrativeToolTruncated: ['Result truncated', '结果已截断'], narrativeToolStatusUnknown: ['Status not recorded', '状态未记录'],
  narrativeToolEvidenceCount: ['{count} evidence items returned', '返回证据 {count} 条'], narrativeToolNoSteps: ['No per-call steps were recorded.', '未记录逐次工具调用过程。'],
  taskOnlyNotice: ['These logs match the task ID. Final-attempt ownership, execution windows, and retries are not verified. Observed patterns are candidate evidence, not a final-attempt root cause.', '这些日志与任务 ID 匹配，但最终执行、执行时间窗和重试归属尚未验证。观测到的错误现象属于候选证据，不代表最终执行的根因。'],
  taskOnlyNoEvidenceNotice: ['This report has no candidate log evidence matching this task ID. Review the collection status and evidence gaps. Final-attempt ownership, execution windows, and retries remain unverified, so the final-attempt root cause cannot be determined.', '本报告暂无与该任务 ID 匹配的候选日志证据。请查看采集状态与取证缺口。最终执行、执行时间窗和重试归属仍未验证，暂不能判断最终执行的根因。'],
  taskOnlyBasis: ['Exact task ID; attempt not verified', '精确任务 ID 匹配；执行归属未验证'],
  observedClustersHint: ['These are task-level observations; final attempts and retries are not verified. One task may appear in several groups, so counts and percentages can overlap.', '这些是任务级观测现象，尚未验证最终执行及重试归属。同一任务可出现在多个组中，组数与占比允许重叠。'],
  keyEvidence: ['Key evidence', '关键证据'], noEvidence: ['No evidence can be confidently assigned yet. Review the quality flags and collection gaps.', '尚无可确定归属的关键证据，请结合质量标记与取证缺口判断。'],
  historicalRetry: ['Historical retry', '历史重试'], historicalAttempt: ['Historical attempt', '历史执行'], noAttempt: ['Attempt not provided', '未提供执行 ID'],
  noBasis: ['Association basis not provided', '关联依据未提供'], timeline: ['Task timeline ({count} representative events)', '任务时间线（{count} 条代表事件）'],
  logEvent: ['Log event', '日志事件'], contextOnly: ['Context only', '仅上下文'], noTimeline: ['No task timeline is available.', '没有可显示的任务时间线。'],
  relatedArchives: ['View {count} related archive references', '查看 {count} 个相关归档引用'],
  archiveNone: ['No log archive was requested. Only the report and key excerpts are retained.', '本批次未请求日志归档，仅保留报告和关键摘录。'],
  archivePending: ['Waiting for the log archive.', '等待日志归档。'], archiveAvailable: ['Collected logs are archived. Download the parts below.', '采集日志已归档，可通过下方链接下载。'],
  archiveEmpty: ['There are no collected logs to archive.', '本批次没有需要归档的日志。'], archiveUnavailable: ['The log archive is incomplete. Available parts and technical details are listed below.', '日志归档未完整完成，可用分块和技术详情如下。'],
  archiveUnknown: ['Archive status is unavailable. See the JSON report.', '归档状态暂不可用，详见 JSON 报告。'],
  archivePart: ['Log part', '日志分块'], downloadPart: ['Download log part', '下载日志分块'], linkExpired: ['The download link has expired.', '下载链接已过期。'],
  noLink: ['No download link is available.', '暂无可用下载链接。'], expires: ['Expires: {time}', '到期时间：{time}'], available: ['Available', '可用'], unavailable: ['Unavailable', '不可用'],
  sigCuda: ['CUDA out of memory', 'CUDA 显存不足'], sigOom: ['Out of memory', '内存不足'], sigTimeout: ['Timed out', '执行超时'], sigUnknown: ['Unknown cause', '原因未知'],
  sigAmbiguous: ['Ambiguous', '存在歧义'], sigIncomplete: ['Incomplete evidence', '证据不完整'],
  sigExecutionError: ['Execution error (generic observation, not a root cause)', '执行报错（泛化现象，非根因）'],
  sigHttp404: ['Resource not found', '资源未找到'],
  invalidResponse: ['The server returned an invalid response. Please retry.', '服务器返回的结果格式无效，请重试。'],
  loginExpired: ['Your session expired. Please sign in again.', '登录已失效，请重新登录。'], badRequest: ['Invalid request. Check the form values and retry.', '请求参数无效，请检查表单后重试。'],
  wrongPassword: ['The access password is incorrect.', '访问密码不正确。'], unauthorized: ['Authentication is required.', '请先登录。'], notFound: ['The batch or item does not exist, or has expired.', '批次或详情不存在，或已过期。'],
  unprocessable: ['The task selection cannot be processed. Check the task source, status, and selected range.', '任务选择无法处理，请检查任务来源、状态及所选范围。'],
  tooMany: ['Too many requests. Please wait and retry.', '请求过多，请稍后重试。'], unavailableError: ['The service is unavailable. Check the server configuration and retry.', '服务暂不可用，请检查服务端配置后重试。'],
  httpError: ['The request failed (HTTP {status}). Please retry.', '请求失败（HTTP {status}），请重试。'], genericError: ['An unexpected error occurred. Please retry.', '发生意外错误，请重试。'],
  timeout: ['The request timed out or was cancelled. Please retry.', '请求超时或已取消，请重试。'], network: ['Cannot reach the service. Confirm that the local server is running.', '无法连接服务，请确认本地服务正在运行。'],
  configError: ['Cannot load configuration: {error} Refresh the page to retry.', '无法加载配置：{error} 刷新页面可重试。'], sessionError: ['Cannot check the session: {error} Refresh the page to retry.', '无法检查会话：{error} 刷新页面可重试。'],
  configNotLoaded: ['Configuration has not loaded yet.', '应用配置尚未加载。'], invalidTimes: ['Enter valid start and end times in increasing order.', '请输入有效且递增的开始、结束时间。'],
  tooLongWindow: ['The time range cannot exceed 24 hours.', '时间范围不能超过 24 小时。'], invalidTaskCount: ['Task count must be an integer from 1 to 50.', '任务数量必须是 1–50 之间的整数。'],
  invalidIdsCount: ['Enter 1–50 unique task IDs.', '请输入 1–50 个不同的 Task ID。'], invalidIdsFormat: ['A task ID has an invalid format. Remove spaces and unsupported characters.', 'Task ID 格式无效，请去掉空格和不支持的字符。'],
  invalidBatch: ['Enter a valid batch ID.', '请输入有效的批次 ID。'], namespaceRequired: ['Choose an authorized Namespace.', '请选择授权的 Namespace。'],
  loginFailed: ['Sign-in did not complete. Please retry.', '登录未成功，请重试。'], passwordRequired: ['Enter your access password.', '请输入访问密码。'],
  logoutError: ['Sign-out failed: {error}', '退出失败：{error}'], pollError: ['Updates paused: {error} The displayed results remain available.', '查询中断：{error} 已显示的结果仍可查看。'],
  askRequired: ['Enter a question to investigate.', '请输入要调查的问题。'], questionTooLong: ['The question cannot exceed 16,000 characters.', '问题不能超过 16000 个字符。'],
  missingAnswer: ['The service did not return an answer.', '服务未返回回答内容。'],
  pipelineStages: ['Investigation stages', '调查阶段'], stepSelection: ['Select tasks', '选择任务'], stepMapping: ['Map executions', '执行映射'],
  stepCollection: ['Collect logs', '日志采集'], stepRules: ['Analyze with rules', '规则分析'], stepNarrative: ['LLM explanation', 'LLM 解释'],
  stageReady: ['Ready', '已就绪'], stageRunning: ['In progress', '进行中'], stageWaiting: ['Waiting', '等待中'], stageFailed: ['Failed', '失败'],
  stageIncomplete: ['Incomplete', '不完整'], stageUnverified: ['Unverified', '未验证'], stageBlocked: ['Blocked', '受阻'],
  stageUnknown: ['Not recorded', '未记录'], stageEmpty: ['No logs', '无日志'], stageSkipped: ['Skipped', '已跳过'], stageNotConnected: ['Not connected', '尚未接入'],
  diagnosticAuthTitle: ['Log collection blocked: server-side Grafana authentication failed', '日志采集受阻：服务端 Grafana 认证失败'],
  diagnosticForbiddenTitle: ['Log collection blocked: Grafana denied access', '日志采集受阻：Grafana 拒绝访问'],
  diagnosticSelectionTitle: ['Task selection failed', '任务选择失败'], diagnosticSourceTitle: ['Log collection failed or stopped', '日志采集失败或已停止'],
  diagnosticNoLogsTitle: ['No logs were collected; failure analysis has no log evidence', '未采集到日志，无法进行有依据的失败分析'],
  diagnosticEvidenceTitle: ['Evidence is insufficient for a meaningful failure classification', '证据不足，尚不能形成有意义的失败分类'],
  diagnosticPartialAnalysisTitle: ['Failure analysis is still incomplete for some tasks', '部分任务的失败分析仍不完整'],
  diagnosticPartialAnalysisReason: ['Analysis is incomplete for {incomplete} / {total} tasks because evidence or parsing is insufficient. Available signatures describe observations, not a shared root cause.', '{incomplete} / {total} 个任务因证据或解析不足，失败分析尚未完成；已有签名描述观测现象，不能据此认定共同根因。'],
  diagnosticPartialAnalysisUnknown: ['The report marks analysis as incomplete, but does not identify the affected task count out of {total} tasks. Review evidence and parsing gaps; this is not a shared-root-cause conclusion.', '报告标记分析不完整，但尚未明确 {total} 个任务中受影响的任务数量；请查看证据与解析缺口，这不代表共同根因结论。'],
  diagnosticMappingTitle: ['Logs are available, but final executions remain unverified', '已有日志，但最终执行仍未验证'],
  diagnosticProgressTitle: ['Investigation stage status', '调查阶段状态'], diagnosticReadyTitle: ['Review the evidence and analysis scope', '请结合证据与分析范围查看结果'],
  diagnosticAuthReason: ['GRAFANA_HTTP_401: the server log query was rejected. Signing into Grafana in your browser does not authenticate the Holmes server.', 'GRAFANA_HTTP_401：服务端日志查询被拒绝；浏览器已登录 Grafana，不代表 Holmes 服务端已通过认证。'],
  diagnosticForbiddenReason: ['GRAFANA_HTTP_403: the server does not have permission to read these logs.', 'GRAFANA_HTTP_403：服务端没有读取这些日志的权限。'],
  diagnosticProcessedReason: ['{processed} / {total} tasks were processed. This is processing progress, not a count of successful collections or verified diagnoses.', '已处理 {processed} / {total} 个任务；这是处理进度，不代表采集成功数或已验证诊断数。'],
  diagnosticZeroLogsReason: ['The batch collected 0 log events. Rule-based analysis cannot establish a log-backed failure cause.', '本批次实际采集日志为 0 条，规则分析无法据此确定失败原因。'],
  diagnosticLogCoverage: ['Tasks with collected logs: {matched} / {total}; no matching logs: {noMatches} / {total}. Log matches do not establish final-attempt ownership.', '日志命中任务：{matched} / {total}；未匹配到日志：{noMatches} / {total}。日志命中不代表最终执行归属已验证。'],
  diagnosticLegacyErrors: ['Execution-error events appear for {count} tasks, but this older report did not extract their error summaries. Its saved clusters and error counts are unchanged; run a new analysis to apply the updated rules.', '{count} 个任务已记录执行报错事件，但旧报告未提取异常摘要。保存的簇与错误计数保持原值；新建分析可使用更新后的规则。'],
  diagnosticMappingReason: ['Final executions, execution windows, Prompt mappings, and retry ownership are not verified. Task-ID matches remain candidate evidence.', '最终执行、实际执行时间窗、Prompt 映射与重试归属尚未验证；仅按任务 ID 匹配的日志属于候选证据。'],
  diagnosticLlmReason: ['No LLM explanation was requested for this batch (NOT_REQUESTED).', '本批次尚未请求 LLM 解释（NOT_REQUESTED）。'],
  diagnosticNext: ['Next: {action}', '下一步：{action}'],
  actionAuth: ['Check the Holmes server Grafana read credential and its validity, then create a new batch. Reopening this historical report will not recollect logs.', '检查 Holmes 服务端 Grafana 只读凭据及有效性，修复后新建批次；重新打开旧报告不会重新采集日志。'],
  actionForbidden: ['Check the Holmes server permissions for the selected data source and Namespace, then create a new batch.', '检查 Holmes 服务端对所选数据源与 Namespace 的读取权限，修复后新建批次。'],
  actionSelection: ['Check the task source, selection window, and reported selection limitations before starting another batch.', '检查任务来源、选择时间窗与任务选择限制，再新建批次。'],
  actionCollection: ['Review the collection error or budget, confirm the authorized log scope and time range, and then start a new batch.', '检查采集错误或预算，确认授权日志范围与查询时间窗，再新建批次。'],
  actionMapping: ['Obtain execution and retry records, map Prompt IDs to executions, and collect logs within the verified execution windows.', '补齐执行与重试记录，建立 Prompt 与执行映射，并按已验证的执行时间窗采集日志。'],
  actionEvidence: ['Resolve the collection and association gaps below before interpreting failure categories.', '先解决下方采集与归属缺口，再解读失败分类。'],
  actionProgress: ['Follow the stage status below; task-processing completion alone does not establish a root cause.', '按下方阶段状态继续核实；任务处理完成本身不能证明根因。'],
  selectionStepDetail: ['{total} selected; {processed} processed. Selection completeness is shown separately.', '已选 {total} 个，已处理 {processed} 个；选择是否完整单独标记。'],
  selectionStepIncomplete: ['{total} tasks were selected, but task selection is incomplete.', '已选中 {total} 个任务，但任务选择不完整。'],
  mappingStepVerified: ['All returned task results provide a final execution ID without an unverified-mapping flag.', '所有已返回任务结果均提供最终执行 ID，且没有执行映射未验证标记。'],
  mappingStepUnverified: ['Task identity is known; final execution and retry ownership are not established.', '已知任务身份，尚未建立最终执行与重试归属。'],
  mappingStepUnknown: ['This report does not establish execution mapping.', '本报告未确认执行映射。'],
  collectionStepDetail: ['{logs} events collected in the batch. See explicit per-task collection status below.', '本批次已采集 {logs} 条日志；逐任务采集结果见下方显式状态。'],
  collectionStepLegacy: ['{logs} events collected in the batch. This report has no per-task collection status.', '本批次已采集 {logs} 条日志；此报告未记录逐任务采集状态。'],
  collectionStepUnknown: ['The report does not record a batch event count or per-task collection outcome.', '报告未记录批次日志数量或逐任务采集结果。'],
  rulesStepBlocked: ['No usable associated log evidence supports a failure classification. INCOMPLETE describes an evidence gap.', '没有可用的关联日志证据支持失败分类；INCOMPLETE 表示证据缺口。'],
  rulesStepCandidate: ['Rules processed {processed} task results; signatures describe observations, not proven root causes.', '规则已处理 {processed} 个任务结果；签名描述观测现象，不代表已证明的根因。'],
  rulesStepWaiting: ['Waiting for task results and usable evidence.', '等待任务结果与可用证据。'],
  narrativeStepUnknown: ['This report does not record whether an LLM explanation ran.', '本报告未记录 LLM 解释是否执行。'],
  narrativeStepRecorded: ['Reported narrative status: {status}.', '报告中的模型解释状态：{status}。'],
  clusterUnavailable: ['No meaningful failure categories yet', '暂时无法生成有意义的失败分类'],
  clusterUnavailableDetail: ['All primary signatures are INCOMPLETE and no associated log evidence is available. This is an evidence-coverage result, not a shared failure cause.', '全部主簇均为 INCOMPLETE，且没有可用的关联日志证据。这表示取证覆盖不足，不代表共同失败原因。'],
  clusterUnavailableCount: ['INCOMPLETE: {count} / {total} selected tasks. Task details remain available below.', 'INCOMPLETE：{count} / {total} 个选中任务；下方保留完整任务明细。'],
  collectionStatus: ['Log collection', '日志采集'], collectionCollected: ['Collected', '已采集'], collectionNoMatches: ['No matches', '未匹配到日志'],
  collectionIncomplete: ['Partially collected', '采集不完整'], collectionFailed: ['Collection failed', '采集失败'], collectionSkipped: ['Collection skipped', '已跳过采集'],
  collectionLegacy: ['Not recorded; see batch diagnosis', '未记录，见批次诊断'], collectionEventCount: ['{count} log events', '{count} 条日志'],
  collectionLimitations: ['Collection limitations', '采集限制'], collectionLegacyDetail: ['This report has no per-task collection outcome. Batch-level failures cannot be assigned to every task automatically.', '此报告没有逐任务采集结果；不能把批次级失败自动归到每个任务。']
};
const QUALITY_NAMES = {
  INCOMPLETE_INPUT: ['Log collection is incomplete', '日志采集不完整'], INCOMPLETE_ATTEMPT_WINDOW: ['The attempt window is incomplete', '执行时间窗不完整'],
  AMBIGUOUS_ATTEMPT: ['The attempt cannot be determined uniquely', '无法唯一判定执行次数'], AMBIGUOUS_INSTANCE: ['Instance association is ambiguous', '实例归属存在歧义'],
  ID_CONFLICT: ['Conflicting task IDs were rejected', '已拒绝冲突的任务 ID'], CROSS_NAMESPACE_REJECTED: ['Cross-Namespace logs were rejected', '已拒绝跨 Namespace 的日志'],
  INSTANCE_MISMATCH: ['Logs from a different instance were excluded', '已排除实例不匹配的日志'], OUTSIDE_ATTEMPT_WINDOW: ['Logs outside the attempt window were excluded', '已排除执行时间窗之外的日志'],
  MISSING_FINAL_ATTEMPT: ['Final attempt is missing', '缺少最终执行记录'], MISSING_POD_UID: ['Pod UID is missing', '日志缺少 Pod UID'], MISSING_RESTART: ['Container restart identity is missing', '日志缺少容器重启标识'],
  MISSING_CONTAINER: ['Container identity is missing', '日志缺少容器标识'], PARSE_ERROR: ['Some log payloads could not be safely parsed', '部分日志格式无法安全解析'], CLOCK_SKEW_MARGIN: ['Some associations used the clock-skew margin', '部分关联使用了时钟偏差余量'],
  MULTIPLE_FAILURE_SIGNATURES: ['The final attempt has multiple failure signatures', '最终执行存在多个失败签名'], CONFLICTING_FAILURE_REASON: ['The task failure reason conflicts with the logs', '任务终态原因与日志存在冲突'],
  DUPLICATE_AMBIGUITY: ['Same-time identical events cannot be confirmed as duplicates', '同一时刻相同日志的重复性无法确认'], SOURCE_EVENT_ID_CONFLICT: ['A source event ID has conflicting content', '源日志事件 ID 内容冲突'],
  SIGNATURE_FROM_TASK_FAILURE_REASON: ['Signature comes from the task snapshot failure reason', '签名来自任务快照中的失败原因'], QUERY_SCOPED_NAMESPACE: ['Namespace is bounded by the query scope', 'Namespace 由查询范围限定'],
  MULTILINE_SAME_STREAM: ['Stack continuations were joined only within one log stream', '堆栈续行仅在同一日志流内拼接'], REPRESENTATIVE_EVIDENCE_LIMIT: ['Only bounded representative evidence is retained', '仅保留有限代表证据'],
  EVIDENCE_EXCERPT_TRUNCATED: ['Long evidence was shortened to excerpts', '长证据已裁剪为摘录'], TIMELINE_TRUNCATED: ['The timeline retains representative opening and closing events', '时间线已保留首尾关键事件'],
  TASK_STATUS_FROM_SNAPSHOT: ['Failure status comes from a task snapshot', '失败状态来自任务快照'], LOG_SOURCE_FILE_FIXTURE: ['This batch uses file logs', '本批次使用文件日志'],
  TASK_STATUS_FROM_SCHEDULER_API: ['Task status comes from the scheduler API observation', '任务状态来自调度器 API 的观测记录'],
  TASK_ATTEMPT_UNVERIFIED: ['Task-to-attempt ownership is not verified', '任务与执行的归属尚未验证'],
  UNVERIFIED_PROMPT_ID: ['A prompt ID was observed beside the exact task ID; its attempt mapping is not verified', '精确任务 ID 日志中观测到 Prompt ID，但尚未验证其执行映射'],
  PROMPT_ATTEMPT_MAPPING_UNVERIFIED: ['Prompt-to-attempt mapping is not verified', 'Prompt 与执行的映射尚未验证'],
  EXECUTION_WINDOW_UNVERIFIED: ['The actual execution window is not verified', '实际执行时间窗尚未验证'],
  RETRY_MEMBERSHIP_UNVERIFIED: ['Retry ownership is not verified', '重试归属尚未验证'],
  TASK_FAILURE_FINALITY_UNVERIFIED: ['Final failure and pending-retry status are not verified', '最终失败及是否存在待执行重试尚未验证'],
  SCHEDULER_CREATED_TIME_SELECTION: ['Scheduler tasks were selected by creation time, not failure time', '调度器任务按创建时间选择，并非按失败时间'],
  SCHEDULER_NON_ATOMIC_PAGINATION: ['Scheduler pages are not an atomic snapshot; task status can change during selection', '调度器分页不是原子快照，选择期间任务状态可能变化'],
  TASK_SELECTION_INCOMPLETE: ['Task selection is incomplete; counts cover only selected tasks', '任务选择不完整，统计仅覆盖实际选中的任务'],
  TASK_SELECTION_FAILED: ['Loading tasks from the scheduler failed', '从调度器加载任务失败'],
  SCHEDULER_HTTP_401: ['Scheduler API rejected authentication (HTTP 401)', '调度器 API 身份认证失败（HTTP 401）'],
  SCHEDULER_HTTP_403: ['Scheduler API denied access (HTTP 403)', '调度器 API 拒绝访问（HTTP 403）'],
  SCHEDULER_HTTP_502: ['Scheduler API gateway error (HTTP 502)', '调度器 API 网关错误（HTTP 502）'],
  SCHEDULER_HTTP_503: ['Scheduler API is unavailable (HTTP 503)', '调度器 API 暂不可用（HTTP 503）'],
  FEWER_FAILED_TASKS_THAN_REQUESTED: ['Fewer failed tasks are available than requested', '范围内失败任务少于请求数量'], COLLECTION_BUDGET_EXHAUSTED: ['The collection budget was exhausted', '采集达到批次预算'],
  CONTEXT_BUDGET_EXHAUSTED: ['The context collection budget was exhausted', '上下文补取达到预算'], CONTEXT_STREAM_LIMIT: ['The context-stream limit was reached', '相关日志流数量达到补取上限'],
  LOG_QUERY_FAILED: ['Log query failed', '日志查询失败'], ATTEMPT_OUTSIDE_QUERY_WINDOW: ['The query does not cover the complete attempt window', '查询范围未覆盖完整执行时间窗'],
  SOURCE_UNAVAILABLE_FOR_REMAINDER: ['The log source was unavailable for the remaining tasks; collection stopped', '日志来源对后续任务不可用，采集已停止'],
  GRAFANA_HTTP_401: ['Grafana rejected the log query (HTTP 401). Check the server-side read credential.', 'Grafana 日志查询未通过认证（HTTP 401），请检查服务端只读凭据。'],
  GRAFANA_HTTP_403: ['Grafana denied log access (HTTP 403). Check the server-side read permissions.', 'Grafana 拒绝日志访问（HTTP 403），请检查服务端读取权限。'],
  NO_RAW_EVENTS_TO_ARCHIVE: ['There are no collected events to archive', '没有可归档的采集日志'], RAW_ARCHIVE_UNAVAILABLE: ['The log archive is unavailable', '本批次日志归档不可用'],
  STORAGE_LIMIT: ['Report storage reached its capacity limit', '报告存储达到容量上限'], INTERNAL_OR_SOURCE_ERROR: ['A service or log-source operation failed', '服务或日志来源处理失败']
};
const STATE_KEYS = {QUEUED: 'queued', RUNNING: 'running', COMPLETED: 'completed', PARTIAL: 'partial', FAILED: 'failed'};
const SIGNATURE_KEYS = {CUDA_OOM: 'sigCuda', OOM: 'sigOom', TIMEOUT: 'sigTimeout', HTTP_404: 'sigHttp404', EXECUTION_ERROR: 'sigExecutionError', UNKNOWN: 'sigUnknown', AMBIGUOUS: 'sigAmbiguous', INCOMPLETE: 'sigIncomplete'};
let language = 'en';
try { if (localStorage.getItem('holmes-language') === 'zh') language = 'zh'; } catch (_) { /* 语言偏好不是必需存储。 */ }
const uiMessages = new Map();
function t(key, variables = {}) {
  const pair = TEXT[key];
  let text = pair ? pair[language === 'zh' ? 1 : 0] : key;
  return text.replace(/\{(\w+)\}/g, (_, name) => variables[name] instanceof Error ? errorText(variables[name]) : String(variables[name] ?? ''));
}
function uiError(key, variables = {}) { const error = new Error(key); error.i18nKey = key; error.variables = variables; return error; }
function errorText(error) { return t(error?.i18nKey || 'genericError', error?.variables || {}); }
function applyLanguage() {
  const messages = new Map(uiMessages);
  document.documentElement.lang = language; document.title = 'Holmes'; $('language').value = language;syncLanguageButtons();
  document.querySelectorAll('[data-i18n]').forEach((node) => { node.textContent = t(node.dataset.i18n); });
  for (const [attribute, dataset] of [['placeholder', 'i18nPlaceholder'], ['aria-label', 'i18nAria']])
    document.querySelectorAll(`[data-${attribute === 'placeholder' ? 'i18n-placeholder' : 'i18n-aria'}]`).forEach((node) => node.setAttribute(attribute, t(node.dataset[dataset])));
  if (state.config) renderConfigMessages();
  if (state.report) renderReport(state.report);
  $('login-submit').textContent = t(state.loggingIn ? 'loggingIn' : 'login');
  $('analysis-submit').textContent = t(state.submitting ? 'creating' : 'startAnalysis');
  $('ask-progress').textContent = state.asking ? t('investigating') : '';
  for (const [id, value] of messages) { uiMessages.set(id, value); renderMessage(id, value); }
}
// 语言按钮与兼容下拉框使用同一状态，按下状态供辅助技术和样式共同使用。
function syncLanguageButtons() {
  document.querySelectorAll('[data-language]').forEach(button=>button.setAttribute('aria-pressed',String(button.dataset.language===language)));
}
// 切换语言时重绘当前结果，保留已选批次与只读证据，不发起任何请求。
function setLanguage(value) {
  language=value==='zh'?'zh':'en';
  try{localStorage.setItem('holmes-language',language);}catch(_){/* 语言偏好存储不可用时仍立即切换。 */}
  applyLanguage();
}
function renderMessage(id, value) {
  $(id).textContent = value instanceof Error ? errorText(value) : t(value.key, value.variables);
  $(id).hidden = false;
}

function element(tag, className, text) {
  const value = document.createElement(tag);
  if (className) value.className = className;
  if (text !== undefined && text !== null) value.textContent = String(text);
  return value;
}
function showError(id, value, variables = {}) {
  if (!value) { uiMessages.delete(id); $(id).textContent = ''; $(id).hidden = true; return; }
  const message = value instanceof Error ? value : {key: value, variables};
  uiMessages.set(id, message); renderMessage(id, message);
}
function list(value) { return Array.isArray(value) ? value : []; }
function count(value) { const number = Number(value); return Number.isFinite(number) && number >= 0 ? Math.floor(number) : 0; }
function dateText(value) {
  if (!value) return t('notProvided');
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? t('notProvided') : date.toLocaleString(language === 'zh' ? 'zh-CN' : 'en-US');
}
function localInput(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  const pad = (number) => String(number).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth()+1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}.${String(date.getMilliseconds()).padStart(3,'0')}`;
}
function bytesText(value) {
  const bytes = count(value);
  return bytes < 1024 ? `${bytes} B` : bytes < 1048576 ? `${(bytes/1024).toFixed(1)} KiB` : `${(bytes/1048576).toFixed(1)} MiB`;
}
function qualityText(value) { return QUALITY_NAMES[value]?.[language === 'zh' ? 1 : 0] || t('technicalFlag'); }
function signatureText(value) { return SIGNATURE_KEYS[value] ? `${value} · ${t(SIGNATURE_KEYS[value])}` : String(value || 'UNKNOWN'); }
function rememberBatch(id) { try { sessionStorage.setItem(BATCH_KEY,id); } catch (_) { /* 批次恢复存储不可用时仍可继续操作。 */ } }
function rememberedBatch() { try { return sessionStorage.getItem(BATCH_KEY)||''; } catch (_) { return ''; } }
function stopPolling() {
  clearTimeout(state.timer);state.timer=null;state.generation+=1;state.narrativeRequest=null;
  state.automaticRequest=null;state.submitting=false;updateAutomaticButton();
}
function cancelRequests() { for (const controller of state.controllers) controller.abort(); state.controllers.clear(); }
function progress(value, maximum, label) {
  const outer=element('div','progress flex-grow-1 cluster-progress'); outer.setAttribute('role','progressbar');
  outer.setAttribute('aria-label',label); outer.setAttribute('aria-valuemin','0'); outer.setAttribute('aria-valuemax',String(maximum)); outer.setAttribute('aria-valuenow',String(value));
  const bar=element('div','progress-bar'); bar.style.width=`${maximum>0?Math.min(value/maximum,1)*100:0}%`; outer.append(bar); return outer;
}
function technicalDetails(values) {
  const detail=element('details','small text-secondary mt-2 technical-wrap'); detail.append(element('summary','',t('technicalDetails')));
  const items=element('ul','mt-2 mb-0'); for (const value of values) items.append(element('li','',value)); detail.append(items); return detail;
}
async function api(path,options={},timeout=25000,isCurrent=()=>true) {
  const controller=new AbortController(); state.controllers.add(controller); const timer=setTimeout(()=>controller.abort(),timeout);
  try {
    const response=await fetch(path,{credentials:'same-origin',cache:'no-store',...options,
      headers:{Accept:'application/json',...(options.body?{'Content-Type':'application/json'}:{}),...options.headers},signal:controller.signal});
    let body={}; try { body=await response.json(); } catch (_) { if(response.ok) throw uiError('invalidResponse'); }
    if(!body||typeof body!=='object') body={};
    if(!response.ok) {
      if(response.status===401&&path!=='/auth/login'&&isCurrent()) {state.authenticationRequired=true; showLogin('loginExpired');}
      const keys={400:'badRequest',401:path==='/auth/login'?'wrongPassword':'unauthorized',404:'notFound',422:'unprocessable',429:'tooMany',503:'unavailableError'};
      const raw=typeof body.error==='string'?body.error:'';
      let key=keys[response.status]||'httpError';
      if(response.status===400) {
        if(/requested_count|任务数量/.test(raw)) key='invalidTaskCount';
        else if(/时间范围|ISO 8601/.test(raw)) key='invalidTimes';
        else if(/namespace/i.test(raw)) key='namespaceRequired';
        else if(/task_id.*格式/.test(raw)) key='invalidIdsFormat';
      }
      const error=uiError(key,{status:response.status}); error.status=response.status; throw error;
    }
    return body;
  } catch(error) {
    if(error.name==='AbortError') throw uiError('timeout');
    if(error instanceof TypeError) throw uiError('network');
    throw error;
  } finally { clearTimeout(timer); state.controllers.delete(controller); }
}
function showLogin(message='') {
  stopPolling();cancelRequests(); state.authenticated=false;state.config=null;state.report=null;state.batchId='';state.selectedTask='';
  uiMessages.clear();updateAutomaticButton();
  $('login-view').hidden=!state.authenticationRequired; $('app-view').hidden=true; $('navigation').hidden=true; $('logout').hidden=true;
  $('password').value='';$('login-submit').disabled=false;$('login-submit').textContent=t('login');
  $('report-section').hidden=true;$('task-detail').hidden=true;$('ask-result').hidden=true;$('question').value='';$('task-ids').value='';
  $('logs-view').hidden=false;$('ask-view').hidden=true;
  document.querySelectorAll('[data-view]').forEach((item)=>{const active=item.dataset.view==='logs';item.classList.toggle('active',active);if(active)item.setAttribute('aria-current','page');else item.removeAttribute('aria-current');});
  for(const id of ['clusters','observed-clusters','tasks-body','archive-parts','task-detail-content','narrative-content','error-samples','pending-tasks','answer','trace']) $(id).replaceChildren();
  $('report-advanced').open=false;$('narrative-section').open=false;$('form-advanced').open=false;
  $('log-narrative').checked=false;$('log-narrative').disabled=true;$('narrative-submit').disabled=true;$('narrative-content').hidden=true;
  $('observed-section').hidden=true;
  showError('login-error',message);
}
async function enterApp() {
  state.authenticated=true;$('login-view').hidden=true;$('app-view').hidden=false;$('navigation').hidden=false;$('logout').hidden=!state.authenticationRequired;
  showError('global-notice','');
  try {
    const config=await api('/api/config');if(!state.authenticated)return;state.config=config;configure(config);
    const batch=rememberedBatch();if(UUID.test(batch)){$('restore-id').value=batch;await openBatch(batch,'restore-error');}
  } catch(error) {if(state.authenticated){showError('global-notice','configError',{error});$('analysis-submit').disabled=true;$('ask-submit').disabled=true;}}
}
function configure(config) {
  const defaults=config.defaults||{},snapshot=config.snapshot||{};
  state.authenticationRequired=config.authentication_required===true;$('logout').hidden=!state.authenticationRequired;
  $('source').value=config.log_source||'';$('source-current').textContent=config.log_source||t('notProvided');$('namespace').replaceChildren();
  for(const name of list(config.namespaces)){const option=element('option','',name);option.value=name;$('namespace').append(option);}
  if(list(config.namespaces).includes(snapshot.namespace))$('namespace').value=snapshot.namespace;
  $('requested-count').value=count(defaults.requested_count)||20;$('from').value=localInput(defaults.from);$('to').value=localInput(defaults.to);
  $('raw-archive').checked=false;
  // 旧控件仅为历史页面兼容保留；自动入口不读取它们，也不进行表单校验。
  $('log-narrative').disabled=true;$('log-narrative').checked=false;
  for(const id of ['namespace','requested-count','task-ids','from','to','mode-latest','mode-explicit','raw-archive']){$(id).required=false;$(id).disabled=true;}
  $('legacy-request-inputs').hidden=true;updateAutomaticButton();
  const askEnabled=config.capabilities?.ask===true;$('ask-submit').disabled=!askEnabled;$('question').disabled=!askEnabled;
  $('footer').textContent=`Holmes · ${config.version||'0.1.0'}`;
  renderConfigMessages();switchMode();
}
function renderConfigMessages() {
  const config=state.config,snapshot=config.snapshot||{},askEnabled=config.capabilities?.ask===true,scheduler=config.task_source==='scheduler_api';
  $('data-badge').textContent=t(config.demo?'demoBadge':config.data_mode==='file'?'fileBadge':'grafanaBadge');
  $('data-badge').className='badge text-bg-light border text-secondary fw-normal';
  $('timezone-hint').textContent=t('timezone',{zone:Intl.DateTimeFormat().resolvedOptions().timeZone||'UTC'});
  $('snapshot-notice').hidden=snapshot.ready===true;
  $('snapshot-notice').textContent=t(scheduler?'schedulerMissing':'snapshotMissing');
  $('latest-mode-label').textContent=t(scheduler?'schedulerLatest':'latestFailed');
  $('source-time-hint').hidden=!scheduler;$('source-time-hint').textContent=scheduler?t('schedulerTimeHint'):'';
  $('submit-hint').textContent=!snapshot.ready?t('snapshotHint'):!scheduler?t('automaticSnapshotPolicy'):'';
  // 主入口只显示简短摘要；完整发送范围仍由折叠详情保留。
  const model=config.log_narrative||{};
  $('automatic-model-summary').textContent=!logNarrativeEnabled(config)?t('automaticRulesSummary'):model.demo===true?t('automaticDemoSummary')
    :t('automaticModelSummary',{model:model.model||t('notProvided'),destination:model.destination||t('notProvided')});
  $('automatic-model-hint').textContent=logNarrativeEnabled(config)?t('automaticModelRequest',{scope:narrativeDestinationText(config)}):t('automaticRulesOnly');
  $('ask-notice').hidden=askEnabled&&!config.demo;$('ask-notice').textContent=!askEnabled?t('askUnavailable'):config.demo?t('askDemo'):'';
  $('log-narrative-hint').textContent=narrativeDestinationText(config);
}
function switchMode() {
  const explicit=document.querySelector('input[name="selection-mode"]:checked').value==='explicit_task_ids';
  $('count-field').hidden=explicit;$('ids-field').hidden=!explicit;$('requested-count').required=false;$('task-ids').required=false;
}
function requestPayload() {
  if(!state.config)throw uiError('configNotLoaded');
  if(!$('namespace').value)throw uiError('namespaceRequired');
  const from=new Date($('from').value),to=new Date($('to').value);
  if(Number.isNaN(from.getTime())||Number.isNaN(to.getTime())||from>=to)throw uiError('invalidTimes');
  if(to-from>86400000)throw uiError('tooLongWindow');
  const mode=document.querySelector('input[name="selection-mode"]:checked').value,selection={mode,from:from.toISOString(),to:to.toISOString()};
  if(mode==='explicit_task_ids') {
    const ids=[...new Set($('task-ids').value.split(/[\s,，]+/).filter(Boolean))];
    if(!ids.length||ids.length>50)throw uiError('invalidIdsCount');
    if(ids.some(id=>!/^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/.test(id)))throw uiError('invalidIdsFormat');selection.task_ids=ids;
  } else {
    const requested=Number($('requested-count').value);if(!Number.isInteger(requested)||requested<1||requested>50)throw uiError('invalidTaskCount');selection.requested_count=requested;
  }
  return {source:state.config.log_source,namespace:$('namespace').value,selection,attempt_scope:'final_failed',raw_archive:$('raw-archive').checked?'required':'none',
    narrative:$('log-narrative').checked&&logNarrativeEnabled()?'llm':'none'};
}
function automaticAvailable() {
  return state.authenticated&&state.config?.snapshot?.ready===true&&state.config?.capabilities?.logs===true;
}
function updateAutomaticButton() {
  $('analysis-submit').disabled=state.submitting||!automaticAvailable();
  $('analysis-submit').textContent=t(state.submitting?'creating':'startAnalysis');
}
async function discoverErrors() {
  if(state.submitting||!automaticAvailable())return;
  showError('analysis-error','');
  const marker={generation:state.generation},isCurrent=()=>state.authenticated&&state.automaticRequest===marker&&state.generation===marker.generation;
  state.automaticRequest=marker;state.submitting=true;updateAutomaticButton();
  try {
    // 采样范围、任务数量、来源与模型策略均由服务端决定，禁止发送历史表单值。
    const report=await api('/api/log-investigations/automatic',{method:'POST',body:'{}'},25000,isCurrent);
    if(!isCurrent())return;
    if(!report||!UUID.test(report.batch_id)||!Array.isArray(report.tasks))throw uiError('invalidResponse');
    stopPolling();showError('narrative-error','');state.batchId=report.batch_id;state.selectedTask='';state.report=report;rememberBatch(report.batch_id);$('task-detail').hidden=true;
    $('report-advanced').open=false;$('narrative-section').open=false;renderReport(report);
    const generation=state.generation;if(needsBatchPolling(report))state.timer=setTimeout(()=>fetchBatch(generation),1800);
    $('report-section').scrollIntoView({behavior:'smooth',block:'start'});
  }catch(error){if(isCurrent())showError('analysis-error',error);}
  finally{if(state.automaticRequest===marker){state.automaticRequest=null;state.submitting=false;updateAutomaticButton();}}
}
async function openBatch(id,errorId='restore-error') {
  showError(errorId,'');if(!UUID.test(id)){showError(errorId,'invalidBatch');return;}
  stopPolling();showError('narrative-error','');state.batchId=id;state.selectedTask='';state.report=null;$('report-section').hidden=true;$('task-detail').hidden=true;
  $('report-advanced').open=false;$('narrative-section').open=false;
  const generation=state.generation;$('restore-submit').disabled=true;
  try {await fetchBatch(generation,true);if(generation===state.generation&&state.authenticated)rememberBatch(id);}
  catch(error){if(state.authenticated&&generation===state.generation)showError(errorId,error);}finally{$('restore-submit').disabled=false;}
}
async function fetchBatch(generation,propagate=false) {
  const batch=state.batchId,isCurrent=()=>generation===state.generation&&state.authenticated&&batch===state.batchId;
  if(!isCurrent())return;
  try {
    const report=await api(`/api/log-investigations/${encodeURIComponent(batch)}`,{},25000,isCurrent);
    if(!isCurrent())return;
    if(!report||report.batch_id!==batch||!Array.isArray(report.tasks))throw uiError('invalidResponse');
    state.report=report;$('poll-error').hidden=true;showError('poll-error-text','');renderReport(report);
    clearTimeout(state.timer);state.timer=null;if(needsBatchPolling(report))state.timer=setTimeout(()=>fetchBatch(generation),1800);
  }catch(error){if(!isCurrent())return;if(propagate)throw error;showError('poll-error-text','pollError',{error});$('poll-error').hidden=false;}
}
function logNarrativeEnabled(config=state.config) {return config?.capabilities?.log_narrative===true&&config?.log_narrative?.enabled===true;}
function narrativeDestinationText(config=state.config) {
  if(!logNarrativeEnabled(config))return t('narrativeUnavailable');
  const settings=config.log_narrative;
  return t(settings.demo===true?'narrativeDemoTransfer':'narrativeTransfer',{model:settings.model||t('notProvided'),destination:settings.destination||t('notProvided')});
}
function needsBatchPolling(report) {return !TERMINAL.has(report.state)||NARRATIVE_ACTIVE.has(report.narrative_status);}
function narrativeState(report) {
  const states={PENDING:['narrativePending','narrativeWaitingDetail','stageWaiting','neutral'],RUNNING:['narrativeRunning','narrativeRunningDetail','stageRunning','active'],
    AVAILABLE:['narrativeAvailable','narrativeAvailableDetail','stageReady','success'],SKIPPED_NO_EVIDENCE:['narrativeSkipped','narrativeNoEvidenceDetail','stageSkipped','warning'],
    DISABLED:['narrativeDisabled','narrativeDisabledDetail','stageNotConnected','neutral'],FAILED:['narrativeFailed','narrativeFailedDetail','stageFailed','danger'],
    INTERRUPTED:['narrativeInterrupted','narrativeInterruptedDetail','stageIncomplete','warning']};
  if(report.narrative_status==='NOT_REQUESTED')return {label:logNarrativeEnabled()?'narrativeConfigured':'narrativeNotRequested',
    detail:logNarrativeEnabled()?'narrativeReadyDetail':'narrativeNoneDetail',stage:'stageWaiting',tone:'neutral'};
  const values=states[report.narrative_status];
  return values?{label:values[0],detail:values[1],stage:values[2],tone:values[3]}:
    {label:'stageUnknown',detail:'narrativeStepUnknown',stage:'stageUnknown',tone:'neutral'};
}
async function requestNarrative() {
  const report=state.report,batch=state.batchId,generation=state.generation;
  if(!state.authenticated||!logNarrativeEnabled()||!report||report.batch_id!==batch||!TERMINAL.has(report.state)
      ||NARRATIVE_ACTIVE.has(report.narrative_status)||report.narrative_status==='AVAILABLE'||report.narrative_status==='SKIPPED_NO_EVIDENCE'||state.narrativeRequest)return;
  const request={batch,generation},isCurrent=()=>state.authenticated&&state.batchId===batch&&state.generation===generation&&state.narrativeRequest===request;
  state.narrativeRequest=request;clearTimeout(state.timer);state.timer=null;showError('narrative-error','');renderNarrative(report);
  try {
    await api(`/api/log-investigations/${encodeURIComponent(batch)}/narrative`,{method:'POST',body:JSON.stringify({narrative:'llm'})},25000,isCurrent);
    if(isCurrent())await fetchBatch(generation,true);
  }catch(error){if(isCurrent())showError('narrative-error',error);}
  finally{if(state.narrativeRequest===request){state.narrativeRequest=null;if(state.authenticated&&state.batchId===batch&&state.report)renderNarrative(state.report);}}
}
const COLLECTION_KEYS={COLLECTED:'collectionCollected',NO_MATCHES:'collectionNoMatches',INCOMPLETE:'collectionIncomplete',FAILED:'collectionFailed',SKIPPED:'collectionSkipped'};
function reportFlags(report) {
  return [...new Set([...list(report.quality_flags),...list(report.limitations),
    ...list(report.tasks).flatMap(task=>[...list(task.quality_flags),...list(task.collection_limitations)])].filter(value=>typeof value==='string'))];
}
function recordedCount(value) {return typeof value==='number'&&Number.isFinite(value)&&value>=0?Math.floor(value):null;}
function collectionCoverage(report) {
  const tasks=list(report.tasks),total=recordedCount(report.selected_count);
  // 覆盖率只来自完整的显式采集字段，不能用签名、时间线或批次日志数猜测旧报告。
  if(total===null||total===0||tasks.length!==total||tasks.some(task=>!Object.hasOwn(COLLECTION_KEYS,task.collection_status)
      ||recordedCount(task.collection_event_count)===null))return null;
  return {total,matched:tasks.filter(task=>task.collection_event_count>0).length,
    noMatches:tasks.filter(task=>task.collection_status==='NO_MATCHES'&&task.collection_event_count===0).length};
}
function hasAssociatedLogEvidence(report) {
  return list(report.evidence).some(item=>item&&typeof item.excerpt==='string'&&item.excerpt.trim())
    ||list(report.tasks).some(task=>hasTaskLogEvidence(task,report));
}
// 只用报告中明确记录的事实推导阶段；旧报告缺少逐任务采集状态时，不把全局错误摊给每个任务。
function reportDiagnostic(report) {
  const tasks=list(report.tasks),flags=reportFlags(report),total=count(report.selected_count),processed=count(report.processed_count);
  const terminal=TERMINAL.has(report.state),logs=recordedCount(report.retrieved_event_count),evidence=hasAssociatedLogEvidence(report);
  const statuses=tasks.map(task=>task.collection_status),explicitCollection=tasks.length>0&&statuses.every(value=>Object.hasOwn(COLLECTION_KEYS,value));
  const auth=flags.includes('GRAFANA_HTTP_401'),forbidden=flags.includes('GRAFANA_HTTP_403');
  const selectionFailed=report.selection_status==='FAILED'||flags.includes('TASK_SELECTION_FAILED');
  const selecting=report.selection_status==='PENDING';
  const mappingUnverified=flags.some(flag=>['TASK_ATTEMPT_UNVERIFIED','EXECUTION_WINDOW_UNVERIFIED','PROMPT_ATTEMPT_MAPPING_UNVERIFIED','RETRY_MEMBERSHIP_UNVERIFIED','MISSING_FINAL_ATTEMPT'].includes(flag))
    ||tasks.some(task=>task.association_scope==='TASK_ONLY');
  const mappingVerified=!mappingUnverified&&total>0&&tasks.length===total&&tasks.every(task=>typeof task.final_attempt_id==='string'&&task.final_attempt_id.trim());
  const collectionFailed=auth||forbidden||statuses.includes('FAILED')||flags.includes('LOG_QUERY_FAILED');
  const collectionIncomplete=report.collection_incomplete===true||statuses.includes('INCOMPLETE');
  const clusters=list(report.clusters),allIncomplete=tasks.length>0&&tasks.every(task=>task.signature==='INCOMPLETE')
    ||clusters.length>0&&clusters.every(cluster=>cluster.signature==='INCOMPLETE');
  const incompleteAnalysisCount=tasks.filter(task=>task.signature==='INCOMPLETE'||task.analysis_status==='NOT_PROCESSED').length;
  const analysisIncomplete=report.attribution_incomplete===true||incompleteAnalysisCount>0;
  const analysisDetail=incompleteAnalysisCount>0?'diagnosticPartialAnalysisReason':'diagnosticPartialAnalysisUnknown';
  const noClassification=allIncomplete&&!evidence,zeroLogs=logs===0;
  let title='diagnosticProgressTitle',action='actionProgress',tone='neutral';
  if(auth){title='diagnosticAuthTitle';action='actionAuth';tone='danger';}
  else if(forbidden){title='diagnosticForbiddenTitle';action='actionForbidden';tone='danger';}
  else if(selectionFailed){title='diagnosticSelectionTitle';action='actionSelection';tone='danger';}
  else if(collectionFailed){title='diagnosticSourceTitle';action='actionCollection';tone='danger';}
  else if(zeroLogs&&terminal){title='diagnosticNoLogsTitle';action='actionCollection';tone='warning';}
  else if(noClassification){title='diagnosticEvidenceTitle';action='actionEvidence';tone='warning';}
  else if(mappingUnverified){title='diagnosticMappingTitle';action='actionMapping';tone='warning';}
  else if(analysisIncomplete&&logs!==0&&!selecting){title='diagnosticPartialAnalysisTitle';action='actionEvidence';tone='warning';}
  else if(terminal){title='diagnosticReadyTitle';tone=report.state==='COMPLETED'?'success':'warning';}
  const reasons=[{key:'diagnosticProcessedReason',variables:{processed,total}}];
  const coverage=collectionCoverage(report);if(coverage)reasons.push({key:'diagnosticLogCoverage',variables:coverage});
  const legacyErrors=tasks.filter(task=>hasLegacyExecutionError(task,report)).length;
  if(legacyErrors)reasons.push({key:'diagnosticLegacyErrors',variables:{count:legacyErrors}});
  if(auth)reasons.unshift({key:'diagnosticAuthReason'});else if(forbidden)reasons.unshift({key:'diagnosticForbiddenReason'});
  else if(selectionFailed){
    const selectionFlags=flags.filter(flag=>flag==='TASK_SELECTION_FAILED'||/^SCHEDULER_HTTP_\d+$/.test(flag));
    for(const flag of selectionFlags)reasons.unshift({key:flag,quality:true});
  }
  else if(collectionFailed){
    const sourceFlags=flags.filter(flag=>/^(LOG_QUERY_FAILED|GRAFANA_HTTP_\d+|SOURCE_UNAVAILABLE_FOR_REMAINDER|INTERNAL_OR_SOURCE_ERROR)$/.test(flag));
    for(const flag of sourceFlags)reasons.unshift({key:flag,quality:true});
  }
  if(zeroLogs&&(terminal||collectionFailed))reasons.push({key:'diagnosticZeroLogsReason'});
  if(mappingUnverified)reasons.push({key:'diagnosticMappingReason'});
  if(analysisIncomplete&&evidence)reasons.push({key:analysisDetail,variables:{incomplete:incompleteAnalysisCount,total}});
  if(report.narrative_status==='NOT_REQUESTED')reasons.push({key:logNarrativeEnabled()?'narrativeReadyDetail':'diagnosticLlmReason'});
  const stage=(label,status,detail,variables={},tone='neutral')=>({label,status,detail,variables,tone});
  const selection=stage('stepSelection',selectionFailed?'stageFailed':selecting?'stageRunning':report.selection_complete===false?'stageIncomplete':report.selection_status==='READY'||total>0?'stageReady':'stageUnknown',
    report.selection_complete===false&&!selecting?'selectionStepIncomplete':'selectionStepDetail',{total,processed},selectionFailed?'danger':selecting?'active':report.selection_complete===false?'warning':total>0?'success':'neutral');
  const mapping=stage('stepMapping',mappingUnverified?'stageUnverified':mappingVerified?'stageReady':selecting?'stageWaiting':'stageUnknown',
    mappingUnverified?'mappingStepUnverified':mappingVerified?'mappingStepVerified':'mappingStepUnknown',{},mappingUnverified?'warning':mappingVerified?'success':'neutral');
  let collectionState=collectionFailed?'stageFailed':collectionIncomplete?'stageIncomplete':explicitCollection&&statuses.every(value=>value==='SKIPPED')?'stageSkipped'
    :zeroLogs&&terminal?'stageEmpty':explicitCollection&&statuses.every(value=>['COLLECTED','NO_MATCHES'].includes(value))?'stageReady':logs>0&&terminal?'stageReady':terminal?'stageUnknown':selecting||report.state==='QUEUED'?'stageWaiting':'stageRunning';
  const collection=stage('stepCollection',collectionState,logs===null?'collectionStepUnknown':explicitCollection?'collectionStepDetail':'collectionStepLegacy',{logs},
    collectionFailed?'danger':collectionIncomplete||collectionState==='stageEmpty'?'warning':collectionState==='stageReady'?'success':collectionState==='stageRunning'?'active':'neutral');
  const rulesBlocked=!evidence&&(terminal||processed>0||collectionFailed);
  const rules=stage('stepRules',rulesBlocked?'stageBlocked':processed===0?'stageWaiting':analysisIncomplete?'stageIncomplete':terminal?'stageReady':'stageRunning',
    rulesBlocked?'rulesStepBlocked':processed===0?'rulesStepWaiting':analysisIncomplete?analysisDetail:'rulesStepCandidate',
    {processed,incomplete:incompleteAnalysisCount,total},rulesBlocked||processed>0&&analysisIncomplete?'warning':processed>0&&terminal?'success':processed>0?'active':'neutral');
  const narrativeView=narrativeState(report),narrative=stage('stepNarrative',narrativeView.stage,narrativeView.detail,{},narrativeView.tone);
  return {title,action,tone,reasons,steps:[selection,mapping,collection,rules,narrative],noClassification,neutralProgress:!evidence||collectionFailed};
}
function renderDiagnostic(report) {
  const diagnosis=reportDiagnostic(report),panel=$('batch-diagnostic');panel.className=`batch-diagnostic diagnostic-${diagnosis.tone} mb-4`;
  $('diagnostic-heading').textContent=t(diagnosis.title);
  $('diagnostic-reasons').replaceChildren(...diagnosis.reasons.map(reason=>element('li','technical-wrap',reason.quality?`${reason.key} · ${qualityText(reason.key)}`:t(reason.key,reason.variables))));
  $('diagnostic-next').textContent=t('diagnosticNext',{action:t(diagnosis.action)});
  $('pipeline-steps').replaceChildren(...diagnosis.steps.map((step,index)=>{
    const item=element('li','pipeline-step'),heading=element('div','small fw-semibold');heading.append(element('span','step-number',`${index+1}.`),element('span','',t(step.label)));
    item.append(heading,element('span',`step-status ${step.tone}`,t(step.status)),element('p','text-secondary',t(step.detail,step.variables)));return item;
  }));
  return diagnosis;
}
// 进度只使用服务端已记录的阶段和整数计数，不从日志质量推断耗时或百分比。
function deriveInvestigationProgress(report) {
  const number=value=>Number.isSafeInteger(value)&&value>=0?value:null,
    total=number(report.selected_count),processed=number(report.processed_count),stage=report.stage,
    terminal=TERMINAL.has(report.state),selectionFailed=report.selection_status==='FAILED',
    selecting=!selectionFailed&&(report.selection_status==='PENDING'||(stage==='SELECTING_TASKS'&&report.selection_status!=='READY'&&!terminal)),
    fixed=!selecting&&!selectionFailed&&(report.selection_status==='READY'||(total!==null&&total>0)),
    validCounts=fixed&&total!==null&&processed!==null&&processed<=total,
    tasksDone=validCounts&&processed===total,empty=report.selection_status==='READY'&&terminal&&total===0&&report.selection_complete!==false,
    stopped=stage==='STOPPED'||report.state==='FAILED'||selectionFailed,
    organizing=stage==='SUMMARIZING'||report.narrative_status==='RUNNING'||(report.narrative_status==='PENDING'&&terminal),
    modelIncomplete=terminal&&tasksDone&&['FAILED','INTERRUPTED'].includes(report.narrative_status),
    percent=validCounts&&total>0?Math.floor(processed/total*100):null;
  let phase='unknown',heading='progressUnknown',caption='progressUnknownCaption',tone='neutral';
  if(organizing){phase='organizing';heading=report.narrative_status==='RUNNING'||stage==='SUMMARIZING'?'progressModelRunning':'progressModelWaiting';caption='progressModelCaption';tone='active';}
  else if(stopped){phase='stopped';heading='progressFailed';caption='progressStoppedCaption';tone='danger';}
  else if(selecting){phase='selecting';heading='progressSelect';caption='progressSelectCaption';tone='active';}
  else if(stage==='ARCHIVING'&&!terminal){phase='archiving';heading='progressArchive';caption='progressArchiveCaption';tone='active';}
  else if(modelIncomplete){phase='model-incomplete';heading='progressModelIncomplete';caption='progressModelIncompleteCaption';tone='warning';}
  else if(empty&&terminal){phase='empty';heading='progressEmpty';caption='progressEmptyCaption';tone=report.state==='PARTIAL'?'warning':'success';}
  else if(terminal&&validCounts&&tasksDone){phase='finished';heading=report.state==='PARTIAL'?'progressPartial':'progressFinished';caption=report.state==='PARTIAL'?'progressPartialCaption':'progressCountScope';tone=report.state==='PARTIAL'?'warning':'success';}
  else if(terminal){phase='stopped';heading=report.state==='PARTIAL'?'progressPartial':'progressUnknown';caption=validCounts?'progressStoppedCaption':'progressUnknownCaption';tone='warning';}
  else if(stage==='QUEUED'||stage==='SELECTING_TASKS'){phase='queued';heading='progressQueued';caption=fixed?'progressCountScope':'progressSelectCaption';tone='active';}
  else if(['COLLECTING','ANALYZING'].includes(stage)){phase='collecting';heading='progressCollect';caption='progressCountScope';tone='active';}
  else if(tasksDone&&total>0){phase='organizing';heading='progressOrganize';caption='progressCountScope';tone='active';}
  const countText=selecting?'loadingTasks':empty?'progressNoTasks':percent!==null?'taskProgressText':'progressCountUnknown',
    variables={processed,total,percent},stepStates=['pending','pending','pending','pending'];
  if(fixed)stepStates[0]='complete';
  if(selecting)stepStates[0]=terminal?'stopped':'active';
  if(selectionFailed)stepStates[0]='stopped';
  if(fixed&&!empty){
    if(tasksDone){stepStates[1]='complete';stepStates[2]='complete';}
    else if(!terminal){stepStates[1]=['collecting','archiving'].includes(phase)?'active':'pending';stepStates[2]=processed>0&&['collecting','archiving'].includes(phase)?'active':'pending';}
    else{stepStates[1]='stopped';stepStates[2]=processed>0?'stopped':'pending';}
  }
  if(phase==='empty'){stepStates[1]='complete';stepStates[2]='complete';}
  if(['organizing','archiving'].includes(phase))stepStates[3]='active';
  else if(['finished','empty'].includes(phase))stepStates[3]='complete';
  else if(['stopped','model-incomplete'].includes(phase)&&fixed)stepStates[3]='stopped';
  // 任务处理百分比与模型、归档阶段分开；空样本和未知计数不显示虚假的 100%。
  return {phase,heading,caption,tone,selecting,fixed,empty,total,processed,percent,countText,variables,stepStates,
    barPercent:['organizing','archiving'].includes(phase)?null:percent,
    indeterminate:['organizing','archiving'].includes(phase)||(!terminal&&percent===null&&!empty),
    statusKey:phase==='queued'||(phase==='selecting'&&report.state==='QUEUED')||(phase==='organizing'&&report.narrative_status==='PENDING'&&stage!=='SUMMARIZING')
      ?'progressWaiting':['organizing','archiving','selecting'].includes(phase)||(!terminal&&percent===null)?'progressInProgress':null};
}
// 故障原因仅由白名单安全码决定；不显示供应商正文、异常原文或未知代码。
function narrativeFailure(report) {
  if(!['FAILED','INTERRUPTED'].includes(report.narrative_status))return null;
  const keys={NARRATIVE_INPUT_INVALID:'narrativeFailureInput',NARRATIVE_INPUT_BUDGET:'narrativeFailureInputBudget',
    NARRATIVE_PROVIDER_TIMEOUT:'narrativeFailureProviderTimeout',NARRATIVE_PROVIDER_NETWORK:'narrativeFailureNetwork',NARRATIVE_PROVIDER_RESPONSE:'narrativeFailureResponse',
    NARRATIVE_TOOL_PROTOCOL:'narrativeFailureToolProtocol',NARRATIVE_TOOL_NO_EVIDENCE:'narrativeFailureNoEvidence',NARRATIVE_TOOL_CONTEXT_LIMIT:'narrativeFailureContext',NARRATIVE_TOOL_BUDGET:'narrativeFailureToolBudget',
    NARRATIVE_OUTPUT_JSON:'narrativeFailureJson',NARRATIVE_OUTPUT_SCHEMA:'narrativeFailureSchema',NARRATIVE_OUTPUT_CITATION:'narrativeFailureCitation',NARRATIVE_OUTPUT_INCOMPLETE:'narrativeFailureOutput',
    NARRATIVE_GENERATION_FAILED:'narrativeFailureUnrecorded',NARRATIVE_TIMEOUT:'narrativeFailureTimeout',NARRATIVE_REPORT_LIMIT:'narrativeFailureReportLimit',
    NARRATIVE_INTERRUPTED:'narrativeFailureInterrupted',NARRATIVE_RULES_INTERRUPTED:'narrativeFailureRulesInterrupted',NARRATIVE_DISABLED:'narrativeFailureDisabled'},
    raw=typeof report.narrative_error_code==='string'?report.narrative_error_code:report.narrative_diagnostic?.code,
    http=typeof raw==='string'?/^NARRATIVE_PROVIDER_HTTP_([1-5][0-9]{2})$/.exec(raw):null;
  let key=typeof raw==='string'&&Object.hasOwn(keys,raw)?keys[raw]:null,status=null;
  if(http){status=Number(http[1]);key={401:'narrativeFailureAuth',403:'narrativeFailurePermission',429:'narrativeFailureRate',400:'narrativeFailureRequest'}[status]
    ||(status>=500?'narrativeFailureServer':'narrativeFailureHttp');}
  return {key:key||'narrativeFailureUnknown',code:key?raw:'',status};
}
// 同时渲染可见阶段、任务口径进度和四个里程碑，未知进度移除数值ARIA属性。
function renderInvestigationProgress(report) {
  const model=deriveInvestigationProgress(report),panel=$('investigation-progress'),bar=$('batch-progress'),outer=bar.parentElement,
    status=model.statusKey?t(model.statusKey):model.percent!==null?t('progressPercent',model.variables):t(model.countText,model.variables);
  panel.dataset.tone=model.tone;$('progress-stage').textContent=t(model.heading);$('progress-status').textContent=status;
  const failure=narrativeFailure(report),caption=$('progress-caption');
  caption.textContent=failure?t(failure.key,{status:failure.status}):t(model.caption);
  caption.classList.toggle('visually-hidden',!failure);caption.classList.toggle('progress-detail',Boolean(failure));
  $('batch-progress-text').textContent=t(model.countText,model.variables);
  outer.setAttribute('aria-label',t(model.barPercent===null?model.heading:'taskProgress'));outer.setAttribute('aria-valuemin','0');outer.setAttribute('aria-valuemax','100');outer.setAttribute('aria-valuetext',`${t(model.heading)} · ${status}`);
  if(model.barPercent===null)outer.removeAttribute('aria-valuenow');else outer.setAttribute('aria-valuenow',String(model.barPercent));
  panel.setAttribute('aria-busy',String(!TERMINAL.has(report.state)||model.phase==='organizing'));
  bar.className=`progress-bar${model.indeterminate?' progress-indeterminate':''}${['warning','danger','neutral'].includes(model.tone)?' progress-neutral':''}`;
  bar.style.width=model.indeterminate?'':`${model.barPercent??0}%`;
  $('progress-steps').replaceChildren(...['progressStepSelect','progressStepCollect','progressStepAnalyze','progressStepOrganize'].map((label,index)=>{
    const status=model.stepStates[index],item=element('li',`progress-step is-${status}`);
    if(status==='active')item.setAttribute('aria-current','step');
    item.setAttribute('aria-label',`${t(label)} · ${t({complete:'progressStepComplete',active:'progressStepActive',pending:'progressStepPending',stopped:'progressStepStopped'}[status])}`);
    item.append(element('span','progress-step-index',status==='complete'?'✓':String(index+1)),element('span','progress-step-label',t(label)));return item;
  }));
  return model;
}
function renderReport(report) {
  const progress=renderInvestigationProgress(report),selecting=progress.selecting;
  $('report-section').hidden=false;$('batch-state').textContent=t(progress.heading);
  $('batch-state').className=`badge border fw-normal ${progress.tone==='success'?'text-success bg-success-subtle':['warning','danger'].includes(progress.tone)?'text-warning-emphasis bg-warning-subtle':'text-secondary text-bg-light'}`;
  $('batch-id').textContent=t('batchLabel',{id:report.batch_id});$('restore-id').value=report.batch_id;
  const total=count(report.selected_count),diagnosis=renderDiagnostic(report);
  $('report-summary').textContent=selecting?t('selectionPending'):t('reportSummary',{total});
  $('report-counts').textContent=t('reportCounts',{logs:count(report.retrieved_event_count),errors:count(report.observed_error_event_count)});
  const selection=report.request?.selection,iso=value=>typeof value==='string'&&!Number.isNaN(new Date(value).getTime())?new Date(value).toISOString():t('notProvided');
  $('report-sampling-window').textContent=t('samplingWindow',{from:iso(selection?.from),to:iso(selection?.to)});
  renderEngineerView(report,diagnosis);renderQuality(report);renderNarrative(report);renderClusters(report);renderObservedClusters(report);renderTasks(report);renderArchive(report);if(state.selectedTask)renderTaskDetail(state.selectedTask);
  // 名单加载阶段仅保留主进度；结果文字仍生成，固定名单或已有真实分组后恢复显示。
  const active=!TERMINAL.has(report.state),unfixed=active&&!progress.fixed,hasGroups=list(engineerView(report)?.groups).length>0;
  $('engineer-summary').hidden=unfixed;$('error-samples-section').hidden=unfixed&&!hasGroups;
  // 模型尚未开始时收起面板，但明确的模型失败或请求错误始终保留可见入口。
  const modelError=['FAILED','INTERRUPTED'].includes(report.narrative_status)||!$('narrative-error').hidden||!$('narrative-safe-error').hidden;
  $('narrative-section').hidden=active&&report.narrative_status!=='RUNNING'&&progress.phase!=='organizing'&&!modelError;
  $('batch-state').hidden=(active||progress.phase==='organizing')&&!modelError;

}
function engineerView(report) {
  const view=report?.engineer_view;
  return view?.version==='engineer-v1'&&view.summary&&typeof view.summary==='object'&&Array.isArray(view.groups)&&Array.isArray(view.pending_tasks)?view:null;
}
function displayCount(value) {const number=recordedCount(value);return number===null?'—':number;}
function groupTasks(group,report) {
  const refs=new Set(list(group.task_refs).filter(value=>typeof value==='string'));
  return list(report.tasks).filter(task=>refs.has(task.task_ref));
}
function groupEvidence(group,report) {
  const refs=new Set(list(group.task_refs));
  return [...new Set(list(group.representative_evidence_ids).filter(value=>typeof value==='string'))].slice(0,2)
    .map(id=>narrativeEvidence(id,report)).filter(linked=>linked&&refs.has(linked.task.task_ref));
}
function groupNarrativeFindings(group,report) {
  if(report?.narrative_status!=='AVAILABLE'||!report.narrative||typeof report.narrative!=='object')return [];
  const refs=new Set(list(group.task_refs)),representatives=new Set(list(group.representative_evidence_ids)),text=value=>typeof value==='string'?value.trim():'';
  const signature=evidence=>text(evidence.failure_signature)||(['execution_error','execution_failed','execution_failure','task_failed','task_failure'].includes(text(evidence.event).toLowerCase())?'EXECUTION_ERROR':'');
  // 服务端分组包含异常消息，不能仅凭同一任务、签名或节点给模型发现贴组。
  // 代表证据之外仅接纳与锚点结构化消息完全相同的证据；宁可留在全局，也不模糊归类。
  const identity=evidence=>{
    const node=text(evidence.node_type),component=text(evidence.component)||text(evidence.container)||'unknown',message=text(evidence.error_summary)||text(evidence.exception_message);
    return message?JSON.stringify([signature(evidence),node&&node!=='unknown'?node:component,text(evidence.exception_type),message.replace(/\s+/g,' ')]):'';
  };
  const uniqueEvidence=id=>{
    if(list(report.evidence).filter(item=>item?.evidence_id===id).length!==1)return null;
    const linked=narrativeEvidence(id,report);
    return linked&&refs.has(linked.task.task_ref)&&linked.evidence.historical_attempt!==true&&linked.evidence.context_only!==true?linked:null;
  };
  const anchors=[...representatives].filter(id=>typeof id==='string').map(uniqueEvidence).filter(linked=>linked&&signature(linked.evidence)===text(group.signature)
    &&text(linked.evidence.node_type)===text(group.node_type)&&text(linked.evidence.exception_type)===text(group.exception_type));
  if(!anchors.length)return [];
  const anchorIds=new Set(anchors.map(linked=>linked.evidence.evidence_id)),identities=new Set(anchors.map(linked=>identity(linked.evidence)).filter(Boolean));
  return list(report.narrative.findings).flatMap(finding=>{
    if(!finding||typeof finding!=='object'||(!text(finding.title)&&!text(finding.explanation)))return [];
    const supplied=list(finding.evidence_ids);
    if(!supplied.length||supplied.some(id=>typeof id!=='string'))return [];
    const ids=[...new Set(supplied)],evidence=ids.map(uniqueEvidence);
    if(evidence.some(linked=>!linked||!anchorIds.has(linked.evidence.evidence_id)&&!identities.has(identity(linked.evidence))))return [];
    return [{finding,evidence}];
  });
}
function groupNarrativeContent(group,report) {
  const findings=groupNarrativeFindings(group,report);if(!findings.length)return null;
  const content=element('div','small mb-3');content.append(element('h5','sample-label',t('groupModelAnalysis')));
  for(const {finding,evidence} of findings){
    if(typeof finding.title==='string'&&finding.title.trim())content.append(element('p','fw-semibold mb-1',finding.title));
    if(typeof finding.explanation==='string'&&finding.explanation.trim())content.append(element('p','mb-1',finding.explanation));
    const links=element('div','d-flex flex-wrap gap-3 mb-2');
    for(const {evidence:item} of evidence){
      const reference=element('button','btn btn-link btn-sm p-0 technical-wrap text-start',t('narrativeReference',{id:item.evidence_id}));reference.type='button';
      reference.addEventListener('click',()=>openNarrativeEvidence(item.evidence_id,report.batch_id));links.append(reference);
    }
    content.append(links);
  }
  content.append(element('p','text-secondary mb-0',t('modelInterpretationScope')));return content;
}
function structuredEvidenceSummary(evidence) {
  if(typeof evidence.error_summary==='string'&&evidence.error_summary.trim())return evidence.error_summary;
  const fields=[evidence.event,evidence.failure_signature,evidence.exception_type].filter(value=>typeof value==='string'&&value.trim());
  return [...fields,t('legacySummaryMissing')].join(' · ');
}
function openTaskDetails(taskRef,batch) {
  if(!state.authenticated||state.batchId!==batch||state.report?.batch_id!==batch||!list(state.report.tasks).some(task=>task.task_ref===taskRef))return false;
  state.selectedTask=taskRef;renderTaskDetail(taskRef);revealTaskDetails();$('task-detail').scrollIntoView({behavior:'smooth',block:'start'});return true;
}
function revealTaskDetails() {
  // 证据可能位于已关闭的高级详情中；显式跳转时展开所有 details 祖先。
  for(let parent=$('task-detail').parentElement;parent;parent=parent.parentElement)if(parent.tagName?.toLowerCase()==='details')parent.open=true;
}
function renderEngineerView(report,diagnosis=reportDiagnostic(report)) {
  const view=engineerView(report),cards=$('error-samples'),pending=$('pending-tasks');cards.replaceChildren();pending.replaceChildren();
  $('engineer-alert').hidden=diagnosis.tone!=='danger';$('engineer-alert').textContent=diagnosis.tone==='danger'?`${t(diagnosis.title)}${language==='zh'?'。':' '}${t(diagnosis.action)}`:'';
  $('error-samples-limit').hidden=true;$('pending-section').hidden=true;
  const progress=deriveInvestigationProgress(report);
  if(progress.selecting||report.selection_status==='FAILED'||(progress.total===0&&report.selection_complete===false)){
    const ended=TERMINAL.has(report.state);$('engineer-summary').textContent=t(ended?'selectionUnavailable':'selectionPending');
    cards.append(element('p','sample-placeholder text-secondary small',t(ended?'selectionUnavailable':'samplesSelecting')));return;
  }
  if(!view){
    const message=t(TERMINAL.has(report.state)?'engineerViewMissing':'engineerViewWaiting');
    $('engineer-summary').textContent=message;cards.append(element('p',`text-secondary small${TERMINAL.has(report.state)?'':' sample-placeholder'}`,message));return;
  }
  const summary=view.summary;
  $('engineer-summary').textContent=progress.empty?t('samplesNoTasks'):progress.total===0?t(TERMINAL.has(report.state)?'selectionUnrecorded':'samplesZeroPending'):t('engineerSummary',{processed:displayCount(summary.processed_task_count),selected:displayCount(summary.selected_task_count),
    errors:displayCount(summary.error_task_count),pending:displayCount(summary.pending_task_count),groups:displayCount(summary.group_count)});
  if(!view.groups.length)cards.append(element('p',`text-secondary${!TERMINAL.has(report.state)||progress.phase==='organizing'?' sample-placeholder':''}`,t(progress.phase==='empty'?'samplesNoTasks':progress.phase==='organizing'?'samplesOrganizing':progress.phase==='queued'?'samplesQueued':TERMINAL.has(report.state)?'samplesEmpty':'samplesWaiting')));
  // 仅按服务端明确返回的样本影响数排序，不在前端推断错误分组。
  for(const group of [...view.groups].sort((a,b)=>(recordedCount(b.task_count)??-1)-(recordedCount(a.task_count)??-1))){
    const card=element('article','error-sample'),heading=element('div','d-flex flex-wrap justify-content-between align-items-start gap-2');
    heading.append(element('h4','h6 mb-1 technical-wrap',typeof group.title==='string'?group.title:signatureText(group.signature)),
      element('span','sample-task-count',t('affectedTasks',{count:displayCount(group.task_count)})));card.append(heading);
    const scope=[group.component?`${t('componentLabel')}: ${group.component}`:'',group.node_type?`${t('nodeLabel')}: ${group.node_type}`:'',typeof group.exception_type==='string'?group.exception_type:''].filter(Boolean);
    if(scope.length)card.append(element('p','small text-secondary technical-wrap mb-2',scope.join(' · ')));
    const owner=typeof group.suggested_owner_role==='string'?group.suggested_owner_role:t('notProvided');
    card.append(element('p','small mb-3',`${t('ownerRole')}: ${owner} · ${group.assignment_status==='UNASSIGNED'?t('unassigned'):t('notProvided')}`));
    const checks=list(group.recommendations).filter(value=>typeof value==='string').slice(0,3);
    if(checks.length){const items=element('ul','small mb-3');for(const check of checks)items.append(element('li','',check));card.append(element('h5','sample-label',t('recommendations')),items);}
    const model=groupNarrativeContent(group,report);if(model)card.append(model);
    const representatives=groupEvidence(group,report);
    for(const [index,linked]of representatives.entries()){
      const evidence=linked.evidence,block=element('div','sample-log');
      block.append(element('p','small text-secondary mb-1',`${t('representativeLog')} · ${dateText(evidence.event_time)} · ${evidence.pod||t('unknownPod')}`));
      const summary=Array.from(structuredEvidenceSummary(evidence));
      block.append(element('p','sample-message mb-2',summary.slice(0,300).join('')+(summary.length>300?'…':'')));
      const button=element('button','btn btn-outline-primary btn-sm',t('viewTaskEvidence'));button.type='button';
      button.addEventListener('click',()=>openNarrativeEvidence(evidence.evidence_id,report.batch_id));block.append(button);
      if(index===0)card.append(block);
      else{const more=element('details','small mb-3');more.append(element('summary','text-secondary',t(linked.task.task_ref!==representatives[0].task.task_ref?'anotherRepresentativeLog':'moreRepresentativeLog')),block);card.append(more);}
    }
    if(!representatives.length)card.append(element('p','small text-secondary',t('representativeUnavailable')));
    const footer=element('div','d-flex flex-wrap align-items-center gap-3'),download=element('button','btn btn-link btn-sm p-0 text-decoration-none',t('downloadSample'));download.type='button';
    download.addEventListener('click',()=>downloadEngineerSample(group.group_id,report.batch_id));footer.append(download);
    const tasks=groupTasks(group,report);
    if(tasks.length){const details=element('details','small'),listNode=element('div','sample-task-links mt-2');details.append(element('summary','',t('affectedTaskList')));
      for(const task of tasks){const button=element('button','btn btn-link btn-sm p-0 technical-wrap text-start',task.task_id);button.type='button';button.addEventListener('click',()=>openTaskDetails(task.task_ref,report.batch_id));listNode.append(button);}
      details.append(listNode);footer.append(details);}
    card.append(footer);
    if(typeof group.quality_hint==='string'&&group.quality_hint.trim()){
      const quality=element('details','small text-secondary mt-3');quality.append(element('summary','',t('qualityDetails')),element('p','mt-2 mb-0',group.quality_hint));card.append(quality);
    }
    cards.append(card);
  }
  if(view.truncated===true||view.groups.length>1){$('error-samples-limit').hidden=false;$('error-samples-limit').textContent=view.truncated===true
    ?t('groupsLimited',{displayed:displayCount(summary.displayed_group_count),total:displayCount(summary.group_count)}):t('groupOverlap');}
  const reasons={NO_LOG_MATCH:'pendingNoMatch',QUERY_FAILED:'pendingQueryFailed',QUERY_SKIPPED:'pendingQuerySkipped',NO_CLEAR_ERROR:'pendingNoError',LEGACY_NOT_RECORDED:'pendingLegacy'};
  $('pending-section').hidden=!view.pending_tasks.length;
  for(const item of view.pending_tasks){
    const row=element('div','pending-task'),description=element('div'),task=list(report.tasks).find(task=>task.task_ref===item.task_ref),type=schedulerFactText(task?.scheduler_facts?.task_type);
    description.append(element('div','small font-monospace technical-wrap',item.task_id||t('notProvided')));
    if(type)description.append(element('p','small text-secondary technical-wrap mb-0 mt-1',t('schedulerTaskType',{type})));
    description.append(element('p','small text-secondary mb-0 mt-1',t(reasons[item.reason_code]||'pendingUnknown')));row.append(description);
    if(list(report.tasks).some(task=>task.task_ref===item.task_ref)){
      const button=element('button','btn btn-link btn-sm text-nowrap',t('view'));button.type='button';button.addEventListener('click',()=>openTaskDetails(item.task_ref,report.batch_id));row.append(button);
    }
    pending.append(row);
  }
}
function engineerSampleText(group,report) {
  // 交付单只允许结构化字段；不复制 excerpt、原始 payload、配置对象或模型密钥。
  const value=text=>typeof text==='string'?text:'',lines=[value(group.title)||signatureText(group.signature),'',t('exportScope'),'',t('exportFacts'),
    `${t('failureSignature')}: ${value(group.signature)}`,`${t('componentLabel')}: ${value(group.component)}`,`${t('nodeLabel')}: ${value(group.node_type)}`,
    `${t('errorSummary')}: ${value(group.normalized_message)}`,`${t('affectedTasks',{count:displayCount(group.task_count)})}`,
    `${t('ownerRole')}: ${value(group.suggested_owner_role)} · ${group.assignment_status==='UNASSIGNED'?t('unassigned'):t('notProvided')}`,'',t('exportTasks')];
  for(const task of groupTasks(group,report)){
    const logical=value(task.task_id),scheduler=value(task.log_lookup?.scheduler_task_id)||value(task.scheduler_facts?.record_id);
    lines.push(logical);
    if(scheduler&&scheduler!==logical)lines.push(t('lookupTaskIdMapping',{logical,scheduler}));
    for(const window of list(task.log_lookup?.windows).slice(0,2))if(typeof window?.from==='string'&&typeof window?.to==='string')
      lines.push(`log_from: ${window.from} · log_to: ${window.to} · events: ${Number.isSafeInteger(window.returned_count)?window.returned_count:'—'}`);
  }
  lines.push('',t('representativeLog'));
  for(const {evidence} of groupEvidence(group,report)){
    lines.push(`${value(evidence.event_time)} · Pod ${value(evidence.pod)} · ${value(evidence.evidence_id)}`,structuredEvidenceSummary(evidence));
    if(value(evidence.observed_task_id))lines.push(`${t('observedTaskId')}: ${value(evidence.observed_task_id)}`);
    if(value(evidence.observed_prompt_id))lines.push(`${t('observedPromptId')}: ${value(evidence.observed_prompt_id)}`);
    if(value(evidence.content_sha256))lines.push(`SHA-256: ${value(evidence.content_sha256)}`);
    lines.push('');
  }
  const findings=groupNarrativeFindings(group,report);
  if(findings.length){
    lines.push(t('groupModelAnalysis'),t('modelInterpretationScope'));
    const cited=new Map();
    for(const {finding,evidence} of findings){
      lines.push(value(finding.title),value(finding.explanation));
      for(const linked of evidence){lines.push(`evidence_id: ${value(linked.evidence.evidence_id)}`);cited.set(linked.evidence.evidence_id,linked);}
      lines.push('');
    }
    lines.push(t('groupModelEvidence'));
    for(const {evidence,task} of cited.values()){
      lines.push(`evidence_id: ${value(evidence.evidence_id)}`,`Task ID: ${value(task.task_id)}`,`${value(evidence.event_time)} · Pod ${value(evidence.pod)}`,
        `${t('nodeLabel')}: ${value(evidence.node_type)}`,`${t('failureSignature')}: ${value(evidence.failure_signature)}`,structuredEvidenceSummary(evidence));
      for(const name of ['exception_type','exception_message','observed_task_id','observed_prompt_id'])if(value(evidence[name]))lines.push(`${name}: ${value(evidence[name])}`);
      if(value(evidence.content_sha256))lines.push(`SHA-256: ${value(evidence.content_sha256)}`);
      lines.push('');
    }
  }
  lines.push(t('qualityDetails'),value(group.quality_hint),'',t('recommendations'),...list(group.recommendations).filter(text=>typeof text==='string').slice(0,3).map(text=>`- ${text}`),'',
    t('exportSource'),`source: ${value(report.source)||t('notProvided')}`,`namespace: ${value(report.namespace)||t('notProvided')}`,
    `from: ${value(report.request?.selection?.from)||t('notProvided')}`,`to: ${value(report.request?.selection?.to)||t('notProvided')}`,
    `batch_id: ${value(report.batch_id)}`,`group_id: ${value(group.group_id)}`,
    ...groupEvidence(group,report).map(({evidence})=>`evidence_id: ${value(evidence.evidence_id)}`));
  return lines.join('\n');
}
function downloadEngineerSample(groupId,batch) {
  if(!state.authenticated||state.batchId!==batch||state.report?.batch_id!==batch)return false;
  const group=list(engineerView(state.report)?.groups).find(item=>item.group_id===groupId);if(!group)return false;
  const url=URL.createObjectURL(new Blob([engineerSampleText(group,state.report)],{type:'text/plain;charset=utf-8'})),link=element('a');
  link.href=url;link.download=`holmes-error-${String(groupId).replace(/[^A-Za-z0-9_-]/g,'_').slice(0,64)||'sample'}.txt`;
  document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);return true;
}
function narrativeEvidence(id,report=state.report) {
  if(typeof id!=='string')return null;
  const evidence=list(report?.evidence).find(item=>item&&item.evidence_id===id);
  if(!evidence)return null;
  const task=list(report.tasks).find(item=>item.task_ref===evidence.task_ref&&list(item.evidence_ids).includes(id));
  return task?{evidence,task}:null;
}
function openNarrativeEvidence(id,batch) {
  if(!state.authenticated||state.batchId!==batch||state.report?.batch_id!==batch)return false;
  const linked=narrativeEvidence(id);if(!linked)return false;
  state.selectedTask=linked.task.task_ref;renderTaskDetail(linked.task.task_ref);revealTaskDetails();
  const index=taskEvidence(linked.task).findIndex(item=>item.evidence_id===id),detail=$(`task-evidence-${index}`);
  if(detail){detail.open=true;detail.scrollIntoView({behavior:'smooth',block:'center'});}
  else $('task-detail').scrollIntoView({behavior:'smooth',block:'start'});
  return true;
}
function narrativeAnalysisContent(analysis) {
  if(!analysis||typeof analysis!=='object'||analysis.mode!=='TOOL_DRIVEN')return null;
  const number=value=>Number.isSafeInteger(value)&&value>=0?value:'—',content=element('div','narrative-analysis mb-3');
  content.append(element('p','small fw-semibold mb-2',t('narrativeToolSummary',{rounds:number(analysis.model_rounds),calls:number(analysis.tool_calls)})));
  const details=element('details','small'),items=element('ol','mt-2 mb-0');details.append(element('summary','text-secondary',t('narrativeToolSteps')));
  const tools={get_error_samples:'narrativeToolSamples',read_task_timeline:'narrativeToolTimeline',read_evidence:'narrativeToolEvidence',compare_tasks:'narrativeToolCompare'},
    statuses={OK:'narrativeToolOk',REJECTED:'narrativeToolRejected',TRUNCATED:'narrativeToolTruncated'};
  // 调用过程只展示白名单工具名、安全状态和计数，不读取或展示参数及原始返回值。
  for(const step of list(analysis.steps)){
    if(!step||typeof step!=='object')continue;
    const tool=typeof step.tool==='string'&&Object.hasOwn(tools,step.tool)?tools[step.tool]:'narrativeToolUnknown',
      status=typeof step.status==='string'&&Object.hasOwn(statuses,step.status)?statuses[step.status]:'narrativeToolStatusUnknown';
    items.append(element('li','mb-1',`${t(tool)} · ${t(status)} · ${t('narrativeToolEvidenceCount',{count:number(step.evidence_count)})}`));
  }
  if(!items.children.length)details.append(element('p','text-secondary mt-2 mb-0',t('narrativeToolNoSteps')));else details.append(items);
  content.append(details);return content;
}
function renderNarrative(report) {
  const view=narrativeState(report),busy=NARRATIVE_ACTIVE.has(report.narrative_status)||state.narrativeRequest?.batch===report.batch_id,
    available=report.narrative_status==='AVAILABLE',failure=narrativeFailure(report),button=$('narrative-submit'),content=$('narrative-content');
  $('narrative-status').textContent=t(state.narrativeRequest?.batch===report.batch_id?'narrativeSubmitting':view.label);
  $('narrative-status').className=`step-status ${view.tone}`;
  $('narrative-status-detail').textContent=(failure?t(failure.key,{status:failure.status}):t(view.detail))+(report.narrative_incomplete===true?` ${t('narrativeIncomplete')}`:'');
  $('narrative-destination').textContent=narrativeDestinationText();$('narrative-destination').hidden=available;
  button.hidden=!TERMINAL.has(report.state)||available||report.narrative_status==='SKIPPED_NO_EVIDENCE';
  button.disabled=busy||!state.authenticated||!logNarrativeEnabled();
  button.textContent=t(busy?'narrativeSubmitting':['FAILED','INTERRUPTED'].includes(report.narrative_status)?'retryNarrative':'generateNarrative');
  const code=failure?.code||'';
  $('narrative-safe-error').hidden=!code;$('narrative-safe-error').textContent=code?t('narrativeErrorCode',{code}):'';
  content.replaceChildren();content.hidden=!available;
  if(!available){
    // 失败诊断只投影轮数、调用数及工具统计；不枚举诊断对象或读取参数、服务响应正文。
    const diagnostic=report.narrative_diagnostic;
    if(failure&&diagnostic&&typeof diagnostic==='object'&&!Array.isArray(diagnostic)){
      const trace=narrativeAnalysisContent({mode:'TOOL_DRIVEN',model_rounds:diagnostic.model_rounds,tool_calls:diagnostic.tool_calls,steps:diagnostic.steps});
      if(trace){content.hidden=false;content.append(trace);}
    }
    return;
  }
  const narrative=report.narrative;
  if(!narrative||typeof narrative!=='object'||Array.isArray(narrative)){content.append(element('p','text-secondary',t('narrativeMissingContent')));return;}
  content.append(metadata([[t('narrativeModel'),typeof narrative.model==='string'?narrative.model:t('notProvided')],
    [t('narrativeGeneratedAt'),dateText(narrative.generated_at)]]));
  const analysis=narrativeAnalysisContent(narrative.analysis);if(analysis)content.append(analysis);
  if(narrative.input_truncated===true)content.append(element('p','alert alert-warning small',t('narrativeInputTruncated')));
  if(typeof narrative.summary==='string'&&narrative.summary.trim())content.append(element('h4','h6',t('narrativeSummary')),element('p','',narrative.summary));
  const findings=list(narrative.findings).filter(item=>item&&typeof item==='object');
  if(findings.length)content.append(element('h4','h6 mt-4',t('narrativeFindings')));
  for(const finding of findings){
    const block=element('section','narrative-finding small');
    if(typeof finding.title==='string')block.append(element('h5','h6',finding.title));
    if(typeof finding.explanation==='string')block.append(element('p','',finding.explanation));
    const links=element('div','narrative-evidence-links');
    for(const id of [...new Set(list(finding.evidence_ids).filter(value=>typeof value==='string'))]){
      if(narrativeEvidence(id,report)){
        const reference=element('button','btn btn-outline-secondary btn-sm technical-wrap',t('narrativeReference',{id}));reference.type='button';
        reference.addEventListener('click',()=>openNarrativeEvidence(id,report.batch_id));links.append(reference);
      }else links.append(element('span','text-secondary technical-wrap',t('narrativeReferenceMissing',{id})));
    }
    block.append(links);content.append(block);
  }
  for(const [field,label]of [['recommendations','narrativeRecommendations'],['limitations','narrativeLimitations']]){
    const values=list(narrative[field]).filter(value=>typeof value==='string');if(!values.length)continue;
    const items=element('ul','small');for(const value of values)items.append(element('li','',value));
    content.append(element('h4','h6 mt-4',t(label)),items);
  }
}
function renderQuality(report) {
  const values=[...new Set([...list(report.quality_flags),...list(report.limitations)])],known=values.filter(value=>QUALITY_NAMES[value]),unknown=values.filter(value=>!QUALITY_NAMES[value]);
  const items=known.map(value=>element('li','',qualityText(value)));
  if(report.selection_complete===false&&report.selection_status!=='PENDING'&&!values.includes('TASK_SELECTION_INCOMPLETE'))items.push(element('li','',t('selectionIncomplete')));
  if(['PARTIAL','FAILED'].includes(report.state)&&!values.length)items.push(element('li','',t('partialHint')));
  if(unknown.length)items.push(element('li','',t('technicalHint')));
  $('quality-list').replaceChildren(...items);$('quality-technical-list').replaceChildren(...unknown.map(value=>element('li','technical-wrap',value)));
  $('quality-technical').hidden=!unknown.length;$('quality-section').hidden=!items.length;
}
function renderClusters(report) {
  if(reportDiagnostic(report).noClassification){
    const box=element('div','cluster-unavailable mt-3');box.append(element('p','small fw-semibold mb-2',t('clusterUnavailable')),
      element('p','small text-secondary mb-2',t('clusterUnavailableDetail')),
      element('p','small text-secondary',t('clusterUnavailableCount',{count:list(report.tasks).filter(task=>task.signature==='INCOMPLETE').length||list(report.clusters).reduce((sum,cluster)=>sum+count(cluster.task_count),0),total:count(report.selected_count)})));
    $('clusters').replaceChildren(box);return;
  }
  renderClusterRows($('clusters'),list(report.clusters),count(report.selected_count));
}
function renderObservedClusters(report) {
  const clusters=list(report.observed_clusters);$('observed-section').hidden=!clusters.length;
  renderClusterRows($('observed-clusters'),clusters,count(report.selected_count));
}
function renderClusterRows(target,clusters,total) {
  target.replaceChildren();
  if(!clusters.length){target.append(element('p','text-secondary small my-3',t('waitingClusters')));return;}
  for(const cluster of clusters) {
    const row=element('div','row g-3 align-items-center py-3 border-bottom'),name=element('div','col-5 small technical-wrap',signatureText(cluster.signature));
    const meter=element('div','col-4'),label=t('clusterAria',{signature:cluster.signature,count:count(cluster.task_count),total});meter.append(progress(Math.min(count(cluster.task_count),total),Math.max(total,1),label));
    const ratio=typeof cluster.ratio==='number'&&Number.isFinite(cluster.ratio)?`${(cluster.ratio*100).toFixed(1)}%`:'—';
    row.append(name,meter,element('div','col-3 text-end text-secondary small',t('clusterCount',{count:count(cluster.task_count),ratio})));target.append(row);
  }
}
function taskEvidence(task,report=state.report) {
  const ids=list(task.evidence_ids);
  return list(report?.evidence).filter(item=>item&&item.task_ref===task.task_ref&&ids.includes(item.evidence_id));
}
function hasLegacyExecutionError(task,report=state.report) {
  if(task.association_scope!=='TASK_ONLY'||list(task.observed_signatures).length)return false;
  // 仅使用报告中明确记录的执行报错事件，不从任意正文或其他任务推断新签名。
  return [...list(task.timeline),...taskEvidence(task,report)].some(event=>event&&event.context_only!==true
    &&typeof event.event==='string'&&event.event.trim().toLowerCase()==='execution_error');
}
function emptyPatternText(task,report=state.report) {return t(hasLegacyExecutionError(task,report)?'legacyExecutionError':'noObservedPatterns');}
function evidenceContent(item) {
  const content=element('div'),summary=typeof item.error_summary==='string'?item.error_summary.trim():'';
  if(summary){
    content.append(element('p','fw-semibold mt-2 mb-1',t('errorSummary')),
      element('pre','log-excerpt bg-light border rounded p-3 my-2',summary));
  }
  if(typeof item.failure_signature==='string'&&item.failure_signature.trim())
    content.append(element('p','small technical-wrap mb-2',signatureText(item.failure_signature)));
  const excerpt=element('pre','log-excerpt bg-light border rounded p-3 my-2',item.excerpt||'');
  if(summary){const raw=element('details','small my-2');raw.append(element('summary','',t('rawEvidence')),excerpt);content.append(raw);}
  else content.append(excerpt);
  return content;
}
function hasTaskLogEvidence(task,report=state.report) {
  // 任务状态或错误签名本身不能证明采到了日志；只接受已关联证据或有正文的候选时间线事件。
  return taskEvidence(task,report).length>0||list(task.timeline).some(event=>event&&event.context_only!==true
    &&(event.association_scope==='TASK_ONLY'||event.candidate_only===true)&&typeof event.event_time==='string'
    &&Number.isFinite(Date.parse(event.event_time))&&typeof event.excerpt==='string'&&event.excerpt.trim().length>0);
}
function taskCollection(task) {
  const status=task.collection_status,known=Object.hasOwn(COLLECTION_KEYS,status);
  return {known,label:known?COLLECTION_KEYS[status]:'collectionLegacy',events:known?recordedCount(task.collection_event_count):null,
    limitations:known?list(task.collection_limitations).filter(value=>typeof value==='string'):[],
    tone:status==='FAILED'?'danger':status==='INCOMPLETE'||status==='NO_MATCHES'?'warning':status==='COLLECTED'?'success':'neutral'};
}
function collectionCell(task) {
  const outcome=taskCollection(task),cell=element('td','collection-cell');cell.append(element('span',`step-status ${outcome.tone}`,t(outcome.label)));
  if(outcome.events!==null)cell.append(element('div','small text-secondary mt-1',t('collectionEventCount',{count:outcome.events})));
  if(outcome.limitations.length)cell.append(element('div','small text-secondary technical-wrap mt-1',outcome.limitations.map(value=>QUALITY_NAMES[value]?qualityText(value):value).join(' · ')));
  return cell;
}
function renderTasks(report) {
  const tasks=list(report.tasks),selecting=report.stage==='SELECTING_TASKS'&&report.selection_status==='PENDING';
  $('tasks-body').replaceChildren();$('tasks-empty').hidden=tasks.length!==0;
  $('tasks-empty').textContent=t(selecting?'loadingTasks':report.selection_complete===false?'selectionUnavailable':TERMINAL.has(report.state)?'noTasks':'waitingTasks');
  for(const task of tasks) {
    const row=element('tr'),idCell=element('td'),id=element('span','task-id font-monospace d-block',task.task_id);id.title=task.task_id||'';idCell.append(id);
    // 证据状态优先于程序是否执行了分析，避免把 INCOMPLETE 显示成已完成。
    const status=task.analysis_status==='NOT_PROCESSED'?'notProcessed':task.signature==='INCOMPLETE'||task.analysis_status==='INCOMPLETE_EVIDENCE'?'incomplete':task.signature==='AMBIGUOUS'?'ambiguous':'analyzed';
    const action=element('button','btn btn-link btn-sm p-0 text-decoration-none',t('view'));action.type='button';action.setAttribute('aria-label',t('viewTask',{id:task.task_id}));
    action.addEventListener('click',()=>{state.selectedTask=task.task_ref;renderTaskDetail(task.task_ref);$('task-detail').scrollIntoView({behavior:'smooth',block:'start'});});
    const actionCell=element('td');actionCell.append(action);
    const signatureCell=element('td','task-signature font-monospace',task.signature||'UNKNOWN'),statusCell=element('td','',t(status)),taskOnly=task.association_scope==='TASK_ONLY';
    if(taskOnly){
      signatureCell.append(element('div','text-secondary small font-sans-serif mt-1',`${t('observedPatterns')}: ${list(task.observed_signatures).map(signatureText).join(' · ')||emptyPatternText(task,report)}`));
      statusCell.append(element('div','text-secondary small mt-1',t(hasTaskLogEvidence(task,report)?'candidateEvidence':'noCandidateEvidence')));
    }
    row.append(idCell,collectionCell(task),signatureCell,element('td','',taskOnly?t('attemptUnverified'):task.final_attempt_id||t('notProvided')),statusCell,actionCell);$('tasks-body').append(row);
  }
}
function metadata(entries) {
  const description=element('dl','row small mb-3');for(const[name,value]of entries)description.append(element('dt','col-4 col-md-2 text-secondary fw-normal',name),element('dd','col-8 col-md-10 technical-wrap',value));return description;
}
function schedulerFactText(value) {
  const text=typeof value==='string'?value.trim():typeof value==='number'&&Number.isFinite(value)?String(value):'';
  return text.length>160?`${text.slice(0,159)}…`:text;
}
function taskLookupContent(task) {
  const object=value=>value&&typeof value==='object'&&!Array.isArray(value)?value:null,
    facts=object(task.scheduler_facts),lookup=object(task.log_lookup),lines=[],
    iso=value=>typeof value==='string'&&Number.isFinite(Date.parse(value))?new Date(value).toISOString():t('notProvided'),
    count=value=>Number.isSafeInteger(value)&&value>=0?String(value):'—';
  const type=schedulerFactText(facts?.task_type),code=schedulerFactText(facts?.error_code);
  if(type)lines.push(t('schedulerTaskType',{type}));
  if(code)lines.push(t('schedulerErrorCode',{code}));
  if(typeof facts?.created_at==='string'||typeof facts?.updated_at==='string')
    lines.push(t('schedulerRecordTimes',{created:iso(facts.created_at),updated:iso(facts.updated_at)}));
  if(lookup){
    const strategies={AUTOMATIC_TASK_LIFECYCLE:'lookupAutomatic',BATCH_WINDOW:'lookupBatch',VERIFIED_ATTEMPT_WINDOW:'lookupVerified'},
      bases={...strategies,VERIFIED_STREAM_CONTEXT:'lookupStreamContext',AUTOMATIC_ID_FALLBACK:'lookupFallback'},
      statuses={MATCHED:'lookupMatched',NO_MATCH:'lookupNoMatch',QUERY_FAILED:'lookupFailed',INCOMPLETE:'lookupIncomplete'},
      mappings={TASK_ONLY:'lookupTaskIdOnly',TASK_ID_ONLY:'lookupTaskIdOnly',SOURCE_RECORD_TASK_ID:'lookupSourceRecordMapping',VERIFIED_ATTEMPTS:'lookupVerifiedMapping'},
      key=(table,value)=>typeof value==='string'&&Object.hasOwn(table,value)?table[value]:'notProvided';
    lines.push(t('lookupSummary',{strategy:t(key(strategies,lookup.strategy)),status:t(key(statuses,lookup.status)),count:count(lookup.query_count)}));
    const logical=typeof lookup.logical_task_id==='string'?schedulerFactText(lookup.logical_task_id):'',
      scheduler=typeof lookup.scheduler_task_id==='string'?schedulerFactText(lookup.scheduler_task_id):'';
    const mapping=lookup.mapping_status==='SOURCE_RECORD_TASK_ID'&&logical&&scheduler
      ?t('lookupTaskIdMapping',{logical,scheduler}):t(key(mappings,lookup.mapping_status));
    lines.push(t('lookupMapping',{mapping}));
    if(lookup.searched_all_pods===true)lines.push(t('lookupAllPods'));
    // 只展示实际返回的查询窗口和条数，不从批次总量推断旧报告或缺失字段。
    const windows=list(lookup.windows).filter(object);
    windows.slice(0,16).forEach((window,index)=>lines.push(t('lookupWindow',{index:index+1,from:iso(window.from),to:iso(window.to),
      basis:key(bases,window.basis)==='notProvided'?'':` · ${t(key(bases,window.basis))}`,
      count:count(window.returned_count),complete:t(window.complete===true?'lookupComplete':window.complete===false?'lookupPartial':'lookupCompletenessUnknown')})));
    if(windows.length>16)lines.push(t('lookupMoreWindows',{count:windows.length-16}));
    if(lookup.status==='NO_MATCH')lines.push(t('lookupNoExecutionClaim'));
  }
  if(!lines.length)return null;
  const content=element('section','small mb-3');content.append(element('h3','h6',t('taskLookupDetails')));
  lines.forEach(line=>content.append(element('p','text-secondary technical-wrap mb-1',line)));
  return content;
}
function renderTaskDetail(ref,override) {
  const task=override||list(state.report?.tasks).find(item=>item.task_ref===ref);if(!task){$('task-detail').hidden=true;return;}
  $('task-detail').hidden=false;showError('task-detail-error','');const content=$('task-detail-content');content.replaceChildren();
  const taskOnly=task.association_scope==='TASK_ONLY',evidence=taskEvidence(task),collection=taskCollection(task),lookup=taskLookupContent(task);
  if(lookup)content.append(lookup);
  const collectionNotice=element('div',`alert ${collection.tone==='danger'?'alert-danger':collection.tone==='warning'?'alert-warning':'alert-light border'} small`);
  collectionNotice.append(element('p','fw-semibold mb-1',`${t('collectionStatus')}：${t(collection.label)}${collection.events!==null?` · ${t('collectionEventCount',{count:collection.events})}`:''}`));
  if(collection.limitations.length)collectionNotice.append(element('p','technical-wrap mb-0',`${t('collectionLimitations')}：${collection.limitations.map(value=>QUALITY_NAMES[value]?qualityText(value):value).join(' · ')}`));
  if(!collection.known)collectionNotice.append(element('p','mb-0',t('collectionLegacyDetail')));
  const scopeDetails=element('details','small mb-3');scopeDetails.append(element('summary','text-secondary',t('qualityDetails')),collectionNotice);
  if(taskOnly)scopeDetails.append(element('p','text-secondary mt-2',t(hasTaskLogEvidence(task)?'taskOnlyNotice':'taskOnlyNoEvidenceNotice')));
  scopeDetails.append(metadata([['Task ID',task.task_id],[t('finalAttempt'),taskOnly?t('attemptUnverified'):task.final_attempt_id||t('notProvided')],[t('failureSignature'),signatureText(task.signature)],
    [t('qualityFlags'),list(task.quality_flags).map(qualityText).join(' · ')||t('noExtraFlags')]]));content.append(scopeDetails);
  if(taskOnly){
    const observed=element('div','small mb-3');observed.append(element('h3','h6',t('observedPatterns')));
    const patterns=list(task.observed_signatures),items=element('ul','mb-0');
    if(patterns.length)for(const value of patterns)items.append(element('li','',signatureText(value)));
    else items.append(element('li','text-secondary',emptyPatternText(task)));
    observed.append(items);content.append(observed);
  }
  const unknownFlags=list(task.quality_flags).filter(value=>!QUALITY_NAMES[value]);if(unknownFlags.length)scopeDetails.append(technicalDetails(unknownFlags));
  const refresh=element('button','btn btn-link btn-sm p-0 text-decoration-none mb-3',t('refreshTask'));refresh.type='button';
  refresh.addEventListener('click',async()=>{refresh.disabled=true;const batch=state.batchId;
    try{const response=await api(`/api/log-investigations/${encodeURIComponent(batch)}/tasks/${encodeURIComponent(ref)}`);if(state.authenticated&&state.batchId===batch&&state.selectedTask===ref)renderTaskDetail(ref,response.task||response);}
    catch(error){if(state.authenticated&&state.selectedTask===ref)showError('task-detail-error',error);}finally{refresh.disabled=false;}});content.append(refresh);
  const evidenceSection=element('div','border-top pt-3 mt-2');evidenceSection.append(element('h3','h6',t(taskOnly?'candidateEvidence':'keyEvidence')));
  if(!evidence.length)evidenceSection.append(element('p','text-secondary small',t(taskOnly?'noCandidateEvidence':'noEvidence')));
  evidence.forEach((item,index)=>{
    const detail=element('details','small my-3');detail.id=`task-evidence-${index}`;detail.open=index<2;
    const candidate=item.candidate_only===true||item.association_scope==='TASK_ONLY'||item.historical_attempt===null;
    const scopeLabel=candidate?` · ${t('candidateEvidence')} · ${t('attemptUnverified')}`:item.historical_attempt===true?` · ${t('historicalRetry')}`:'';
    const basis=item.association_basis==='TASK_ID_ONLY_ATTEMPT_UNVERIFIED'?t('taskOnlyBasis')
      :item.association_basis==='SOURCE_RECORD_TASK_ID_ATTEMPT_UNVERIFIED'?t('sourceRecordTaskBasis'):item.association_basis||t('noBasis');
    detail.append(element('summary','technical-wrap',`${dateText(item.event_time)} · ${item.pod||t('unknownPod')} / ${item.container||t('unknownContainer')}${scopeLabel}`),
      element('p','text-secondary small mt-2 mb-1 technical-wrap',`${item.evidence_id} · ${candidate?t('attemptUnverified'):item.attempt_id||t('noAttempt')} · ${basis}`),
      evidenceContent(item),element('p','text-secondary small technical-wrap',`SHA-256 ${item.content_sha256||t('notProvided')}`));
    const observedTask=typeof item.observed_task_id==='string'?schedulerFactText(item.observed_task_id):'';
    if(observedTask)detail.append(element('p','text-secondary small technical-wrap',`${t('observedTaskId')}: ${observedTask}`));
    if(item.observed_prompt_id)detail.append(element('p','text-secondary small technical-wrap',`${t('observedPromptId')}: ${item.observed_prompt_id}`));
    if(list(item.quality_flags).length)detail.append(element('p','text-secondary small',list(item.quality_flags).map(qualityText).join(' · ')));
    const unknown=list(item.quality_flags).filter(value=>!QUALITY_NAMES[value]);if(unknown.length)detail.append(technicalDetails(unknown));evidenceSection.append(detail);
  });content.append(evidenceSection);
  const timeline=element('details','border-top pt-3 mt-3 small');timeline.append(element('summary','',t('timeline',{count:list(task.timeline).length})));const items=element('ol','mt-3');
  for(const event of list(task.timeline)) {
    const item=element('li','mb-3'),time=element('time','text-secondary small',dateText(event.event_time));if(event.event_time)time.dateTime=event.event_time;
    const candidate=event.candidate_only===true||event.association_scope==='TASK_ONLY'||event.historical_attempt===null;
    item.append(time,element('div','',`${event.event||t('logEvent')} · ${event.pod||t('unknownPod')}${event.context_only?` · ${t('contextOnly')}`:''}${candidate?` · ${t('attemptUnverified')}`:event.historical_attempt===true?` · ${t('historicalAttempt')}`:''}`),evidenceContent(event));items.append(item);
  }
  if(!list(task.timeline).length)items.append(element('li','text-secondary',t('noTimeline')));timeline.append(items);content.append(timeline);
  if(list(task.raw_refs).length){const link=element('a','small d-block mt-3',t('relatedArchives',{count:list(task.raw_refs).length}));link.href='#archive-heading';content.append(link);}
}
function validDownload(value) {
  if(typeof value!=='string'||!/^https?:\/\//i.test(value))return null;
  try{const url=new URL(value);return['http:','https:'].includes(url.protocol)&&!url.username&&!url.password?url.href:null;}catch(_){return null;}
}
function renderArchive(report) {
  const keys={NOT_REQUESTED:'archiveNone',PENDING:'archivePending',AVAILABLE:'archiveAvailable',EMPTY:'archiveEmpty',RAW_ARCHIVE_UNAVAILABLE:'archiveUnavailable'};
  $('archive-summary').textContent=t(keys[report.archive_status]||'archiveUnknown');$('archive-parts').replaceChildren();
  for(const part of list(report.archive_parts)) {
    const block=element('div','border-top py-3 small technical-wrap'),partState=part.state==='AVAILABLE'?t('available'):part.state==='PENDING'?t('archivePending'):t('unavailable');
    block.append(element('p','font-monospace mb-1',part.part_id||t('archivePart')),element('p','text-secondary mb-2',`${bytesText(part.bytes)} · ${partState}${part.task_ref?` · ${part.task_ref}`:''}`));
    const url=validDownload(part.download_url),expiry=part.expires_at?new Date(part.expires_at).getTime():null;
    if(url&&!(expiry!==null&&Number.isFinite(expiry)&&expiry<=Date.now())){const link=element('a','',t('downloadPart'));link.href=url;link.target='_blank';link.rel='noopener noreferrer';block.append(link);}
    else block.append(element('p','text-secondary mb-1',t(url?'linkExpired':'noLink')));
    if(part.expires_at)block.append(element('p','text-secondary mb-1',t('expires',{time:dateText(part.expires_at)})));
    if(part.message)block.append(technicalDetails([part.message]));if(part.sha256)block.append(element('p','text-secondary small mt-2 mb-0',`SHA-256 ${part.sha256}`));$('archive-parts').append(block);
  }
}
$('language').addEventListener('change',()=>setLanguage($('language').value));
// 显式按钮与旧下拉框共用切换入口，避免语言状态出现两套来源。
document.querySelectorAll('[data-language]').forEach(button=>button.addEventListener('click',()=>setLanguage(button.dataset.language)));
document.querySelectorAll('form').forEach(form=>{form.noValidate=true;});
document.querySelectorAll('[data-view]').forEach(button=>button.addEventListener('click',()=>{
  $('logs-view').hidden=button.dataset.view!=='logs';$('ask-view').hidden=button.dataset.view!=='ask';
  document.querySelectorAll('[data-view]').forEach(item=>{item.classList.toggle('active',item===button);if(item===button)item.setAttribute('aria-current','page');else item.removeAttribute('aria-current');});
}));
document.querySelectorAll('input[name="selection-mode"]').forEach(input=>input.addEventListener('change',switchMode));
$('login-form').addEventListener('submit',async event=>{
  event.preventDefault();if($('login-submit').disabled)return;showError('login-error','');if(!$('password').value){showError('login-error','passwordRequired');return;}
  state.loggingIn=true;$('login-submit').disabled=true;$('login-submit').textContent=t('loggingIn');
  try{const result=await api('/auth/login',{method:'POST',body:JSON.stringify({password:$('password').value})});if(result.authenticated!==true)throw uiError('loginFailed');$('password').value='';await enterApp();}
  catch(error){showError('login-error',error);}finally{state.loggingIn=false;$('password').value='';$('login-submit').disabled=false;$('login-submit').textContent=t('login');}
});
$('logout').addEventListener('click',async()=>{
  $('logout').disabled=true;stopPolling();cancelRequests();
  try{await api('/auth/logout',{method:'POST',body:'{}'});}catch(error){if(state.authenticated)showError('global-notice','logoutError',{error});return;}finally{$('logout').disabled=false;}
  try{sessionStorage.removeItem(BATCH_KEY);}catch(_){}showLogin();
});
$('analysis-form').addEventListener('submit',event=>{event.preventDefault();discoverErrors();});
$('narrative-submit').addEventListener('click',requestNarrative);
$('restore-form').addEventListener('submit',async event=>{event.preventDefault();if(!$('restore-submit').disabled)await openBatch($('restore-id').value.trim());});
$('retry-poll').addEventListener('click',()=>{if(!state.batchId)return;stopPolling();$('poll-error').hidden=true;fetchBatch(state.generation);});
$('close-detail').addEventListener('click',()=>{state.selectedTask='';$('task-detail').hidden=true;});
$('download-report').addEventListener('click',()=>{
  if(!state.authenticated||!state.report)return;const url=URL.createObjectURL(new Blob([JSON.stringify(state.report,null,2)],{type:'application/json;charset=utf-8'}));
  const link=element('a');link.href=url;link.download=`holmes-${state.batchId}.json`;document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);
});
$('ask-form').addEventListener('submit',async event=>{
  event.preventDefault();if(state.asking||$('ask-submit').disabled)return;const question=$('question').value.trim();showError('ask-error','');
  if(!question){showError('ask-error','askRequired');return;}if(question.length>16000){showError('ask-error','questionTooLong');return;}
  state.asking=true;$('ask-submit').disabled=true;$('ask-progress').textContent=t('investigating');
  try{const result=await api('/api/investigate',{method:'POST',body:JSON.stringify({question})},2700000);if(!state.authenticated)return;
    $('answer').textContent=typeof result.answer==='string'?result.answer:t('missingAnswer');$('trace').textContent=JSON.stringify(result.trace||[],null,2);$('trace-details').hidden=!result.trace;$('trace-details').open=false;$('ask-result').hidden=false;
  }catch(error){if(state.authenticated)showError('ask-error',error);}finally{state.asking=false;$('ask-progress').textContent='';$('ask-submit').disabled=!state.authenticated||state.config?.capabilities?.ask!==true;}
});
applyLanguage();
(async()=>{
  try{const session=await api('/auth/session');state.authenticationRequired=session.authentication_required===true;
    if(session.authenticated===true)await enterApp();else{state.authenticationRequired=true;showLogin();}
  }catch(error){$('login-view').hidden=true;$('app-view').hidden=false;showError('global-notice','sessionError',{error});}
})();
