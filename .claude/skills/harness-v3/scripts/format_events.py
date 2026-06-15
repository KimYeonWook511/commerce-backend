"""stream-json(JSONL) 이벤트를 사람이 읽기 좋은 텍스트로 변환한다.

sub-agent transcript(.jsonl)에 누적되는 stream-json 계열 이벤트를 한 줄(또는 몇 줄)
단위로 포맷해 tmux pane이 tail하는 `.log`에 쌓는다. 색(ANSI)은 추후 단계이며 지금은 흑백이다.

순수 함수만 두어 단위 테스트가 가능하다. JSONL 파일 읽기/append는 호출측(transcript_formatter)이 맡는다.
tool_result에는 어떤 도구의 결과인지 정보가 없으므로, 호출측이 tool_use_id→name 매핑(tool_names)을
넘기면 도구별 동사(Write→생성됨 등)를 붙인다.
"""

from __future__ import annotations

import json

# Write content, Edit new_string 같은 큰 인자의 미리보기 한도
PREVIEW_LINES = 8
PREVIEW_CHARS = 200

# step 구분선 (시작·완료=박스, retry=점선). tmux 오른쪽 pane 폭을 고려해 28칸.
# 호출측(transcript_formatter)이 헤더·완료 박스를 이 상수로 그린다.
BAR = "━" * 28
DOT = "╌" * 28

# 도구 성공 시 결과 줄에 붙일 동사
TOOL_SUCCESS_VERB = {
    "Write": "생성됨",
    "Edit": "수정됨",
    "MultiEdit": "수정됨",
    "NotebookEdit": "수정됨",
    "Bash": "성공",
    "Read": "읽음",
    "Grep": "검색됨",
    "Glob": "검색됨",
}


def format_line(line: str, step_num: "int | None" = None, tool_names: "dict | None" = None) -> "str | None":
    """stream-json 한 줄(JSONL)을 사람용 텍스트로 변환한다.

    파싱 불가능한(중단으로 half-written 된) 라인은 None을 반환해 호출측이 건너뛸 수 있게 한다.
    """
    line = (line or "").strip()
    if not line:
        return None
    try:
        event = json.loads(line)
    except (json.JSONDecodeError, ValueError):
        return None
    if not isinstance(event, dict):
        return None
    return format_event(event, step_num=step_num, tool_names=tool_names)


def format_event(event: dict, step_num: "int | None" = None, tool_names: "dict | None" = None) -> "str | None":
    """단일 stream-json 이벤트를 사람용 텍스트로 변환한다. 무시할 이벤트는 None."""
    etype = event.get("type")
    if etype == "system":
        # init만 노출하고 hook_started/hook_response 등 메타는 버린다
        if event.get("subtype") == "init":
            model = event.get("model") or _nested_model(event) or "claude"
            return f"● {model} 세션 시작"
        return None
    if etype in ("assistant", "user"):
        lines = [
            formatted
            for block in _content_blocks(event)
            if (formatted := _format_block(block, tool_names)) is not None
        ]
        return "\n".join(lines) if lines else None
    if etype == "result":
        return _format_result(event, step_num)
    # rate_limit_event 등 나머지 메타는 버린다
    return None


# --- content block ---

def _content_blocks(event: dict) -> list:
    """assistant/user 이벤트에서 content 블록 배열을 꺼낸다 (message.content / content 모두 지원)."""
    message = event.get("message")
    if isinstance(message, dict) and isinstance(message.get("content"), list):
        return message["content"]
    if isinstance(event.get("content"), list):
        return event["content"]
    return []


def _format_block(block: dict, tool_names: "dict | None" = None) -> "str | None":
    if not isinstance(block, dict):
        return None
    btype = block.get("type")
    if btype == "text":
        return _format_text(block.get("text", ""))
    if btype == "tool_use":
        return _format_tool_use(block)
    if btype == "tool_result":
        return _format_tool_result(block, tool_names)
    return None


# --- text (모델 발화) ---

def _format_text(text: object) -> "str | None":
    """모델 발화를 💬로 시작하고 이어지는 줄은 정렬 들여쓴다. 빈 텍스트는 버린다."""
    text = str(text or "").rstrip()
    if not text:
        return None
    lines = text.splitlines()
    out = [f"💬 {lines[0]}"]
    out += [f"   {line}" for line in lines[1:]]
    return "\n".join(out)


# --- tool_use ---

def _format_tool_use(block: dict) -> str:
    name = block.get("name") or "tool"
    inp = block.get("input") if isinstance(block.get("input"), dict) else {}
    head = f"🔧 {name}"
    arg = _tool_arg(name, inp)
    if arg:
        head += f"  {arg}"
    preview = _tool_preview(name, inp)
    return f"{head}\n{preview}" if preview else head


def _tool_arg(name: str, inp: dict) -> str:
    """도구 호출의 핵심 인자 한 줄을 뽑는다."""
    if name in ("Write", "Edit", "MultiEdit", "Read", "NotebookEdit"):
        return str(inp.get("file_path", ""))
    if name == "Bash":
        return _truncate(_first_line(inp.get("command", "")), PREVIEW_CHARS)
    if name in ("Grep", "Glob"):
        return str(inp.get("pattern", ""))
    return ""


def _tool_preview(name: str, inp: dict) -> "str | None":
    """Write의 content, Edit의 new_string 같은 본문 인자 앞부분을 미리보기로 만든다."""
    if name == "Write":
        return _preview(inp.get("content", ""))
    if name in ("Edit", "MultiEdit"):
        return _preview(inp.get("new_string", ""))
    return None


def _preview(text: object) -> "str | None":
    text = str(text or "")
    if not text:
        return None
    lines = text.splitlines()
    shown = [_truncate(ln, PREVIEW_CHARS) for ln in lines[:PREVIEW_LINES]]
    out = [f"     │ {ln}" for ln in shown]
    if len(lines) > PREVIEW_LINES:
        out.append(f"     │ … (+{len(lines) - PREVIEW_LINES} lines)")
    return "\n".join(out)


# --- tool_result ---

def _format_tool_result(block: dict, tool_names: "dict | None" = None) -> str:
    """도구 호출에 종속된 결과 줄. 성공이면 도구별 동사, 실패면 사유를 들여써서 보여준다."""
    if block.get("is_error"):
        reason = _first_line(_stringify_result(block.get("content"))) or "도구 실행 실패"
        return f"     └ ❌ {_truncate(reason, PREVIEW_CHARS)}"
    tool_name = (tool_names or {}).get(block.get("tool_use_id"))
    verb = TOOL_SUCCESS_VERB.get(tool_name, "완료")
    return f"     └ ✅ {verb}"


def _stringify_result(content: object) -> str:
    """tool_result content를 문자열로 정규화한다 (str / [{type:text,text}] 모두 지원)."""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        texts = [
            part.get("text", "")
            for part in content
            if isinstance(part, dict) and part.get("type") == "text"
        ]
        return "\n".join(t for t in texts if t)
    return ""


# --- result (step 완료) ---

def _format_result(event: dict, step_num: "int | None" = None) -> str:
    is_error = bool(event.get("is_error")) or event.get("subtype") not in (None, "success")
    parts: list[str] = []
    turns = event.get("num_turns")
    if isinstance(turns, int):
        parts.append(f"{turns} turns")
    duration = event.get("duration_ms")
    if isinstance(duration, (int, float)):
        parts.append(f"{duration / 1000:.0f}s")
    cost = event.get("total_cost_usd")
    if isinstance(cost, (int, float)):
        parts.append(f"${cost:.2f}")
    meta = f"  ({', '.join(parts)})" if parts else ""
    label = f"step {step_num} " if step_num is not None else ""
    body = f"{'❌ ' + label + '실패' if is_error else '✅ ' + label + '완료'}{meta}"
    return f"{BAR}\n{body}\n{BAR}"


# --- 공통 유틸 ---

def _first_line(text: object) -> str:
    return str(text or "").strip().splitlines()[0] if str(text or "").strip() else ""


def _nested_model(event: dict) -> "str | None":
    message = event.get("message")
    return message.get("model") if isinstance(message, dict) else None


def _truncate(text: object, limit: int) -> str:
    text = str(text or "")
    return text if len(text) <= limit else text[:limit] + " …"
