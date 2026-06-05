import importlib.util
import sys
import unittest
from pathlib import Path


def load_module():
    module_path = Path(__file__).resolve().parent.parent / "format_events.py"
    spec = importlib.util.spec_from_file_location("format_events_module", module_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class FormatEventsTest(unittest.TestCase):
    def setUp(self):
        self.fe = load_module()

    # --- system ---

    def test_init_event_shows_model(self):
        out = self.fe.format_event({"type": "system", "subtype": "init", "model": "claude-opus-4-8"})
        self.assertEqual(out, "● claude-opus-4-8 세션 시작")

    def test_init_reads_model_from_message(self):
        out = self.fe.format_event(
            {"type": "system", "subtype": "init", "message": {"model": "claude-sonnet-4-6"}}
        )
        self.assertEqual(out, "● claude-sonnet-4-6 세션 시작")

    def test_hook_and_rate_limit_are_dropped(self):
        self.assertIsNone(self.fe.format_event({"type": "system", "subtype": "hook_started"}))
        self.assertIsNone(self.fe.format_event({"type": "system", "subtype": "hook_response"}))
        self.assertIsNone(self.fe.format_event({"type": "rate_limit_event"}))
        self.assertIsNone(self.fe.format_event({"type": "system", "subtype": "thinking_tokens"}))

    # --- assistant text (💬) ---

    def test_assistant_text_uses_speech_icon(self):
        out = self.fe.format_event(
            {"type": "assistant", "message": {"content": [{"type": "text", "text": "도메인 타입부터 정의하겠습니다."}]}}
        )
        self.assertEqual(out, "💬 도메인 타입부터 정의하겠습니다.")

    def test_multiline_text_aligns_following_lines(self):
        out = self.fe.format_event(
            {"type": "assistant", "content": [{"type": "text", "text": "첫째 줄\n둘째 줄"}]}
        )
        self.assertEqual(out, "💬 첫째 줄\n   둘째 줄")

    def test_empty_text_block_is_dropped(self):
        self.assertIsNone(
            self.fe.format_event({"type": "assistant", "message": {"content": [{"type": "text", "text": "   "}]}})
        )

    # --- tool_use ---

    def test_tool_use_write_shows_path_and_indented_preview(self):
        out = self.fe.format_event(
            {
                "type": "assistant",
                "message": {"content": [{"type": "tool_use", "name": "Write", "input": {"file_path": "src/domain/Money.java", "content": "hi"}}]},
            }
        )
        self.assertEqual(out, "🔧 Write  src/domain/Money.java\n     │ hi")

    def test_tool_use_bash_shows_command(self):
        out = self.fe.format_event(
            {"type": "assistant", "content": [{"type": "tool_use", "name": "Bash", "input": {"command": "./gradlew compileJava"}}]}
        )
        self.assertEqual(out, "🔧 Bash  ./gradlew compileJava")

    def test_long_content_preview_truncated_by_lines(self):
        content = "\n".join(f"line{i}" for i in range(20))
        out = self.fe.format_event(
            {"type": "assistant", "content": [{"type": "tool_use", "name": "Write", "input": {"file_path": "f", "content": content}}]}
        )
        self.assertIn("     │ line0", out)
        self.assertIn("(+12 lines)", out)

    # --- tool_result (종속 들여쓰기 + 도구별 동사) ---

    def test_tool_result_error_is_indented(self):
        out = self.fe.format_event(
            {"type": "user", "message": {"content": [{"type": "tool_result", "is_error": True, "content": "권한 미승인 (Write 거부됨)"}]}}
        )
        self.assertEqual(out, "     └ ❌ 권한 미승인 (Write 거부됨)")

    def test_tool_result_success_uses_tool_verb(self):
        # tool_names로 tool_use_id → name 매핑을 주면 도구별 동사를 붙인다
        out = self.fe.format_event(
            {"type": "user", "content": [{"type": "tool_result", "tool_use_id": "tu_1", "is_error": False, "content": "..."}]},
            tool_names={"tu_1": "Write"},
        )
        self.assertEqual(out, "     └ ✅ 생성됨")

    def test_tool_result_success_bash_verb(self):
        out = self.fe.format_event(
            {"type": "user", "content": [{"type": "tool_result", "tool_use_id": "tu_2", "content": "ok"}]},
            tool_names={"tu_2": "Bash"},
        )
        self.assertEqual(out, "     └ ✅ 성공")

    def test_tool_result_success_unknown_tool_falls_back(self):
        out = self.fe.format_event(
            {"type": "user", "content": [{"type": "tool_result", "tool_use_id": "tu_x", "content": "ok"}]}
        )
        self.assertEqual(out, "     └ ✅ 완료")

    # --- result (step 완료) ---

    def test_result_with_step_num(self):
        out = self.fe.format_event(
            {"type": "result", "subtype": "success", "num_turns": 2, "duration_ms": 14000, "total_cost_usd": 0.06},
            step_num=1,
        )
        self.assertEqual(out, f"{self.fe.BAR}\n✅ step 1 완료  (2 turns, 14s, $0.06)\n{self.fe.BAR}")

    def test_result_without_step_num(self):
        out = self.fe.format_event(
            {"type": "result", "subtype": "success", "num_turns": 1, "duration_ms": 1200, "total_cost_usd": 0.01}
        )
        self.assertEqual(out, f"{self.fe.BAR}\n✅ 완료  (1 turns, 1s, $0.01)\n{self.fe.BAR}")

    def test_result_error_with_step_num(self):
        out = self.fe.format_event({"type": "result", "subtype": "error_max_turns", "is_error": True}, step_num=3)
        self.assertEqual(out, f"{self.fe.BAR}\n❌ step 3 실패\n{self.fe.BAR}")

    # --- format_line ---

    def test_format_line_parses_jsonl(self):
        out = self.fe.format_line('{"type":"system","subtype":"init","model":"m"}')
        self.assertEqual(out, "● m 세션 시작")

    def test_format_line_skips_broken_line(self):
        self.assertIsNone(self.fe.format_line('{"type":"assist'))
        self.assertIsNone(self.fe.format_line(""))
        self.assertIsNone(self.fe.format_line("   "))


if __name__ == "__main__":
    unittest.main()
