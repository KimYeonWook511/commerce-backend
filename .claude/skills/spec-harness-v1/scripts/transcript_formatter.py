"""sub-agent transcript(.jsonl)를 사람이 읽는 로그로 변환한다 (spec-harness-v1).

workflow agent를 포함한 sub-agent는 stdout을 외부로 흘리지 않고, Claude Code 런타임이
작업 내용을 transcript .jsonl 파일에 직접 기록한다. workflow agent의 경로는
`<main-transcript>/subagents/workflows/wf_<runid>/agent-<id>.jsonl` 이다(native Task는
`<main-transcript>/subagents/agent-<id>.jsonl`). 이 드라이버는 그 파일을 읽어
format_events 로직으로 포맷해 logs/<role>.log 에 쌓는다.

이 과정 전체가 셸/파이썬이라 모델 토큰을 추가로 쓰지 않는다. (transcript 기록은 런타임이
어차피 하는 일이고, 우리는 그 파일을 읽기만 한다.)

모드는 증분(incremental) 단일 경로다:
  - PostToolUse마다 새로 확정된 메시지만 증분 append. requestId/uuid dedup + hold-last +
    emit-once(상태파일)로 중복 없이 실시간 로깅.
  - --final(SubagentStop)이면 보류분까지 flush + 완료 박스. 상태파일 정리는 호출자(hook)가 한다.

transcript 스키마(v2.1.x 확인):
  - 줄마다 {type, message, uuid, parentUuid, isSidechain, agentId, timestamp, ...}
  - type=user      : message.content 가 문자열(위임 프롬프트) 또는 [{type:tool_result,...}]
  - type=assistant : message.content = [{type:text}|{type:tool_use}|{type:thinking}]
  - type=attachment/queue-operation/last-prompt/ai-title : 메타 → format_events가 None으로 버림
"""

from __future__ import annotations

import argparse
import json
import os

import format_events as fe

BAR = fe.BAR


# ─────────────────────────────────────────────────────────────────────────────
# 포맷 헬퍼
# ─────────────────────────────────────────────────────────────────────────────
def _collect_tool_names(event, tool_names):
    """assistant content 안의 tool_use(id→name)를 매핑에 누적한다 (tool_result 동사 표기용)."""
    msg = event.get("message")
    content = msg.get("content") if isinstance(msg, dict) else None
    if isinstance(content, list):
        for b in content:
            if isinstance(b, dict) and b.get("type") == "tool_use":
                tool_names[b.get("id")] = b.get("name")


def _format_task_prompt(event):
    """위임 프롬프트(첫 user 줄의 문자열 content)를 📋 머리줄로 만든다. 아니면 None."""
    if event.get("type") != "user":
        return None
    msg = event.get("message") or {}
    content = msg.get("content")
    if not isinstance(content, str) or not content.strip():
        return None
    first = content.strip().splitlines()[0]
    return f"📋 task  {fe._truncate(first, fe.PREVIEW_CHARS)}"


def render_header(role, description=None):
    line = f"● {role}" + (f" — {description}" if description else "")
    return f"{BAR}\n{line}\n{BAR}"


def render_footer(role, last_message=None):
    return "\n".join([BAR, f"✅ {role} 완료", BAR])


def format_event_for_log(event, tool_names):
    """한 이벤트를 로그 텍스트로. 위임 프롬프트는 직접, 나머지는 format_events에 위임."""
    task = _format_task_prompt(event)
    if task is not None:
        return task
    return fe.format_event(event, tool_names=tool_names)


def write_lines(out_path, chunks):
    if not chunks:
        return
    with open(out_path, "a", encoding="utf-8") as f:
        f.write("\n".join(chunks) + "\n")


# ─────────────────────────────────────────────────────────────────────────────
# 증분 상태 (emit-once)
# ─────────────────────────────────────────────────────────────────────────────
def _event_key(ev, idx):
    """스트리밍 누적 엔트리를 하나로 합치기 위한 키. requestId 우선(스트리밍 중 같은 응답이
    여러 줄로 누적될 때 공유됨), 없으면 uuid(메시지 식별자), 그것도 없으면 인덱스."""
    return ev.get("requestId") or ev.get("uuid") or f"_idx{idx}"


def _load_state(state_path):
    if state_path and os.path.exists(state_path):
        try:
            with open(state_path, encoding="utf-8") as f:
                s = json.load(f)
            if isinstance(s, dict):
                return set(s.get("emitted", [])), bool(s.get("header", False)), bool(s.get("footer", False))
        except (OSError, ValueError):
            pass
    return set(), False, False


def _save_state(state_path, emitted, header_done, footer_done):
    if not state_path:
        return
    os.makedirs(os.path.dirname(os.path.abspath(state_path)) or ".", exist_ok=True)
    with open(state_path, "w", encoding="utf-8") as f:
        json.dump({"emitted": sorted(emitted), "header": header_done, "footer": footer_done}, f)


# ─────────────────────────────────────────────────────────────────────────────
# 증분 변환 (유일한 경로)
# ─────────────────────────────────────────────────────────────────────────────
def run_incremental(input_path, out_path, role, state_path, description=None, final=False):
    """PostToolUse마다 transcript의 '새로 확정된' 메시지만 골라 로그에 append한다.

    - dedup: 같은 키(requestId→uuid)의 스트리밍 누적 엔트리는 마지막(가장 완성된) 것만 남긴다.
    - hold-last: 마지막 키는 아직 생성 중일 수 있으므로 보류한다. final=True(SubagentStop)면 마지막까지 flush.
    - emit-once: state_path에 이미 찍은 키를 기록해 재호출 시 중복 출력하지 않는다.
    백그라운드 프로세스 없이 매 호출이 짧게 끝나므로 좀비/중복이 구조적으로 없다.
    """
    emitted, header_done, footer_done = _load_state(state_path)
    # 이미 footer까지 찍힌 agent는 완료된 것. 재호출돼도 렌더하지 않는다
    # (전체 재덤프·footer 중복·재기상 응답 노이즈 차단).
    if footer_done:
        return
    if not os.path.exists(input_path):
        return

    with open(input_path, encoding="utf-8", errors="replace") as f:
        raw_lines = f.read().split("\n")
    if not final and raw_lines:
        raw_lines = raw_lines[:-1]   # 마지막 조각은 half-written일 수 있어 제외

    order, last_ev = [], {}
    for idx, raw in enumerate(raw_lines):
        s = raw.strip()
        if not s:
            continue
        try:
            ev = json.loads(s)
        except (json.JSONDecodeError, ValueError):
            continue
        if not isinstance(ev, dict):
            continue
        k = _event_key(ev, idx)
        if k not in last_ev:
            order.append(k)
        last_ev[k] = ev   # 같은 키의 마지막 엔트리로 덮어씀(누적본 합침)

    settled = order if final else order[:-1]   # 마지막 키 보류(진행 중 가능)

    tool_names = {}
    for k in order:                            # tool_result 동사 표기용 이름은 전체에서 수집
        _collect_tool_names(last_ev[k], tool_names)

    chunks = []
    if not header_done and settled:
        chunks.append(render_header(role, description))
        header_done = True
    for k in settled:
        if k in emitted:
            continue
        formatted = format_event_for_log(last_ev[k], tool_names)
        if formatted is not None:
            chunks.append(formatted)
        emitted.add(k)
    if final:
        chunks.append(render_footer(role))
        footer_done = True

    write_lines(out_path, chunks)
    _save_state(state_path, emitted, header_done, footer_done)


def main(argv=None):
    p = argparse.ArgumentParser(description="spec-harness-v1 transcript → human-readable log (증분)")
    p.add_argument("--input", required=True, help="transcript .jsonl 경로 (agent_transcript_path)")
    p.add_argument("--output", required=True, help="쌓을 로그 경로 (예: logs/developer.log)")
    p.add_argument("--role", required=True, help="에이전트 역할/타입 (예: spec-harness-v1-developer)")
    p.add_argument("--state", required=True, help="증분 모드 상태파일 경로")
    p.add_argument("--description", default=None, help="헤더에 표시할 설명 (선택)")
    p.add_argument("--final", action="store_true", help="마지막 flush(보류분+footer) — SubagentStop용")
    args = p.parse_args(argv)

    os.makedirs(os.path.dirname(os.path.abspath(args.output)) or ".", exist_ok=True)
    run_incremental(args.input, args.output, args.role, args.state,
                    description=args.description, final=args.final)


if __name__ == "__main__":
    main()
