#!/usr/bin/env bash
# PostToolUse 훅 (spec-harness-v1) — workflow agent가 도구를 쓸 때마다 transcript의 '새로 확정된'
# 부분만 사람용 로그에 증분 append한다. 백그라운드 프로세스 없음 → 좀비/중복 없음.
#
# transcript 경로(실측 확정):
#   - PostToolUse payload에는 transcript_path(메인 세션)와 agent_id가 온다.
#   - workflow agent의 transcript는 <main-transcript>/subagents/workflows/wf_<runid>/agent-<id>.jsonl 에 있다.
#     runid를 모르므로 agent_id로 glob 탐색한다. (native Task 경로 .../subagents/agent-<id>.jsonl 도 fallback)
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPTS="$(cd "$HERE/.." && pwd)"
INPUT="$(cat)"

field() {
  printf '%s' "$INPUT" | python3 -c "import sys,json
try: print(json.load(sys.stdin).get('$1','') or '')
except Exception: print('')" 2>/dev/null
}

ROLE="$(field agent_type)"
case "$ROLE" in
  spec-harness-v1-*) : ;;
  *) exit 0 ;;
esac

AID="$(field agent_id)"; [ -z "$AID" ] && AID="$ROLE"

# 로그 루트: cwd의 git 최상위(worktree)를 우선. CLAUDE_PROJECT_DIR는 develop 루트일 수 있어 fallback. (P4-W)
CWD="$(field cwd)"
PROJ="$(git -C "${CWD:-.}" rev-parse --show-toplevel 2>/dev/null)"
[ -z "$PROJ" ] && PROJ="${CLAUDE_PROJECT_DIR:-.}"

# transcript 탐색: 메인 transcript_path 아래에서 agent_id로 workflow/native 경로를 glob한다.
TP_MAIN="$(field transcript_path)"
BASE="${TP_MAIN%.jsonl}/subagents"
TP=""
# 1순위: workflow agent 경로
for cand in "$BASE"/workflows/wf_*/agent-"$AID".jsonl; do
  [ -f "$cand" ] && TP="$cand" && break
done
# 2순위: native Task 경로 (혹시 workflow가 아닌 경우)
if [ -z "$TP" ] && [ -f "$BASE/agent-$AID.jsonl" ]; then
  TP="$BASE/agent-$AID.jsonl"
fi
{ [ -z "$TP" ] || [ ! -f "$TP" ]; } && exit 0

# 로그 디렉터리: preflight가 남긴 active-phase 마커가 있으면 <phase>/logs, 없으면 fallback.
PHASE=""
[ -f "$PROJ/.harness/active-phase" ] && PHASE="$(head -1 "$PROJ/.harness/active-phase" 2>/dev/null | tr -d '[:space:]')"
if [ -n "$PHASE" ]; then LOGDIR="$PROJ/$PHASE/logs"; else LOGDIR="${HARNESS_LOG_DIR:-logs}"; fi
mkdir -p "$LOGDIR" 2>/dev/null

STATE="$PROJ/.harness/logstate-$AID.json"
python3 "$SCRIPTS/transcript_formatter.py" \
  --input "$TP" --output "$LOGDIR/$ROLE.log" --role "$ROLE" --state "$STATE" >/dev/null 2>&1
exit 0
