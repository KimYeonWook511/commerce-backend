#!/usr/bin/env python3
from __future__ import annotations

"""spec-harness-v1 execute.py — 서브커맨드 모음 (오케스트레이터가 아님).

이 스크립트의 서브커맨드들은 workflow(JS)가 직접 못 하는 shell/git/fs 작업을 대신 수행한다. 오케스트레이션 자체는
JavaScript workflow(spec-harness-v1-execute)로 옮겨갔고, execute.py는 **workflow 스크립트(JS)가
직접 못 하는 일(파일시스템·shell 접근)을 agent가 CLI로 호출할 수 있게 노출한 입구 모음**이 된다.

서브커맨드:
  preflight       phase index.json을 읽어 {steps, execution}을 workflow args(JSON)로 stdout 출력
  build-context   현재 step의 developer 컨텍스트(CLAUDE.md·spec문서·컨벤션요약·이전step summary)를 조립해 출력
  verify-ac       step의 Acceptance Criteria를 실행·판정(acceptance_check) → ac-output.json 누적 + 결과 JSON 출력
  record-step     phase index.json에 step.status/summary/completed_at 기록 (recorder agent 전용)
  reset-step      blocked/error step을 pending으로 되돌림 (사람이 원인을 고친 뒤 재개용)
  set-stage       spec 레벨 checklist의 Stage 6/7/8 상태 갱신 (메인의 Execution 자동 흐름 + PR Review/Root Sync — preflight·finalize는 안 건드림)
  finalize        phase 닫기: 이 phase의 completed_at·spec index 동기화(워킹트리) + 선택적 push (finalizer agent 전용)

설계 원칙:
  - 함수 기반: 셔틀 상태가 없고 각 서브커맨드가 한 번 실행되고 끝이므로 클래스가 불필요.
  - 각 서브커맨드는 결과를 stdout에 JSON으로 출력한다(agent가 그 JSON을 받아 workflow에 반환).
  - 무거운 로직은 모듈(acceptance_check/step_context/git_ops)에 있고, 여기선 입구만 얇게.
"""

import argparse
import json
import sys
from datetime import datetime, timezone, timedelta
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import acceptance_check
import git_ops
import step_context


# ─────────────────────────────────────────────────────────────────────────────
# 공통 헬퍼
# ─────────────────────────────────────────────────────────────────────────────
def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: dict) -> None:
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")


_KST = timezone(timedelta(hours=9))


def stamp() -> str:
    return datetime.now(_KST).isoformat()


def emit(payload: dict) -> None:
    """결과를 stdout에 JSON 한 줄로 출력한다(agent가 파싱해 workflow에 반환)."""
    sys.stdout.write(json.dumps(payload, ensure_ascii=False))
    sys.stdout.write("\n")
    sys.stdout.flush()


_WORKFLOW_ITEMS = [
    (1, "Explore"),
    (2, "Specify"),
    (3, "Clarify"),
    (4, "Plan + Tasks"),
    (5, "Analyze"),
    (6, "Execution"),
    (7, "PR Review"),
    (8, "Root Sync"),
]
# 단계 목록은 여러 곳에 동기화돼야 한다(이 리스트 / _template/workflow-checklist.json /
# SKILL.md 상태표·각 ### 헤더 / phase-files.md 예시 / docs 요약본 mermaid / 아래 게이트 경계).
# 항목 개수는 len()으로 동적 처리되지만, 게이트 경계 리터럴(EXECUTION_ORDER)은 별도이므로 함께 갱신해야 한다.

# Execution 단계의 order. 이 앞(1..EXECUTION_ORDER-1)은 모두 completed여야 실행 게이트를 통과한다.
# Worktree 생성은 Specify(2)에 흡수되었고, Execution은 6번이다.
EXECUTION_ORDER = 6


def validate_workflow_checklist(checklist_dir: Path) -> dict:
    """실행 전 게이트: 문서 검토·실행 승인 완료 여부를 강제한다.

    checklist는 spec 레벨에 하나다(`<spec>/workflow-checklist.json`).
    Execution(6) 직전 단계(1~5: Explore·Specify·Clarify·Plan+Tasks·Analyze)가
    모두 completed이고 Execution(6)이 pending/in_progress여야 통과.
    통과하면 {"ok": True}, 아니면 {"ok": False, "error": ...}를 반환한다.
    (게이트 미통과 시 preflight는 emit으로 알리고 종료한다.)

    참고: "Clarify 위험마커 0 + Analyze 통과"라는 의미 게이트는 별도 코드 검사가 아니라,
    Clarify(3)·Analyze(5) 단계를 completed로 표시하는 행위가 그 조건의 충족을 뜻한다는 규약으로 강제한다
    (마커가 남았으면 Clarify를 completed로 두지 않고, Analyze가 실패하면 Analyze를 completed로 두지 않는다).
    """
    checklist_path = checklist_dir / "workflow-checklist.json"
    if not checklist_path.exists():
        return {"ok": False, "error": f"workflow-checklist.json 없음: {checklist_path}. "
                "Stage 1~5(Explore~Analyze)를 마치고 실행 승인을 기록한 checklist를 만든 뒤 실행하라."}

    checklist = read_json(checklist_path)
    if checklist.get("workflow") != "harness":
        return {"ok": False, "error": "workflow-checklist.json의 workflow가 'harness'가 아니다."}

    items = checklist.get("items")
    if not isinstance(items, list) or len(items) != len(_WORKFLOW_ITEMS):
        return {"ok": False, "error": f"workflow-checklist.json은 {len(_WORKFLOW_ITEMS)}개 harness workflow 항목을 가져야 한다."}

    invalid, incomplete = [], []
    for idx, (order, title) in enumerate(_WORKFLOW_ITEMS):
        item = items[idx] if idx < len(items) else {}
        if not isinstance(item, dict) or item.get("order") != order or item.get("title") != title:
            invalid.append(f"{order}. {title}")
            continue
        status = item.get("status")
        if order < EXECUTION_ORDER and status != "completed":
            incomplete.append(title)
        if order == EXECUTION_ORDER and status not in {"pending", "in_progress"}:
            invalid.append(f"{order}. {title} status must be pending or in_progress")

    if invalid:
        return {"ok": False, "error": "workflow-checklist.json 항목이 올바르지 않다.", "invalid": invalid}
    observed = {it.get("order") for it in items if isinstance(it, dict)}
    if {o for o, _ in _WORKFLOW_ITEMS} != observed:
        return {"ok": False, "error": "workflow-checklist.json에 누락/중복된 order가 있다."}
    if incomplete:
        return {"ok": False, "error": "harness workflow가 실행 승인되지 않았다(Execution 직전 단계 미완).", "incomplete": incomplete}
    return {"ok": True}


def update_workflow_item(checklist_dir: Path, title: str, status: str) -> bool:
    """workflow-checklist.json의 단일 항목 상태를 갱신한다. 파일/항목 없으면 False.

    checklist는 spec 레벨에 하나다(`<spec>/workflow-checklist.json`).
    preflight·finalize는 이 함수를 부르지 않는다(그들은 phase 단위라 spec 레벨 stage를 건드리면 안 됨).
    Stage 6(Execution)은 메인이 자동 흐름으로 `set-stage`를 호출해 갱신한다(진입 시 in_progress,
    phase 루프를 다 돈 뒤 completed). Stage 7/8(PR Review·Root Sync)은 사람 판단 시점에 `set-stage`로 갱신한다.
    """
    checklist_path = checklist_dir / "workflow-checklist.json"
    if not checklist_path.exists():
        return False
    checklist = read_json(checklist_path)
    ts = stamp()
    matched = False
    for item in checklist.get("items", []):
        if item.get("title") == title:
            item["status"] = status
            if status == "completed":
                item["completed_at"] = ts
            elif status == "in_progress":
                item.setdefault("started_at", ts)
                item.pop("completed_at", None)
            matched = True
            break
    write_json(checklist_path, checklist)
    return matched


def resolve_paths(phase_dir_arg: str) -> dict:
    """phase 디렉터리 경로로부터 자주 쓰는 경로들을 계산한다.

    레이아웃: docs/specs/<spec>/phases/<phase>/
      phase_dir            = .../phases/<phase>
      phase index.json     = phase_dir/index.json
      spec phases dir      = .../phases
      spec phases index    = .../phases/index.json
      spec dir            = .../<spec>   (spec 문서 spec/architecture/... 위치)
      root                 = git worktree 루트 (cwd 기준)
    """
    phase_dir = Path(phase_dir_arg).resolve()
    spec_phases_dir = phase_dir.parent           # .../phases
    spec_dir = spec_phases_dir.parent            # .../<spec>
    # root: cwd의 git 최상위 (worktree 루트). 실패 시 cwd.
    top = git_ops.run_git(str(Path.cwd()), "rev-parse", "--show-toplevel")
    root = top.stdout.strip() if top.returncode == 0 and top.stdout.strip() else str(Path.cwd())
    root_path = Path(root)
    return {
        "phase_dir": phase_dir,
        "phase_index": phase_dir / "index.json",
        "spec_phases_dir": spec_phases_dir,
        "spec_index": spec_phases_dir / "index.json",
        "spec_dir": spec_dir,
        "root": root,
        "root_path": root_path,
        "phase_name": phase_dir.name,
        "phase_relpath": phase_dir.relative_to(root_path).as_posix() if str(phase_dir).startswith(root) else str(phase_dir),
        "spec_phases_relpath": spec_phases_dir.relative_to(root_path).as_posix() if str(spec_phases_dir).startswith(root) else str(spec_phases_dir),
    }


def step_file_path(phase_dir: Path, n: int) -> Path:
    return phase_dir / f"step{n}.md"


# ─────────────────────────────────────────────────────────────────────────────
# preflight — index.json → workflow args
# ─────────────────────────────────────────────────────────────────────────────
def cmd_preflight(args) -> int:
    """phase index.json을 읽어 workflow에 넘길 args(steps + execution)를 stdout JSON으로 출력.

    workflow(JS)는 파일을 못 읽으므로, 세션이 이 출력을 받아 /spec-harness-v1-execute <args>로 넘긴다.
    """
    p = resolve_paths(args.phase_dir)
    if not p["phase_index"].exists():
        emit({"ok": False, "error": f"phase index 없음: {p['phase_index']}"})
        return 1
    index = read_json(p["phase_index"])

    # 실행 전 게이트: Execution 직전 단계(1~5) 완료 + 실행 승인을 강제한다. 통과 못하면 여기서 멈춘다.
    gate = validate_workflow_checklist(p["spec_dir"])
    if not gate["ok"]:
        emit(gate)
        return 1

    # 주의: checklist의 Execution(Stage 6) 상태는 여기서 갱신하지 않는다. checklist는 spec 레벨
    # 하나이고 preflight는 phase마다 돌므로, 여기서 in_progress로 바꾸면 phase가 여러 개일 때
    # spec 전체 진행 상태가 흔들린다. Execution(6)은 메인이 자동 흐름(진입 시 in_progress,
    # phase 루프 후 completed)에서 set-stage로 갱신한다.

    # hook이 phase별 로그 디렉터리(<phase>/logs)를 찾도록 active-phase 마커를 남긴다.
    # (preflight가 이 마커를 쓰고, hook이 읽는다.)
    try:
        marker_dir = p["root_path"] / ".harness"
        marker_dir.mkdir(parents=True, exist_ok=True)
        (marker_dir / "active-phase").write_text(p["phase_relpath"], encoding="utf-8")
    except OSError:
        pass  # 마커 실패는 치명적이지 않다(로그가 fallback 위치로 갈 뿐)

    steps = [
        {"step": s["step"], "name": s.get("name", ""), "status": s.get("status", "pending")}
        for s in index.get("steps", [])
    ]
    execution = index.get("execution", {})

    emit({
        "ok": True,
        "execute": str(Path(__file__).resolve()),
        "phase_dir": str(p["phase_dir"]),
        "phase_relpath": p["phase_relpath"],
        "project": index.get("project", ""),
        "phase": index.get("phase", p["phase_name"]),
        "steps": steps,
        "execution": {
            "developer_model": execution.get("developer_model", "sonnet"),
            "reviewer_model": execution.get("reviewer_model", "opus"),
            "committer_model": execution.get("commit_model", execution.get("committer_model", "haiku")),
            "push": execution.get("push", True),
        },
    })
    return 0


# ─────────────────────────────────────────────────────────────────────────────
# build-context — developer 컨텍스트 조립
# ─────────────────────────────────────────────────────────────────────────────
def cmd_build_context(args) -> int:
    """현재 step의 developer 컨텍스트를 조립해 출력한다.

    이전 step summary는 실행 중에 생기므로(preflight 시점엔 없음) developer 턴에 호출한다.
    """
    p = resolve_paths(args.phase_dir)
    n = args.step
    sf = step_file_path(p["phase_dir"], n)
    if not sf.exists():
        emit({"ok": False, "error": f"step 문서 없음: {sf}"})
        return 1
    step_text = sf.read_text(encoding="utf-8")

    # 정적 컨텍스트(CLAUDE.md·spec문서·컨벤션요약·step이 참조한 문서)
    doc_context = step_context.load_step_documents(p["root_path"], p["spec_dir"], step_text)

    # 이전 완료 step들의 summary (phase index에서)
    prev = ""
    if p["phase_index"].exists():
        index = read_json(p["phase_index"])
        prev = step_context.build_previous_step_context(index)

    emit({
        "ok": True,
        "step": n,
        "context": doc_context,
        "previous_steps": prev,
        "step_text": step_text,
    })
    return 0


# ─────────────────────────────────────────────────────────────────────────────
# verify-ac — Acceptance Criteria 실행·판정
# ─────────────────────────────────────────────────────────────────────────────
def cmd_verify_ac(args) -> int:
    """step의 AC를 실행·판정(acceptance_check)하고 ac-output.json에 누적. 결과 JSON 출력.

    developer agent가 구현 끝에 호출한다. AC가 없으면 passed=true(검사할 게 없음).
    """
    p = resolve_paths(args.phase_dir)
    n = args.step
    sf = step_file_path(p["phase_dir"], n)
    if not sf.exists():
        emit({"ok": False, "error": f"step 문서 없음: {sf}"})
        return 1
    step_text = sf.read_text(encoding="utf-8")

    result = acceptance_check.check(
        root=p["root"],
        phase_dir=p["phase_dir"],
        write_json=write_json,
        step={"step": n},
        step_text=step_text,
        attempt=args.attempt,
    )
    if result is None:
        # AC 명령이 없는 step → 검사할 게 없으므로 통과로 본다.
        emit({"ok": True, "step": n, "attempt": args.attempt, "passed": True, "results": [], "no_ac": True})
        return 0

    emit({
        "ok": True,
        "step": n,
        "attempt": args.attempt,
        "passed": result["passed"],
        "results": result["results"],
    })
    return 0


# ─────────────────────────────────────────────────────────────────────────────
# record-step — phase index.json에 step 완료 기록 (recorder 전용)
# ─────────────────────────────────────────────────────────────────────────────
def cmd_record_step(args) -> int:
    """phase index.json의 step status/summary/completed_at을 기록한다.

    recorder agent가 step 완료 시 호출한다. 매 step 영속 기록이라 재실행 시
    완료된 step을 건너뛸 수 있다(JS 변수/런타임 저널은 실행 경계를 못 넘으므로 필요).
    """
    p = resolve_paths(args.phase_dir)
    if not p["phase_index"].exists():
        emit({"ok": False, "error": f"phase index 없음: {p['phase_index']}"})
        return 1
    index = read_json(p["phase_index"])

    target = next((s for s in index.get("steps", []) if s.get("step") == args.step), None)
    if target is None:
        emit({"ok": False, "error": f"step {args.step} not found in index"})
        return 1

    target["status"] = args.status
    if args.summary:
        target["summary"] = args.summary
    ts = stamp()
    if args.status == "completed":
        target["completed_at"] = ts
        for stale in ("error_message", "blocked_reason"):
            target.pop(stale, None)
    elif args.status == "blocked":
        target["blocked_at"] = ts
        if args.reason:
            target["blocked_reason"] = args.reason
    elif args.status == "error":
        target["failed_at"] = ts
        if args.reason:
            target["error_message"] = args.reason

    write_json(p["phase_index"], index)
    emit({"ok": True, "step": args.step, "status": args.status})
    return 0


def cmd_reset_step(args) -> int:
    """blocked/error로 멈춘 step을 pending으로 되돌린다(사람이 원인을 고친 뒤 호출).

    재시도/재개의 명시적 신호다. blocked/error 관련 잔여 필드를 모두 제거해 정본을
    깨끗한 pending 상태로 만든다. 이렇게 해야 workflow 재실행 시 그 step이 다시 잡힌다.
    (status를 바꾸지 않은 채 재실행하면 workflow가 자동 재개하지 않으므로, 안 고친 채
    같은 실패를 반복하며 토큰을 낭비하는 일이 없다.)
    """
    p = resolve_paths(args.phase_dir)
    if not p["phase_index"].exists():
        emit({"ok": False, "error": f"phase index 없음: {p['phase_index']}"})
        return 1
    index = read_json(p["phase_index"])

    target = next((s for s in index.get("steps", []) if s.get("step") == args.step), None)
    if target is None:
        emit({"ok": False, "error": f"step {args.step} not found in index"})
        return 1

    prev = target.get("status", "pending")
    target["status"] = "pending"
    for stale in ("summary", "error_message", "blocked_reason",
                  "completed_at", "failed_at", "blocked_at"):
        target.pop(stale, None)

    write_json(p["phase_index"], index)
    emit({"ok": True, "step": args.step, "status": "pending", "previous_status": prev})
    return 0


def cmd_set_stage(args) -> int:
    """spec 레벨 checklist의 한 Stage 상태를 갱신한다.

    checklist는 spec 레벨 하나이고 Stage 6/7/8은 spec 전체의 진행이므로, phase 단위로 도는
    preflight·finalize가 아니라 이 명령으로 spec 단위에 한 번씩 갱신한다.
      - Stage 6(Execution): 메인이 자동 흐름에서 호출한다 — 진입 시 in_progress, phase 루프를 다 돈 뒤 completed.
          set-stage <spec> Execution in_progress
          set-stage <spec> Execution completed
      - Stage 7/8(PR Review/Root Sync): 리뷰·승격 등 사람 판단 시점에 같은 방식으로 갱신한다.
    """
    checklist_dir = Path(args.checklist_dir).resolve()
    matched = update_workflow_item(checklist_dir, args.stage, args.status)
    if not matched:
        emit({"ok": False, "error": f"checklist에서 '{args.stage}' 항목을 찾지 못했다(경로: {checklist_dir}). "
              "checklist_dir는 spec 디렉터리(workflow-checklist.json이 있는 spec 루트)여야 하고 stage는 정확한 Stage 제목이어야 한다."})
        return 1
    emit({"ok": True, "stage": args.stage, "status": args.status})
    return 0


def cmd_finalize(args) -> int:
    """phase 닫기: 이 phase의 completed_at 기록·spec index 동기화(워킹트리) + 선택적 push.

    finalizer agent가 phase 끝에 호출한다 — workflow 한 번 기동 = phase 하나 완주.
    spec 폴더(phase index 포함)는 .gitignore 대상이라 커밋하지 않는다 — 여기서는
    워킹트리 상태만 갱신하고, committer가 만든 코드 커밋을 원격으로 push한다.

    checklist의 Execution(Stage 6) 상태는 건드리지 않는다. checklist는 spec 레벨 하나이고
    8-Stage는 spec 전체의 진행이므로, phase 하나가 끝났다고 Execution(6)을 completed로 만들면 안 된다
    (다른 phase가 남아 있을 수 있다). Execution completed는 모든 phase를 마친 뒤 메인이 자동 흐름에서 set-stage로 찍는다.
    push 의도는 index.execution.push에서 읽는다(인자 --no-push로도 강제 비활성 가능).
    """
    p = resolve_paths(args.phase_dir)
    root = p["root"]
    index = read_json(p["phase_index"]) if p["phase_index"].exists() else {}

    # 이 phase의 completed_at 기록 (워킹트리 상태 — 커밋하지 않음)
    index["completed_at"] = stamp()
    write_json(p["phase_index"], index)

    # spec index의 이 phase status=completed 동기화 (워킹트리 상태)
    _update_spec_index(p, "completed")

    # push 의도: index.execution.push AND not --no-push
    recorded_push = index.get("execution", {}).get("push", True)
    auto_push = bool(recorded_push) and (not args.no_push)

    # 선택적 push (committer가 만든 코드 커밋을 원격으로 올린다)
    pushed = False
    branch = ""
    if auto_push:
        b = git_ops.run_git(root, "rev-parse", "--abbrev-ref", "HEAD")
        branch = b.stdout.strip()
        if not branch:
            emit({"ok": False, "error": "push할 브랜치명을 확인할 수 없습니다(빈 refspec 방지)."})
            return 1
        r = git_ops.run_git(root, "push", "-u", "origin", branch)
        if r.returncode != 0:
            emit({"ok": False, "error": f"git push 실패: {r.stderr.strip()}"})
            return 1
        pushed = True

    emit({
        "ok": True,
        "phase": p["phase_name"],
        "pushed": pushed,
        "branch": branch,
        "push_skipped": (not auto_push),
    })
    return 0


def _update_spec_index(p: dict, status: str) -> None:
    """spec phases index.json의 현재 phase status를 동기화한다(존재할 때만)."""
    if not p["spec_index"].exists():
        return
    spec_data = read_json(p["spec_index"])
    ts = stamp()
    key = {"completed": "completed_at", "error": "failed_at", "blocked": "blocked_at"}.get(status)
    for phase in spec_data.get("phases", []):
        if phase.get("phase") == p["phase_name"]:
            phase["status"] = status
            for stale in ("completed_at", "failed_at", "blocked_at"):
                if stale != key:
                    phase.pop(stale, None)
            if key:
                phase[key] = ts
            break
    write_json(p["spec_index"], spec_data)


# ─────────────────────────────────────────────────────────────────────────────
# CLI
# ─────────────────────────────────────────────────────────────────────────────
def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="execute.py", description="spec-harness-v1 subcommands")
    sub = parser.add_subparsers(dest="command", required=True)

    p_pre = sub.add_parser("preflight", help="phase index.json → workflow args(JSON)")
    p_pre.add_argument("phase_dir")
    p_pre.set_defaults(func=cmd_preflight)

    p_ctx = sub.add_parser("build-context", help="developer 컨텍스트 조립")
    p_ctx.add_argument("phase_dir")
    p_ctx.add_argument("--step", type=int, required=True)
    p_ctx.set_defaults(func=cmd_build_context)

    p_ac = sub.add_parser("verify-ac", help="Acceptance Criteria 실행·판정")
    p_ac.add_argument("phase_dir")
    p_ac.add_argument("--step", type=int, required=True)
    p_ac.add_argument("--attempt", type=int, default=1)
    p_ac.set_defaults(func=cmd_verify_ac)

    p_rec = sub.add_parser("record-step", help="phase index에 step 완료 기록")
    p_rec.add_argument("phase_dir")
    p_rec.add_argument("--step", type=int, required=True)
    p_rec.add_argument("--status", required=True, choices=["completed", "blocked", "error", "pending", "in_progress"])
    p_rec.add_argument("--summary", default="")
    p_rec.add_argument("--reason", default="")
    p_rec.set_defaults(func=cmd_record_step)

    p_reset = sub.add_parser("reset-step", help="blocked/error step을 pending으로 되돌림(사람이 고친 뒤)")
    p_reset.add_argument("phase_dir")
    p_reset.add_argument("--step", type=int, required=True)
    p_reset.set_defaults(func=cmd_reset_step)

    p_stage = sub.add_parser("set-stage", help="spec 레벨 checklist의 Stage 상태 갱신(메인의 Execution 자동 흐름 + PR Review/Root Sync)")
    p_stage.add_argument("checklist_dir", help="spec 디렉터리(workflow-checklist.json이 있는 spec 루트)")
    p_stage.add_argument("stage", help="Stage 제목 (예: Execution, PR Review, Root Sync)")
    p_stage.add_argument("status", choices=["pending", "in_progress", "completed"])
    p_stage.set_defaults(func=cmd_set_stage)

    p_fin = sub.add_parser("finalize", help="phase 닫기(이 phase 상태 동기화·push)")
    p_fin.add_argument("phase_dir")
    p_fin.add_argument("--no-push", action="store_true")
    p_fin.set_defaults(func=cmd_finalize)

    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
