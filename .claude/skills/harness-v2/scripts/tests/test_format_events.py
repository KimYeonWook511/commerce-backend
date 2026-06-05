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

    # --- assistant ---

    def test_assistant_text_is_indented(self):
        out = self.fe.format_event(
            {"type": "assistant", "message": {"content": [{"type": "text", "text": "I/O 작업이라 바로 진행하겠습니다."}]}}
        )
        self.assertEqual(out, "  I/O 작업이라 바로 진행하겠습니다.")

    def test_multiline_text_indents_each_line(self):
        out = self.fe.format_event(
            {"type": "assistant", "content": [{"type": "text", "text": "첫째 줄\n둘째 줄"}]}
        )
        self.assertEqual(out, "  첫째 줄\n  둘째 줄")

    def test_empty_text_block_is_dropped(self):
        self.assertIsNone(
            self.fe.format_event({"type": "assistant", "message": {"content": [{"type": "text", "text": "   "}]}})
        )

    def test_tool_use_write_shows_path_and_preview(self):
        out = self.fe.format_event(
            {
                "type": "assistant",
                "message": {"content": [{"type": "tool_use", "name": "Write", "input": {"file_path": "src/hello.txt", "content": "hi"}}]},
            }
        )
        self.assertEqual(out, "🔧 Write  src/hello.txt\n   │ hi")

    def test_tool_use_bash_shows_command(self):
        out = self.fe.format_event(
            {"type": "assistant", "content": [{"type": "tool_use", "name": "Bash", "input": {"command": "git status"}}]}
        )
        self.assertEqual(out, "🔧 Bash  git status")

    def test_tool_use_edit_shows_path_and_new_string_preview(self):
        out = self.fe.format_event(
            {
                "type": "assistant",
                "content": [{"type": "tool_use", "name": "Edit", "input": {"file_path": "a.py", "old_string": "x", "new_string": "y"}}],
            }
        )
        self.assertEqual(out, "🔧 Edit  a.py\n   │ y")

    def test_long_content_preview_is_truncated_by_lines(self):
        content = "\n".join(f"line{i}" for i in range(20))
        out = self.fe.format_event(
            {"type": "assistant", "content": [{"type": "tool_use", "name": "Write", "input": {"file_path": "f", "content": content}}]}
        )
        self.assertIn("   │ line0", out)
        self.assertIn("(+12 lines)", out)  # 20줄 중 8줄 표시 후 나머지 12줄

    # --- tool_result ---

    def test_tool_result_error_shows_reason(self):
        out = self.fe.format_event(
            {"type": "user", "message": {"content": [{"type": "tool_result", "is_error": True, "content": "권한 미승인 (Write 거부됨)"}]}}
        )
        self.assertEqual(out, "❌ tool_result  권한 미승인 (Write 거부됨)")

    def test_tool_result_success_shows_snippet(self):
        out = self.fe.format_event(
            {"type": "user", "content": [{"type": "tool_result", "is_error": False, "content": "OK"}]}
        )
        self.assertEqual(out, "✓ OK")

    def test_tool_result_content_list_is_stringified(self):
        out = self.fe.format_event(
            {"type": "user", "content": [{"type": "tool_result", "content": [{"type": "text", "text": "done"}]}]}
        )
        self.assertEqual(out, "✓ done")

    # --- result ---

    def test_result_success_shows_metrics(self):
        out = self.fe.format_event(
            {"type": "result", "subtype": "success", "num_turns": 2, "duration_ms": 6600, "total_cost_usd": 0.08}
        )
        self.assertEqual(out, "✅ 완료  (2 turns, 6.6s, $0.08)")

    def test_result_error(self):
        out = self.fe.format_event({"type": "result", "subtype": "error_max_turns", "is_error": True})
        self.assertEqual(out, "❌ 실패")

    # --- format_line ---

    def test_format_line_parses_jsonl(self):
        out = self.fe.format_line('{"type":"system","subtype":"init","model":"m"}')
        self.assertEqual(out, "● m 세션 시작")

    def test_format_line_skips_broken_line(self):
        self.assertIsNone(self.fe.format_line('{"type":"assist'))  # half-written
        self.assertIsNone(self.fe.format_line(""))
        self.assertIsNone(self.fe.format_line("   "))


if __name__ == "__main__":
    unittest.main()
