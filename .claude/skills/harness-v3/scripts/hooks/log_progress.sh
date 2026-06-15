#!/usr/bin/env bash
# PostToolUse 훅 (harness-v3) — sub-agent가 도구를 쓸 때마다 transcript의 '새로 확정된' 부분만
# 사람용 로그에 증분 append한다. 백그라운드 프로세스를 띄우지 않으므로 좀비/중복이 없다.
# 매 호출은 짧게 끝난다(상태파일의 '이미 찍은 키'로 재출력 차단, 마지막 진행 중 메시지는 보류).
# 자세한 dedup 규칙은 transcript_formatter.run_incremental 참고. 항상 exit 0.
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
  harness-v3-*) : ;;
  *) exit 0 ;;
esac

AID="$(field agent_id)"; [ -z "$AID" ] && AID="$ROLE"
PROJ="${CLAUDE_PROJECT_DIR:-.}"

# sub transcript: transcript_path(메인 세션 .jsonl) 확장자를 떼고 그 아래 subagents/agent-<id>.jsonl.
# 메인 transcript로 fallback하면 메인 세션 활동이 role 로그를 오염시키므로 하지 않는다.
TP="$(field transcript_path)"
TP="${TP%.jsonl}/subagents/agent-$AID.jsonl"
{ [ -z "$TP" ] || [ ! -f "$TP" ]; } && exit 0

PHASE=""
[ -f "$PROJ/.harness/active-phase" ] && PHASE="$(head -1 "$PROJ/.harness/active-phase" 2>/dev/null | tr -d '[:space:]')"
if [ -n "$PHASE" ]; then LOGDIR="$PROJ/$PHASE/logs"; else LOGDIR="${HARNESS_LOG_DIR:-logs}"; fi
mkdir -p "$LOGDIR" 2>/dev/null

STATE="$PROJ/.harness/logstate-$AID.json"
python3 "$SCRIPTS/transcript_formatter.py" \
  --input "$TP" --output "$LOGDIR/$ROLE.log" --role "$ROLE" --state "$STATE" >/dev/null 2>&1
exit 0
