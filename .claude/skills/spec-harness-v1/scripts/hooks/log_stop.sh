#!/usr/bin/env bash
# SubagentStop 훅 (spec-harness-v1) — 증분 로깅의 마지막 flush.
# PostToolUse(log_progress.sh)가 보류해 둔 마지막 메시지까지 찍고 완료 박스(footer)를 붙인다.
# 백그라운드 프로세스 없음. 항상 exit 0.
#
# transcript 경로(실측 확정):
#   - SubagentStop payload는 agent_transcript_path를 직접 제공한다 → 이 필드를 그대로 --input 으로 쓴다.
#     (없을 때만 PostToolUse와 같은 glob fallback.)
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

CWD="$(field cwd)"
PROJ="$(git -C "${CWD:-.}" rev-parse --show-toplevel 2>/dev/null)"
[ -z "$PROJ" ] && PROJ="${CLAUDE_PROJECT_DIR:-.}"

# 1순위: payload가 직접 주는 agent_transcript_path (P2 손조립 제거)
TP="$(field agent_transcript_path)"
# fallback: 없으면 PostToolUse와 동일한 glob 탐색
if [ -z "$TP" ] || [ ! -f "$TP" ]; then
  TP_MAIN="$(field transcript_path)"
  BASE="${TP_MAIN%.jsonl}/subagents"
  TP=""
  for cand in "$BASE"/workflows/wf_*/agent-"$AID".jsonl; do
    [ -f "$cand" ] && TP="$cand" && break
  done
  [ -z "$TP" ] && [ -f "$BASE/agent-$AID.jsonl" ] && TP="$BASE/agent-$AID.jsonl"
fi

PHASE=""
[ -f "$PROJ/.harness/active-phase" ] && PHASE="$(head -1 "$PROJ/.harness/active-phase" 2>/dev/null | tr -d '[:space:]')"
if [ -n "$PHASE" ]; then LOGDIR="$PROJ/$PHASE/logs"; else LOGDIR="${HARNESS_LOG_DIR:-logs}"; fi
mkdir -p "$LOGDIR" 2>/dev/null

STATE="$PROJ/.harness/logstate-$AID.json"
if [ -n "$TP" ] && [ -f "$TP" ]; then
  python3 "$SCRIPTS/transcript_formatter.py" \
    --input "$TP" --output "$LOGDIR/$ROLE.log" --role "$ROLE" --state "$STATE" --final >/dev/null 2>&1
fi
# logstate는 여기서 지우지 않는다(emit-once·footer 가드 보존). 정리는 preflight가 init 시 한다.
exit 0
