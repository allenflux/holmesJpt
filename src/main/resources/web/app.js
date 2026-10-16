'use strict';

// 页面只保留当前登录会话中的结果；浏览器存储中只保存可恢复的批次 ID。
const $ = (id) => document.getElementById(id);
const BATCH_KEY = 'holmes-log-batch-id';
const TERMINAL = new Set(['COMPLETED', 'PARTIAL', 'FAILED']);
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const state = {authenticated: false, authenticationRequired: false, loggingIn: false, config: null, report: null, batchId: '', selectedTask: '', generation: 0,
  timer: null, submitting: false, asking: false, controllers: new Set()};
const TEXT = {
  home: ['Holmes home', 'Holmes 首页'], mainNavigation: ['Main navigation', '主导航'], language: ['Language', '语言'],
  logAnalysis: ['Log analysis', '日志分析'], generalAsk: ['Ask Holmes', '通用问答'], logout: ['Sign out', '退出'],
  loginTitle: ['Sign in to Holmes', '登录 Holmes'], loginIntro: ['Enter your access password to continue.', '输入访问密码后继续。'],
  password: ['Access password', '访问密码'], login: ['Sign in', '登录'], loggingIn: ['Signing in…', '正在登录…'],
  logIntro: ['Connect task logs across Pods and review shared failure patterns.', '关联各 Pod 的任务日志，查看失败任务的共性与关键证据。'],
  loadingConfig: ['Loading configuration', '正在加载配置'], newAnalysis: ['New analysis', '新建分析'], logSource: ['Log source', '日志来源'],
  selectTasks: ['Select tasks', '选择任务'], latestFailed: ['Latest failed tasks', '最近失败任务'], explicitTasks: ['Specific task IDs', '指定任务 ID'],
  schedulerLatest: ['Recent failed tasks (by creation time)', '最近创建的失败任务（按创建时间）'],
  taskCount: ['Task count', '任务数量'], taskCountHint: ['Up to 50. If fewer failed tasks are available, the actual count is used.', '最多 50 个；不足时按实际失败任务数分析。'],
  taskIdsPlaceholder: ['One per line, or separated by commas', '每行一个，或用逗号分隔'], taskIdsHint: ['Up to 50 unique tasks. Duplicate IDs count once.', '最多 50 个任务；同一 ID 只计一次。'],
  from: ['Start time', '开始时间'], to: ['End time', '结束时间'], timezone: ['Enter times in {zone}. Maximum range: 24 hours.', '按本机时区 {zone} 输入；时间范围最长 24 小时。'],
  archiveChoice: ['Save this collection to FluxDrop', '将本次采集日志保存到 FluxDrop'],
  archiveHint: ['Otherwise, only the report and key excerpts are kept. Archive failures are reported explicitly.', '关闭时仅保留报告和关键摘录；归档失败会明确标记。'],
  startAnalysis: ['Start analysis', '开始分析'], creating: ['Creating batch…', '正在创建…'], restoreBatch: ['Open an existing batch', '恢复已有批次'],
  batchId: ['Batch ID', '批次 ID'], openBatch: ['Open batch', '查看批次'], results: ['Analysis results', '分析结果'], downloadReport: ['Download JSON report', '下载 JSON 报告'],
  taskProgress: ['Task progress', '任务处理进度'], retry: ['Retry', '重新查询'], evidenceQuality: ['Evidence scope and gaps', '取证范围与缺口'],
  technicalDetails: ['Technical details', '技术详情'], failureClusters: ['Failure patterns', '失败签名聚合'],
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
  demoBadge: ['Demo · file logs', '演示数据 · 文件日志'], fileBadge: ['File logs', '文件日志'], grafanaBadge: ['Grafana · all authorized Pods', 'Grafana · 全部授权 Pod'],
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
  qualityFlags: ['Quality flags', '质量标记'], noExtraFlags: ['No additional flags', '无额外标记'], refreshTask: ['Refresh task details', '刷新任务详情'],
  candidateEvidence: ['Candidate evidence', '候选证据'], noCandidateEvidence: ['No candidate evidence available', '暂无候选证据'], attemptUnverified: ['Attempt not verified', '执行归属未验证'],
  observedPromptId: ['Observed prompt ID', '观测到的 Prompt ID'],
  observedPatterns: ['Observed error patterns', '候选错误现象'], noObservedPatterns: ['No error pattern observed.', '尚未观察到明确错误现象。'],
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
  missingAnswer: ['The service did not return an answer.', '服务未返回回答内容。']
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
  GRAFANA_HTTP_401: ['Grafana rejected the log query (HTTP 401). Configure read access on the server.', 'Grafana 日志查询未通过认证（HTTP 401），请在服务端配置只读 Token 或开放匿名读取。'],
  GRAFANA_HTTP_403: ['Grafana denied log access (HTTP 403). Check the server-side read permissions.', 'Grafana 拒绝日志访问（HTTP 403），请检查服务端读取权限。'],
  NO_RAW_EVENTS_TO_ARCHIVE: ['There are no collected events to archive', '没有可归档的采集日志'], RAW_ARCHIVE_UNAVAILABLE: ['The log archive is unavailable', '本批次日志归档不可用'],
  STORAGE_LIMIT: ['Report storage reached its capacity limit', '报告存储达到容量上限'], INTERNAL_OR_SOURCE_ERROR: ['A service or log-source operation failed', '服务或日志来源处理失败']
};
const STATE_KEYS = {QUEUED: 'queued', RUNNING: 'running', COMPLETED: 'completed', PARTIAL: 'partial', FAILED: 'failed'};
const SIGNATURE_KEYS = {CUDA_OOM: 'sigCuda', OOM: 'sigOom', TIMEOUT: 'sigTimeout', UNKNOWN: 'sigUnknown', AMBIGUOUS: 'sigAmbiguous', INCOMPLETE: 'sigIncomplete'};
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
  document.documentElement.lang = language; document.title = 'Holmes'; $('language').value = language;
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
function rememberBatch(id) { try { sessionStorage.setItem(BATCH_KEY,id); } catch (_) { /* Optional storage. */ } }
function rememberedBatch() { try { return sessionStorage.getItem(BATCH_KEY)||''; } catch (_) { return ''; } }
function stopPolling() { clearTimeout(state.timer); state.timer=null; state.generation+=1; }
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
async function api(path,options={},timeout=25000) {
  const controller=new AbortController(); state.controllers.add(controller); const timer=setTimeout(()=>controller.abort(),timeout);
  try {
    const response=await fetch(path,{credentials:'same-origin',cache:'no-store',...options,
      headers:{Accept:'application/json',...(options.body?{'Content-Type':'application/json'}:{}),...options.headers},signal:controller.signal});
    let body={}; try { body=await response.json(); } catch (_) { if(response.ok) throw uiError('invalidResponse'); }
    if(!body||typeof body!=='object') body={};
    if(!response.ok) {
      if(response.status===401&&path!=='/auth/login') {state.authenticationRequired=true; showLogin('loginExpired');}
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
  uiMessages.clear();
  $('login-view').hidden=!state.authenticationRequired; $('app-view').hidden=true; $('navigation').hidden=true; $('logout').hidden=true;
  $('password').value='';$('login-submit').disabled=false;$('login-submit').textContent=t('login');
  $('report-section').hidden=true;$('task-detail').hidden=true;$('ask-result').hidden=true;$('question').value='';$('task-ids').value='';
  $('logs-view').hidden=false;$('ask-view').hidden=true;
  document.querySelectorAll('[data-view]').forEach((item)=>{const active=item.dataset.view==='logs';item.classList.toggle('active',active);if(active)item.setAttribute('aria-current','page');else item.removeAttribute('aria-current');});
  for(const id of ['clusters','observed-clusters','tasks-body','archive-parts','task-detail-content','answer','trace']) $(id).replaceChildren();
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
  $('source').value=config.log_source||'';$('namespace').replaceChildren();
  for(const name of list(config.namespaces)){const option=element('option','',name);option.value=name;$('namespace').append(option);}
  if(list(config.namespaces).includes(snapshot.namespace))$('namespace').value=snapshot.namespace;
  $('requested-count').value=count(defaults.requested_count)||20;$('from').value=localInput(defaults.from);$('to').value=localInput(defaults.to);
  $('raw-archive').checked=defaults.raw_archive==='required';
  $('analysis-submit').disabled=!snapshot.ready||config.capabilities?.logs!==true;
  const askEnabled=config.capabilities?.ask===true;$('ask-submit').disabled=!askEnabled;$('question').disabled=!askEnabled;
  $('footer').textContent=`Holmes · ${config.version||'0.1.0'}`;
  renderConfigMessages();switchMode();
}
function renderConfigMessages() {
  const config=state.config,snapshot=config.snapshot||{},askEnabled=config.capabilities?.ask===true,scheduler=config.task_source==='scheduler_api';
  $('data-badge').textContent=t(config.demo?'demoBadge':config.data_mode==='file'?'fileBadge':'grafanaBadge');
  $('data-badge').className='badge text-bg-light border text-secondary fw-normal';
  $('timezone-hint').textContent=t('timezone',{zone:Intl.DateTimeFormat().resolvedOptions().timeZone||'UTC'});
  $('snapshot-notice').textContent=scheduler?t(snapshot.ready?'schedulerAutomatic':'schedulerMissing')
    :snapshot.ready?t('snapshotReady',{namespace:snapshot.namespace||$('namespace').value,count:count(snapshot.failed_count),time:dateText(snapshot.observed_at)}):t('snapshotMissing');
  $('latest-mode-label').textContent=t(scheduler?'schedulerLatest':'latestFailed');
  $('source-time-hint').hidden=!scheduler;$('source-time-hint').textContent=scheduler?t('schedulerTimeHint'):'';
  $('submit-hint').textContent=scheduler?t('schedulerSelectionHint'):config.demo?t('demoHint'):!snapshot.ready?t('snapshotHint'):'';
  $('ask-notice').hidden=askEnabled&&!config.demo;$('ask-notice').textContent=!askEnabled?t('askUnavailable'):config.demo?t('askDemo'):'';
}
function switchMode() {
  const explicit=document.querySelector('input[name="selection-mode"]:checked').value==='explicit_task_ids';
  $('count-field').hidden=explicit;$('ids-field').hidden=!explicit;$('requested-count').required=!explicit;$('task-ids').required=explicit;
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
  return {source:state.config.log_source,namespace:$('namespace').value,selection,attempt_scope:'final_failed',raw_archive:$('raw-archive').checked?'required':'none'};
}
async function openBatch(id,errorId='restore-error') {
  showError(errorId,'');if(!UUID.test(id)){showError(errorId,'invalidBatch');return;}
  stopPolling();state.batchId=id;state.selectedTask='';state.report=null;$('report-section').hidden=true;$('task-detail').hidden=true;
  const generation=state.generation;$('restore-submit').disabled=true;
  try {await fetchBatch(generation,true);if(generation===state.generation&&state.authenticated)rememberBatch(id);}
  catch(error){if(state.authenticated&&generation===state.generation)showError(errorId,error);}finally{$('restore-submit').disabled=false;}
}
async function fetchBatch(generation,propagate=false) {
  try {
    const report=await api(`/api/log-investigations/${encodeURIComponent(state.batchId)}`);
    if(generation!==state.generation||!state.authenticated)return;
    if(!report||report.batch_id!==state.batchId||!Array.isArray(report.tasks))throw uiError('invalidResponse');
    state.report=report;$('poll-error').hidden=true;showError('poll-error-text','');renderReport(report);
    if(!TERMINAL.has(report.state))state.timer=setTimeout(()=>fetchBatch(generation),1800);
  }catch(error){if(propagate)throw error;if(generation!==state.generation||!state.authenticated)return;showError('poll-error-text','pollError',{error});$('poll-error').hidden=false;}
}
function renderReport(report) {
  const selecting=report.selection_status==='PENDING'&&['QUEUED','SELECTING_TASKS'].includes(report.stage);
  $('report-section').hidden=false;$('batch-state').textContent=t(selecting?'loadingTasks':STATE_KEYS[report.state]||'queued');
  $('batch-state').className=`badge border fw-normal ${report.state==='COMPLETED'?'text-success bg-success-subtle':['PARTIAL','FAILED'].includes(report.state)?'text-warning-emphasis bg-warning-subtle':'text-secondary text-bg-light'}`;
  $('batch-id').textContent=t('batchLabel',{id:report.batch_id});$('restore-id').value=report.batch_id;
  const total=count(report.selected_count),processed=count(report.processed_count),bar=$('batch-progress'),outer=bar.parentElement;
  outer.setAttribute('aria-label',t(selecting?'loadingTasks':'taskProgress'));
  outer.setAttribute('aria-valuemax',String(Math.max(total,1)));
  if(selecting)outer.removeAttribute('aria-valuenow');else outer.setAttribute('aria-valuenow',String(Math.min(processed,total)));
  bar.className=selecting?'progress-bar progress-bar-striped progress-bar-animated':'progress-bar';
  bar.style.width=`${selecting?100:total?Math.min(processed/total,1)*100:TERMINAL.has(report.state)?100:0}%`;
  $('batch-progress-text').textContent=selecting?t('loadingTasks'):t('taskProgressText',{processed,total});
  $('report-summary').textContent=selecting?t('selectionPending'):t('reportSummary',{total});
  $('report-counts').textContent=t('reportCounts',{logs:count(report.retrieved_event_count),errors:count(report.observed_error_event_count)});
  renderQuality(report);renderClusters(report);renderObservedClusters(report);renderTasks(report);renderArchive(report);if(state.selectedTask)renderTaskDetail(state.selectedTask);
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
function hasTaskLogEvidence(task,report=state.report) {
  // 任务状态或错误签名本身不能证明采到了日志；只接受已关联证据或有正文的候选时间线事件。
  return taskEvidence(task,report).length>0||list(task.timeline).some(event=>event&&event.context_only!==true
    &&(event.association_scope==='TASK_ONLY'||event.candidate_only===true)&&typeof event.event_time==='string'
    &&Number.isFinite(Date.parse(event.event_time))&&typeof event.excerpt==='string'&&event.excerpt.trim().length>0);
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
      signatureCell.append(element('div','text-secondary small font-sans-serif mt-1',`${t('observedPatterns')}: ${list(task.observed_signatures).map(signatureText).join(' · ')||t('noObservedPatterns')}`));
      statusCell.append(element('div','text-secondary small mt-1',t(hasTaskLogEvidence(task,report)?'candidateEvidence':'noCandidateEvidence')));
    }
    row.append(idCell,signatureCell,element('td','',taskOnly?t('attemptUnverified'):task.final_attempt_id||t('notProvided')),statusCell,actionCell);$('tasks-body').append(row);
  }
}
function metadata(entries) {
  const description=element('dl','row small mb-3');for(const[name,value]of entries)description.append(element('dt','col-4 col-md-2 text-secondary fw-normal',name),element('dd','col-8 col-md-10 technical-wrap',value));return description;
}
function renderTaskDetail(ref,override) {
  const task=override||list(state.report?.tasks).find(item=>item.task_ref===ref);if(!task){$('task-detail').hidden=true;return;}
  $('task-detail').hidden=false;showError('task-detail-error','');const content=$('task-detail-content');content.replaceChildren();
  const taskOnly=task.association_scope==='TASK_ONLY',evidence=taskEvidence(task);
  if(taskOnly)content.append(element('div','alert alert-light border small',t(hasTaskLogEvidence(task)?'taskOnlyNotice':'taskOnlyNoEvidenceNotice')));
  content.append(metadata([['Task ID',task.task_id],[t('finalAttempt'),taskOnly?t('attemptUnverified'):task.final_attempt_id||t('notProvided')],[t('failureSignature'),signatureText(task.signature)],
    [t('qualityFlags'),list(task.quality_flags).map(qualityText).join(' · ')||t('noExtraFlags')]]));
  if(taskOnly){
    const observed=element('div','small mb-3');observed.append(element('h3','h6',t('observedPatterns')));
    const patterns=list(task.observed_signatures),items=element('ul','mb-0');
    if(patterns.length)for(const value of patterns)items.append(element('li','',signatureText(value)));
    else items.append(element('li','text-secondary',t('noObservedPatterns')));
    observed.append(items);content.append(observed);
  }
  const unknownFlags=list(task.quality_flags).filter(value=>!QUALITY_NAMES[value]);if(unknownFlags.length)content.append(technicalDetails(unknownFlags));
  const refresh=element('button','btn btn-link btn-sm p-0 text-decoration-none mb-3',t('refreshTask'));refresh.type='button';
  refresh.addEventListener('click',async()=>{refresh.disabled=true;const batch=state.batchId;
    try{const response=await api(`/api/log-investigations/${encodeURIComponent(batch)}/tasks/${encodeURIComponent(ref)}`);if(state.authenticated&&state.batchId===batch&&state.selectedTask===ref)renderTaskDetail(ref,response.task||response);}
    catch(error){if(state.authenticated&&state.selectedTask===ref)showError('task-detail-error',error);}finally{refresh.disabled=false;}});content.append(refresh);
  const evidenceSection=element('div','border-top pt-3 mt-2');evidenceSection.append(element('h3','h6',t(taskOnly?'candidateEvidence':'keyEvidence')));
  if(!evidence.length)evidenceSection.append(element('p','text-secondary small',t(taskOnly?'noCandidateEvidence':'noEvidence')));
  evidence.forEach((item,index)=>{
    const detail=element('details','small my-3');detail.open=index<2;
    const candidate=item.candidate_only===true||item.association_scope==='TASK_ONLY'||item.historical_attempt===null;
    const scopeLabel=candidate?` · ${t('candidateEvidence')} · ${t('attemptUnverified')}`:item.historical_attempt===true?` · ${t('historicalRetry')}`:'';
    const basis=item.association_basis==='TASK_ID_ONLY_ATTEMPT_UNVERIFIED'?t('taskOnlyBasis'):item.association_basis||t('noBasis');
    detail.append(element('summary','technical-wrap',`${dateText(item.event_time)} · ${item.pod||t('unknownPod')} / ${item.container||t('unknownContainer')}${scopeLabel}`),
      element('p','text-secondary small mt-2 mb-1 technical-wrap',`${item.evidence_id} · ${candidate?t('attemptUnverified'):item.attempt_id||t('noAttempt')} · ${basis}`),
      element('pre','log-excerpt bg-light border rounded p-3 my-2',item.excerpt||''),element('p','text-secondary small technical-wrap',`SHA-256 ${item.content_sha256||t('notProvided')}`));
    if(item.observed_prompt_id)detail.append(element('p','text-secondary small technical-wrap',`${t('observedPromptId')}: ${item.observed_prompt_id}`));
    if(list(item.quality_flags).length)detail.append(element('p','text-secondary small',list(item.quality_flags).map(qualityText).join(' · ')));
    const unknown=list(item.quality_flags).filter(value=>!QUALITY_NAMES[value]);if(unknown.length)detail.append(technicalDetails(unknown));evidenceSection.append(detail);
  });content.append(evidenceSection);
  const timeline=element('details','border-top pt-3 mt-3 small');timeline.append(element('summary','',t('timeline',{count:list(task.timeline).length})));const items=element('ol','mt-3');
  for(const event of list(task.timeline)) {
    const item=element('li','mb-3'),time=element('time','text-secondary small',dateText(event.event_time));if(event.event_time)time.dateTime=event.event_time;
    const candidate=event.candidate_only===true||event.association_scope==='TASK_ONLY'||event.historical_attempt===null;
    item.append(time,element('div','',`${event.event||t('logEvent')} · ${event.pod||t('unknownPod')}${event.context_only?` · ${t('contextOnly')}`:''}${candidate?` · ${t('attemptUnverified')}`:event.historical_attempt===true?` · ${t('historicalAttempt')}`:''}`),element('pre','log-excerpt bg-light border rounded p-3 mt-2',event.excerpt||''));items.append(item);
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
$('language').addEventListener('change',()=>{language=$('language').value==='zh'?'zh':'en';try{localStorage.setItem('holmes-language',language);}catch(_){}applyLanguage();});
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
$('analysis-form').addEventListener('submit',async event=>{
  event.preventDefault();if(state.submitting||$('analysis-submit').disabled)return;showError('analysis-error','');let payload;
  try{payload=requestPayload();}catch(error){showError('analysis-error',error);return;}
  state.submitting=true;$('analysis-submit').disabled=true;$('analysis-submit').textContent=t('creating');
  try {
    const report=await api('/api/log-investigations',{method:'POST',body:JSON.stringify(payload)});if(!state.authenticated)return;if(!UUID.test(report.batch_id))throw uiError('invalidResponse');
    stopPolling();state.batchId=report.batch_id;state.selectedTask='';state.report=report;rememberBatch(report.batch_id);$('task-detail').hidden=true;renderReport(report);
    if(!TERMINAL.has(report.state))state.timer=setTimeout(()=>fetchBatch(state.generation),1800);$('report-section').scrollIntoView({behavior:'smooth',block:'start'});
  }catch(error){if(state.authenticated)showError('analysis-error',error);}finally{state.submitting=false;$('analysis-submit').textContent=t('startAnalysis');$('analysis-submit').disabled=!state.authenticated||!state.config?.snapshot?.ready||state.config?.capabilities?.logs!==true;}
});
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
