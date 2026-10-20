#!/usr/bin/env python3
"""Check Claude relay context flags against a loopback mock, without using a real account.

Run all three modes by default. Each invocation gets a fresh synthetic HOME, Claude config,
project, instructions, memory, skill, and @file. A synthetic API key points at a local HTTP
server that rejects every request, so no model executes. Only Boolean summaries are printed;
CLI stdout/stderr and request bodies are never printed or persisted.

The unguarded control deliberately permits local context loading, but still disables tools.
Its expected positives are instructions, memory, settings, and the @file attachment. The
personal skill fixture is checked for regressions in guarded modes; because --tools "" also
suppresses the skill catalog on tested Claude versions, its absence is not a positive-control
proof of general skill isolation. The restricted baseline expectations were verified on
Claude Code 2.1.300; an upgrade can change those results. This tests prompt construction,
not subscription login.
"""
from __future__ import annotations

import argparse
import http.server
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading

MARKERS = {
    "user_instructions": "CANARY_USER_INSTRUCTIONS",
    "project_instructions": "CANARY_PROJECT_INSTRUCTIONS",
    "auto_memory": "CANARY_AUTO_MEMORY",
    "private_file": "CANARY_PRIVATE_FILE",
    "personal_skill": "CANARY_PERSONAL_SKILL",
}
SETTINGS_MARKER = "CANARY_USER_SETTINGS"
API_KEY = "synthetic-justbuild-canary-key"
MODES = ("unguarded", "restricted", "hardened")
MAX_REQUEST_BYTES = 2 * 1024 * 1024
SCHEMA = json.dumps({
    "type": "object",
    "properties": {"answer": {"type": "string"}},
    "required": ["answer"],
})


def fixtures(root: Path) -> tuple[Path, Path, Path]:
    home = root / "home"
    work = root / "work"
    config = home / ".claude"
    work.mkdir()
    config.mkdir(parents=True)
    (config / "CLAUDE.md").write_text(MARKERS["user_instructions"], encoding="utf-8")
    (work / "CLAUDE.md").write_text(MARKERS["project_instructions"], encoding="utf-8")
    (root / "private.txt").write_text(MARKERS["private_file"], encoding="utf-8")
    # Claude keys per-project memory by the absolute project path, with punctuation replaced.
    project_key = "".join(c if c.isalnum() else "-" for c in str(work.resolve()))
    memory = config / "projects" / project_key / "memory"
    memory.mkdir(parents=True)
    (memory / "MEMORY.md").write_text(MARKERS["auto_memory"], encoding="utf-8")
    # A custom HTTP header makes settings loading observable without executing a hook or tool.
    (config / "settings.json").write_text(json.dumps({"env": {
        "ANTHROPIC_CUSTOM_HEADERS": "X-JustBuild-Settings-Canary: " + SETTINGS_MARKER,
    }}), encoding="utf-8")
    skill = config / "skills" / "canary"
    skill.mkdir(parents=True)
    (skill / "SKILL.md").write_text(
        "---\nname: canary\ndescription: " + MARKERS["personal_skill"]
        + "\n---\n" + MARKERS["personal_skill"], encoding="utf-8")
    return home, work, config


def run_mode(executable: str, mode: str, timeout: float) -> dict[str, bool]:
    with tempfile.TemporaryDirectory(prefix="justbuild-claude-canary-") as temporary:
        root = Path(temporary).resolve()
        home, work, config = fixtures(root)
        captured: list[dict[str, bool]] = []
        capture_lock = threading.Lock()

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self) -> None:
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                    if not 0 < length <= MAX_REQUEST_BYTES:
                        self.send_error(413)
                        return
                    body = json.loads(self.rfile.read(length))
                    # Capture actual model requests, not unrelated startup/telemetry requests.
                    if self.path.split("?", 1)[0] == "/v1/messages" and isinstance(body, dict):
                        text = json.dumps(body)
                        observed = {name: marker in text for name, marker in MARKERS.items()}
                        observed["user_settings"] = (
                            self.headers.get("X-JustBuild-Settings-Canary") == SETTINGS_MARKER)
                        observed["synthetic_auth"] = (
                            self.headers.get("x-api-key") == API_KEY
                            and self.headers.get("authorization") is None)
                        tools = body.get("tools") or []
                        # --json-schema uses this non-executable response-format carrier. No file, shell,
                        # network, skill, or other executable tool may accompany it.
                        observed["executable_tools_disabled"] = isinstance(tools, list) and len(tools) <= 1 and all(
                            isinstance(tool, dict) and tool.get("name") == "StructuredOutput"
                            and tool.get("input_schema") == json.loads(SCHEMA) for tool in tools)
                        with capture_lock:
                            captured.append(observed)
                except (ValueError, OSError):
                    self.send_error(400)
                    return
                self.send_response(400)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"type":"error","error":{"type":"invalid_request_error",'
                                 b'"message":"synthetic canary completed"}}')

            def do_GET(self) -> None:
                self.send_error(403)

            def do_CONNECT(self) -> None:
                # Any client honoring the proxy variables cannot tunnel outside this mock.
                self.send_error(403)

            def log_message(self, *_args: object) -> None:
                pass

        with http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
            server.daemon_threads = True
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            base = f"http://127.0.0.1:{server.server_port}"
            # Do not inherit provider credentials, OAuth tokens, context switches, or proxy settings.
            env = {
                "PATH": os.environ.get("PATH", os.defpath),
                "HOME": str(home),
                "TMPDIR": str(root),
                "LANG": "en_US.UTF-8",
                "CLAUDE_CONFIG_DIR": str(config),
                "ANTHROPIC_API_KEY": API_KEY,
                "ANTHROPIC_BASE_URL": base,
                "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC": "1",
                "HTTP_PROXY": base,
                "HTTPS_PROXY": base,
                "ALL_PROXY": base,
                "NO_PROXY": "127.0.0.1,localhost",
                "http_proxy": base,
                "https_proxy": base,
                "all_proxy": base,
                "no_proxy": "127.0.0.1,localhost",
            }
            command = [executable, "-p", "--output-format", "stream-json", "--verbose",
                       "--include-partial-messages", "--json-schema", SCHEMA,
                       "--system-prompt", "Return only an answer object.", "--tools", "",
                       "--strict-mcp-config", "--no-session-persistence"]
            if mode != "unguarded":
                command.append("--restricted")
            if mode == "hardened":
                # Keep aligned with ClaudeCliProvider.command() and isolatedContext().
                command.extend(["--safe-mode", "--disable-slash-commands"])
                env.update({
                    "CLAUDE_CODE_DISABLE_CLAUDE_MDS": "1",
                    "CLAUDE_CODE_DISABLE_AUTO_MEMORY": "1",
                    "CLAUDE_CODE_DISABLE_ATTACHMENTS": "1",
                })
            completed = False
            try:
                subprocess.run(command, input="Return an empty answer. @" + str(root / "private.txt"),
                               text=True, cwd=work, env=env, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, timeout=timeout, check=False)
                completed = True  # A nonzero exit is expected: the mock refuses the model request.
            except (subprocess.TimeoutExpired, OSError):
                pass
            finally:
                server.shutdown()
                thread.join(timeout=1)

        observed = {name: any(request[name] for request in captured)
                    for name in (*MARKERS, "user_settings")}
        summary = {
            "completed": completed,
            "captured_request": bool(captured),
            "synthetic_auth_only": bool(captured) and all(request["synthetic_auth"] for request in captured),
            "executable_tools_disabled": bool(captured) and all(
                request["executable_tools_disabled"] for request in captured),
            **observed,
        }
        if mode == "unguarded":
            expected = all(observed[name] for name in (
                "user_instructions", "project_instructions", "auto_memory", "private_file", "user_settings"))
        else:
            expected = not any(observed.values())
        summary["expected_context"] = expected
        summary["passed"] = all(summary[key] for key in (
            "completed", "captured_request", "synthetic_auth_only", "executable_tools_disabled", "expected_context"))
        return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--claude", default="claude", help="Claude executable name or path (default: claude)")
    parser.add_argument("--mode", choices=("all", *MODES), default="all", help="default: all three controls")
    parser.add_argument("--timeout", type=float, default=30, help="seconds per mode (default: 30)")
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    executable = shutil.which(args.claude)
    if executable is None:
        print(json.dumps({"binary_found": False, "passed": False}))
        return 2
    executable = str(Path(executable).absolute())
    modes = MODES if args.mode == "all" else (args.mode,)
    passed = True
    for mode in modes:
        result = run_mode(executable, mode, args.timeout)
        print(json.dumps({mode: result}, sort_keys=True), flush=True)
        passed = passed and result["passed"]
    print(json.dumps({"passed": passed}))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
