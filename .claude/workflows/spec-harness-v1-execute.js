export const meta = {
  name: 'spec-harness-v1-execute',
  description: 'phase 오케스트레이션 — step별 dev→AC→review→commit→record, 끝에 finalize',
  phases: [
    { title: 'Steps', detail: 'step마다 구현→검증→검토→커밋→기록' },
    { title: 'Finalize', detail: 'completed_at·spec index 동기화·push' },
  ],
};

// spec-harness-v1-execute — phase 오케스트레이터 (dynamic workflow)
//
// 한 번 기동으로 phase를 끝까지 완주한다: 미완 step부터 순차로 developer→(AC확인)→reviewer
// →committer→recorder 를 돌리고, 모든 step이 끝나면 finalizer로 phase를 닫는다.
// 실제 shell/git/fs 작업은 전부 agent가 execute.py 서브커맨드를 통해 한다. 이 스크립트는 조율만.
//
// 입력(args): execute.py preflight가 만든 객체.
//   { phase_dir, phase_relpath, project, phase, execute,
//     steps: [{step, name, status}], execution: {developer_model, reviewer_model, committer_model, push} }
// 결과: return 으로 outcome 객체를 돌려준다.
//
// 미확정(trial 확인): schema 강제 지원 여부 → SUPPORTS_SCHEMA 토글. parseAgentJson은 반환이
// 문자열이든 객체든 모두 처리하므로 반환형 차이에 안전하다.

const MAX_RETRIES = 3;
const SUPPORTS_SCHEMA = false; // trial에서 schema 지원 확인되면 true

const SCHEMAS = {
  developer: {
    type: 'object',
    properties: {
      step: { type: 'number' }, attempt: { type: 'number' },
      status: { type: 'string', enum: ['completed', 'blocked', 'error'] },
      summary: { type: 'string' },
      blocked_reason: { type: ['string', 'null'] },
      struggles: { type: ['string', 'null'] },
      ac: { type: 'object' },
    },
    required: ['status', 'summary', 'ac'],
  },
  reviewer: {
    type: 'object',
    properties: {
      step: { type: 'number' },
      decision: { type: 'string', enum: ['approved', 'retryable_error', 'blocked'] },
      message: { type: 'string' },
    },
    required: ['decision'],
  },
  committer: {
    type: 'object',
    properties: { committed: { type: 'boolean' }, commits: { type: 'array' } },
    required: ['committed'],
  },
};

// agent 호출 래퍼 — opts(agentType/model/schema/label/phase)를 여기 한 곳에서 조립.
async function callAgent(agentType, model, prompt, schemaKey, label, phaseName) {
  const opts = { agentType };
  if (model) opts.model = model;
  if (label) opts.label = label;
  if (phaseName) opts.phase = phaseName;
  if (SUPPORTS_SCHEMA && schemaKey && SCHEMAS[schemaKey]) opts.schema = SCHEMAS[schemaKey];
  return await agent(prompt, opts);
}

// agent 반환을 객체로. 이미 객체면 그대로, 문자열이면 코드펜스·앞뒤설명 벗겨 JSON.parse.
function parseAgentJson(raw) {
  if (raw && typeof raw === 'object') return raw;
  if (typeof raw !== 'string') return null;
  let text = raw.trim();
  const fence = text.match(/```(?:json)?\s*([\s\S]*?)```/);
  if (fence) text = fence[1].trim();
  const first = text.indexOf('{');
  const last = text.lastIndexOf('}');
  if (first !== -1 && last !== -1 && last > first) text = text.slice(first, last + 1);
  try { return JSON.parse(text); } catch { return null; }
}

// ── 프롬프트 빌더 ───────────────────────────────────────────────
function commonVars(a, step, attempt) {
  return [
    `EXECUTE: ${a.execute}`,
    `PHASE_DIR: ${a.phase_dir}`,
    `STEP: ${step.step}`,
    `ATTEMPT: ${attempt}`,
    `PROJECT: ${a.project}`,
  ].join('\n');
}

function developerPrompt(a, step, attempt, prevError) {
  let p = `${commonVars(a, step, attempt)}\n\n`
    + `step ${step.step}(${step.name})을 구현하라. 위 EXECUTE/PHASE_DIR/STEP/ATTEMPT 값을 사용한다.\n`
    + `먼저 build-context로 컨텍스트를 로드하고, 구현을 마친 뒤 verify-ac로 AC를 검증한 다음, `
    + `약속된 JSON 한 개만 반환하라.`;
  if (prevError) {
    p += `\n\n## 이전 시도 실패\n${prevError}\n같은 실패를 반복하지 않도록 원인을 해결한 뒤 진행하라.`;
  }
  return p;
}

function reviewerPrompt(a, step, attempt, dev) {
  return `${commonVars(a, step, attempt)}\n\n`
    + `step ${step.step}(${step.name})의 변경을 read-only로 검토하라. git status/diff로 변경 범위를 직접 파악하고, `
    + `step${step.step}-ac-output.json의 AC 결과가 developer 보고와 일치하는지 대조하라.\n\n`
    + `## developer 보고\n`
    + `- summary: ${dev.summary || ''}\n`
    + `- struggles: ${dev.struggles || '(없음)'}\n`
    + `- ac.passed: ${dev.ac ? dev.ac.passed : '(없음)'}\n\n`
    + `약속된 판정 JSON 한 개만 반환하라.`;
}

function committerPrompt(a, step, attempt, dev) {
  return `${commonVars(a, step, attempt)}\n\n`
    + `reviewer가 승인한 step ${step.step}(${step.name})의 변경을 commit-conventions에 따라 목적별로 커밋하라.\n`
    + `developer summary: ${dev.summary || ''}\n`
    + `약속된 결과 JSON 한 개만 반환하라.`;
}

function recorderPrompt(a, step, status, summary, reason) {
  return `${commonVars(a, step, 0)}\n`
    + `STATUS: ${status}\nSUMMARY: ${summary || ''}\nREASON: ${reason || ''}\n\n`
    + `record-step으로 위 step의 상태를 phase index에 기록하라. 결과 JSON을 그대로 반환하라.`;
}

function finalizerPrompt(a) {
  return `EXECUTE: ${a.execute}\nPHASE_DIR: ${a.phase_dir}\n`
    + `NO_PUSH: ${a.execution && a.execution.push === false ? 'true' : 'false'}\n\n`
    + `phase의 모든 step이 끝났다. finalize로 phase를 마무리하라(completed_at·spec index 동기화·push). `
    + `결과 JSON을 그대로 반환하라.`;
}

// ── 한 step 처리: 성공이면 {ok}, blocked/error면 {stopped} 반환 ──
async function runStep(a, step) {
  const devModel = a.execution && a.execution.developer_model;
  const revModel = a.execution && a.execution.reviewer_model;
  const comModel = a.execution && a.execution.committer_model;
  let prevError = null;

  for (let attempt = 1; attempt <= MAX_RETRIES; attempt++) {
    const tag = `step${step.step}`;

    // 1) developer
    const dev = parseAgentJson(await callAgent(
      'spec-harness-v1-developer', devModel, developerPrompt(a, step, attempt, prevError),
      'developer', `${tag}:developer`, 'Steps'));
    if (!dev || !dev.status) {
      prevError = 'developer 반환 JSON 파싱 실패 — 약속된 JSON 한 개만 출력해야 한다.';
      continue;
    }
    if (dev.status === 'blocked' || dev.status === 'error') {
      return { stopped: dev.status, step: step.step, reason: dev.blocked_reason || 'developer가 중단 보고', summary: dev.summary };
    }
    if (!dev.ac || dev.ac.passed !== true) {
      const failed = dev.ac && Array.isArray(dev.ac.results)
        ? dev.ac.results.filter(r => !r.ok).map(r => `${r.command} (기대 ${r.expectExit}, 실제 ${r.actualExit})`).join('; ')
        : 'AC 미통과';
      prevError = `AC 미통과: ${failed}`;
      continue;
    }

    // 2) reviewer
    const rev = parseAgentJson(await callAgent(
      'spec-harness-v1-reviewer', revModel, reviewerPrompt(a, step, attempt, dev),
      'reviewer', `${tag}:reviewer`, 'Steps'));
    if (!rev || !rev.decision) {
      prevError = 'reviewer 반환 JSON 파싱 실패.';
      continue;
    }
    if (rev.decision === 'blocked') {
      return { stopped: 'blocked', step: step.step, reason: rev.message || 'reviewer가 blocked 판정', summary: dev.summary };
    }
    if (rev.decision === 'retryable_error') {
      prevError = `reviewer 재시도 요청: ${rev.message || ''}`;
      continue;
    }
    if (rev.decision !== 'approved') {
      prevError = `알 수 없는 reviewer 판정(${rev.decision}) — 자동 승인하지 않는다.`;
      continue;
    }

    // 3) committer (결과 신뢰, 엄격 검증 없음)
    const com = parseAgentJson(await callAgent(
      'spec-harness-v1-committer', comModel, committerPrompt(a, step, attempt, dev),
      'committer', `${tag}:committer`, 'Steps')) || { committed: false, commits: [] };

    // 4) recorder — 정본 index에 completed 기록
    await callAgent('spec-harness-v1-recorder', null,
      recorderPrompt(a, step, 'completed', dev.summary, null), null, `${tag}:recorder`, 'Steps');

    return { ok: true, step: step.step, summary: dev.summary, commits: com.commits || [] };
  }

  return { stopped: 'error', step: step.step, reason: `${MAX_RETRIES}회 시도 후 실패: ${prevError || ''}` };
}

// ── 본문 (top-level) ────────────────────────────────────────────
// 실측(trial): args는 이 런타임에서 JSON '문자열'로 주입된다(객체가 아님).
// 문자열이면 parse, 이미 객체면 그대로. 깨진 입력은 명확히 실패시킨다.
let a;
if (typeof args === 'string') {
  try { a = JSON.parse(args); }
  catch (e) { return { outcome: 'error', reason: `args JSON 파싱 실패: ${e.message}`, raw_args: args }; }
} else if (args && typeof args === 'object') {
  a = args;
} else {
  return { outcome: 'error', reason: `args가 비어 있거나 형식이 잘못됨(type=${typeof args}).` };
}
if (!a || !Array.isArray(a.steps)) {
  return { outcome: 'error', reason: 'args.steps가 배열이 아니다. preflight 출력(JSON)을 args로 넘겼는지 확인하라.', got_keys: a ? Object.keys(a) : null };
}

const results = [];

phase('Steps');
let stopped = null;
for (const step of a.steps) {
  // v2 방식: pending인 step만 실행한다.
  // - completed: 이미 끝남 → 건너뛰고 계속 진행(재실행 안전성).
  // - blocked/error: 이전 실행에서 멈춘 step. 사람이 원인을 고치고 reset-step으로 pending으로
  //   되돌려야 재개된다. 안 고친 채(=status 그대로) 재실행하면 같은 실패를 반복하고 토큰만
  //   낭비하므로, 여기서 실행하지 않고 "reset이 필요하다"고 알리며 멈춘다.
  if (step.status === 'completed') {
    results.push({ step: step.step, skipped: true });
    continue;
  }
  if (step.status !== 'pending') {
    // blocked / error 등 — 사람의 reset을 기다리는 상태. 여기서 멈춘다.
    stopped = {
      stopped: step.status,            // 'blocked' | 'error' | 기타
      step: step.step,
      reason: `step ${step.step}이(가) '${step.status}' 상태다. 원인을 고친 뒤 `
        + `\`execute.py reset-step <PHASE_DIR> --step ${step.step}\`로 pending으로 되돌리고 재실행하라. `
        + `(고치지 않은 채 재실행하면 같은 실패가 반복되므로 자동 재개하지 않는다.)`,
      needs_reset: true,
    };
    break;
  }
  const r = await runStep(a, step);
  results.push(r);
  if (r.stopped) {
    // 이번 실행에서 blocked/error 발생 → 그 상태를 정본에 기록하고 중단
    await callAgent('spec-harness-v1-recorder', null,
      recorderPrompt(a, { step: r.step }, r.stopped, r.summary || '', r.reason), null,
      `step${r.step}:recorder`, 'Steps');
    stopped = r;
    break;
  }
}

if (stopped) {
  return {
    outcome: stopped.stopped,            // 'blocked' | 'error'
    stopped_at_step: stopped.step,
    reason: stopped.reason,
    needs_reset: stopped.needs_reset || false,
    completed_steps: results.filter(x => x.ok || x.skipped).map(x => x.step),
  };
}

// 모든 step 완주 → finalize
phase('Finalize');
const fin = parseAgentJson(await callAgent(
  'spec-harness-v1-finalizer', null, finalizerPrompt(a), null, 'finalize', 'Finalize')) || {};

return {
  outcome: 'completed',
  phase: a.phase,
  completed_steps: results.filter(x => x.ok || x.skipped).map(x => x.step),
  finalize: fin,
};
