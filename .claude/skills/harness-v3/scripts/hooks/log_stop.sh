#!/usr/bin/env bash
# SubagentStop 훅 (harness-v3) — 증분 로깅의 마지막 flush.
# PostToolUse(log_progress.sh)가 보류해 둔 마지막 메시지까지 찍고 완료 박스(footer)를 붙인 뒤,
# 상태파일(.harness/logstate-<id>.json)을 정리한다. 백그라운드 프로세스 없음. 항상 exit 0.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPTS="$(cd "$HERE/.." && pwd)"        # hooks/ 의 부모 = scripts/
INPUT="$(cat)"

field() {
  printf '%s' "$INPUT" | python3 -c "import sys,json
try: print(json.load(sys.stdin).get('$1','') or '')
except Exception: print('')" 2>/dev/null
}

ROLE="$(field agent_type)"
case "$ROLE" in
  harness-v3-*) : ;;
  *) exit 0 ;;
esac

AID="$(field agent_id)"; [ -z "$AID" ] && AID="$ROLE"
PROJ="${CLAUDE_PROJECT_DIR:-.}"

# sub transcript: transcript_path(메인 세션 .jsonl) 확장자를 떼고 그 아래 subagents/agent-<id>.jsonl.
# 메인 transcript로 fallback하면 메인 세션 활동이 role 로그를 오염시키므로 하지 않는다.
TP="$(field transcript_path)"
TP="${TP%.jsonl}/subagents/agent-$AID.jsonl"

PHASE=""
[ -f "$PROJ/.harness/active-phase" ] && PHASE="$(head -1 "$PROJ/.harness/active-phase" 2>/dev/null | tr -d '[:space:]')"
if [ -n "$PHASE" ]; then LOGDIR="$PROJ/$PHASE/logs"; else LOGDIR="${HARNESS_LOG_DIR:-logs}"; fi
mkdir -p "$LOGDIR" 2>/dev/null

STATE="$PROJ/.harness/logstate-$AID.json"
if [ -n "$TP" ] && [ -f "$TP" ]; then
  python3 "$SCRIPTS/transcript_formatter.py" \
    --input "$TP" --output "$LOGDIR/$ROLE.log" --role "$ROLE" --state "$STATE" --final >/dev/null 2>&1
fi
rm -f "$STATE" 2>/dev/null
exit 0
