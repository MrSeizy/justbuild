#!/usr/bin/env python3
"""Exercise Codex auth isolation with fake credentials and localhost endpoints.

Usage: python3 scripts/security/codex-auth-isolation-canary.py [--codex PATH]

Requires Python 3.9+ and a Codex CLI supporting the provider's current flags.
Verified with codex-cli 0.162.1. No real HOME, CODEX_HOME, auth.json, or config
contents are read. Every CLI invocation uses disposable synthetic homes. No
live model is called: the local mock rejects inference after capturing its
request. Token refresh succeeds against a local mock with fake tokens only.

The model-visible arguments are extracted from CodexCliProvider.command(), so
this canary also detects regressions in that method. It does not replace Java
unit tests of the provider's environment and symlink construction.

The mock additionally acts as a rejecting HTTP proxy for unexpected outbound
requests. An installed CLI remains trusted executable code; this is a request
regression test, not an operating-system network sandbox.
"""

import argparse
import base64
import http.server
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import threading
from urllib.parse import urlsplit


SOURCE = (
    Path(__file__).resolve().parents[2]
    / "src/main/java/dev/justbuild/provider/CodexCliProvider.java"
)
JAVA_STRING = r'"(?:\\.|[^"\\])*"'
MARKERS = (
    "CANARY_PERSONAL_AGENTS",
    "CANARY_PERSONAL_OVERRIDE",
    "CANARY_PROJECT_AGENTS",
    "CANARY_PERSONAL_MEMORY",
    "CANARY_PERSONAL_CONFIG",
    "CANARY_PERSONAL_CODEX_SKILL",
    "CANARY_PERSONAL_HOME_SKILL",
    "CANARY_PRIVATE_FILE_CONTENT",
)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def java_list(expression, variables):
    """Read only Java string literals and the expected path expressions."""
    values = []
    for token in re.findall(JAVA_STRING + r"|[^,\s]+", expression):
        if token.startswith('"'):
            values.append(json.loads(token))
        elif token in variables:
            values.append(str(variables[token]))
        else:
            raise RuntimeError(f"Unsupported provider command token: {token}")
    return values


def provider_command(binary, work):
    source = SOURCE.read_text(encoding="utf-8")
    features = re.search(r"DISABLED_FEATURES\s*=\s*List\.of\((.*?)\);", source, re.S)
    method = re.search(
        r"static List<String> command\(.*?\n    }", source, re.S
    )
    require(features and method, "Cannot locate provider command; update the extractor")
    prefix = re.search(
        r"new ArrayList<>\(List\.of\((.*?)\)\);", method.group(), re.S
    )
    suffix = re.search(r"cmd\.addAll\(List\.of\((.*?)\)\);", method.group(), re.S)
    require(prefix and suffix, "Cannot parse provider command; update the extractor")
    schema = work / "schema.json"
    schema.write_text(json.dumps({"type": "object", "properties": {}}), encoding="utf-8")
    variables = {
        "exe.toString()": binary,
        "schemaFile.toString()": schema,
        "outputFile.toString()": work / "last-message.json",
    }
    command = java_list(prefix.group(1), variables)
    for feature in java_list(features.group(1), {}):
        command.extend(("--disable", feature))
    command.extend(java_list(suffix.group(1), variables))
    return command


def fake_auth(access="SYNTHETIC_INITIAL_ACCESS", refresh="SYNTHETIC_INITIAL_REFRESH"):
    def encode(value):
        return base64.urlsafe_b64encode(json.dumps(value).encode()).decode().rstrip("=")

    claims = {
        "email": "synthetic@example.invalid",
        "https://api.openai.com/auth": {
            "chatgpt_account_id": "synthetic-account",
            "chatgpt_plan_type": "plus",
        },
    }
    token = encode({"alg": "none"}) + "." + encode(claims) + ".synthetic-signature"
    return {
        "auth_mode": "chatgpt",
        "OPENAI_API_KEY": None,
        "tokens": {
            "id_token": token,
            "access_token": access,
            "refresh_token": refresh,
            "account_id": "synthetic-account",
        },
        "last_refresh": "2020-01-01T00:00:00Z",
    }


def write_skill(directory, name, marker):
    skill = directory / name
    skill.mkdir(parents=True)
    (skill / "SKILL.md").write_text(
        f"---\nname: {name}\ndescription: {marker}\n---\n{marker}\n", encoding="utf-8"
    )


def run_canary(binary, timeout):
    with tempfile.TemporaryDirectory(prefix="justbuild-codex-canary-") as temporary:
        root = Path(temporary).resolve()
        personal_home = root / "personal-home"
        personal = personal_home / ".codex"
        work = root / "work"
        home = work / "home"
        isolated = work / "codex"
        for directory in (personal, home, isolated):
            directory.mkdir(parents=True, mode=0o700)

        for name, marker in (("AGENTS.md", MARKERS[0]), ("AGENTS.override.md", MARKERS[1])):
            (personal / name).write_text(marker, encoding="utf-8")
        (work / "AGENTS.md").write_text(MARKERS[2], encoding="utf-8")
        memories = personal / "memories"
        memories.mkdir()
        for name in ("MEMORY.md", "memory_summary.md"):
            (memories / name).write_text(MARKERS[3], encoding="utf-8")
        (personal / "config.toml").write_text(
            f'developer_instructions = "{MARKERS[4]}"\n', encoding="utf-8"
        )
        write_skill(personal / "skills", "private-codex", MARKERS[5])
        write_skill(personal_home / ".agents/skills", "private-personal", MARKERS[6])
        private_file = root / "private.txt"
        private_file.write_text(MARKERS[7], encoding="utf-8")

        # Use two symlinks to verify that the provider must preserve the auth
        # pathname, rather than resolving the original auth symlink's target.
        backing = personal / "synthetic-credentials-a.json"
        backing.write_text(json.dumps(fake_auth()), encoding="utf-8")
        backing.chmod(0o600)
        auth = personal / "auth.json"
        auth.symlink_to(backing)
        link = isolated / "auth.json"
        link.symlink_to(auth.absolute())

        captured = []
        model_authorization = []
        refresh_requests = []
        request_paths = []
        rejected_external = []

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                self.respond()

            def do_POST(self):
                self.respond()

            def do_CONNECT(self):
                rejected_external.append(self.path)
                self.send_error(403, "Synthetic canary forbids external requests")

            def respond(self):
                parsed = urlsplit(self.path)
                if parsed.hostname and parsed.hostname not in ("127.0.0.1", "localhost", "::1"):
                    rejected_external.append(self.path)
                    self.send_error(403, "Synthetic canary forbids external requests")
                    return
                body = self.rfile.read(int(self.headers.get("content-length", "0")))
                request_paths.append((self.command, self.path))
                if parsed.path == "/v1/responses":
                    model_authorization.append(self.headers.get("Authorization"))
                    try:
                        captured.append(json.loads(body))
                    except (ValueError, UnicodeDecodeError):
                        captured.append(None)
                if parsed.path == "/oauth/token":
                    try:
                        refresh_requests.append(json.loads(body))
                    except (ValueError, UnicodeDecodeError):
                        refresh_requests.append(None)
                    status = 200
                    response = {
                        "access_token": "SYNTHETIC_REFRESHED_ACCESS",
                        "refresh_token": "SYNTHETIC_REFRESHED_REFRESH",
                    }
                else:
                    status = 400
                    response = {"error": {"message": "Synthetic canary completed; no model called"}}
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps(response).encode())

            def log_message(self, *_args):
                pass

        with http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            base = f"http://127.0.0.1:{server.server_port}"
            # Deliberate allowlist: never inherit personal Codex/auth/config env.
            env = {"PATH": os.environ.get("PATH", ""), "LANG": "en_US.UTF-8"}
            if "SYSTEMROOT" in os.environ:
                env["SYSTEMROOT"] = os.environ["SYSTEMROOT"]
            env.update({
                "HOME": str(home), "USERPROFILE": str(home),
                "APPDATA": str(home), "LOCALAPPDATA": str(home),
                "CODEX_HOME": str(isolated),
                "TMPDIR": str(root), "TMP": str(root), "TEMP": str(root),
                "CODEX_REFRESH_TOKEN_URL_OVERRIDE": base + "/oauth/token",
                "CODEX_AUTHAPI_BASE_URL": base,
                "OPENAI_BASE_URL": base + "/v1",
            })
            for key in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy"):
                env[key] = base
            env["NO_PROXY"] = env["no_proxy"] = "127.0.0.1,localhost,::1"
            try:
                version = subprocess.run(
                    [binary, "--version"], cwd=work, env=env, text=True,
                    capture_output=True, timeout=timeout, check=True,
                ).stdout.strip()
                command = provider_command(binary, work)
                # Fixture routing only; context/tool controls come from Java.
                settings = {
                    "model_provider": '"canary"', "model": '"gpt-5.4"',
                    "model_providers.canary.name": '"Synthetic canary"',
                    "model_providers.canary.base_url": json.dumps(base + "/v1"),
                    "model_providers.canary.wire_api": '"responses"',
                    "model_providers.canary.request_max_retries": "0",
                    "model_providers.canary.stream_max_retries": "0",
                    "model_providers.canary.requires_openai_auth": "true",
                    "chatgpt_base_url": json.dumps(base),
                    "features.use_agent_identity": "false",
                    "features.respect_system_proxy": "true",
                }
                for key, value in settings.items():
                    command.extend(("-c", key + "=" + value))
                command.append("-")
                result = subprocess.run(
                    command, input=f"Use $private-codex and $private-personal. Return an empty object. @{private_file}",
                    cwd=work, env=env, text=True, capture_output=True, timeout=timeout,
                )
            finally:
                server.shutdown()
                thread.join(timeout=5)

        require(not rejected_external, "CLI attempted an unexpected external request (blocked by local proxy)")
        require(captured, "No model request captured; CLI stderr: " + result.stderr[-1800:])
        require(all(isinstance(item, dict) for item in captured), "Model request was not readable JSON")
        require(result.returncode != 0, "CLI unexpectedly succeeded despite deliberate mock rejection")
        require(("POST", "/oauth/token") in request_paths, "Expected synthetic token refresh did not occur")
        require(
            refresh_requests and all(
                isinstance(item, dict)
                and item.get("refresh_token") == "SYNTHETIC_INITIAL_REFRESH"
                and item.get("grant_type") == "refresh_token"
                for item in refresh_requests
            ),
            "CLI did not refresh using the expected synthetic credential",
        )
        refreshed = json.loads(backing.read_text(encoding="utf-8"))
        require(refreshed["tokens"]["access_token"] == "SYNTHETIC_REFRESHED_ACCESS", "Original access token was not refreshed")
        require(refreshed["tokens"]["refresh_token"] == "SYNTHETIC_REFRESHED_REFRESH", "Original refresh token was not rotated")
        require(auth.is_symlink() and link.is_symlink(), "CLI replaced an authentication symlink")
        request_text = json.dumps(captured)
        leaked = [marker for marker in MARKERS if marker in request_text]
        require(not leaked, "Personal context markers leaked: " + ", ".join(leaked))
        require(all(not item.get("tools") for item in captured), "Model request exposed tools")
        require(
            all(value == "Bearer SYNTHETIC_REFRESHED_ACCESS" for value in model_authorization),
            "Model request did not use the refreshed synthetic credential",
        )

        # Logout and account replacement must affect the existing auth-only view.
        auth.unlink()
        require(link.is_symlink() and not link.exists(), "Logout left credentials reachable in the auth-only view")
        replacement = personal / "synthetic-credentials-b.json"
        replacement.write_text(json.dumps(fake_auth("SYNTHETIC_REPLACEMENT")), encoding="utf-8")
        auth.symlink_to(replacement)
        require(json.loads(link.read_text(encoding="utf-8"))["tokens"]["access_token"] == "SYNTHETIC_REPLACEMENT", "Auth-only view did not follow account replacement")
        return {
            "result": "PASS", "version": version, "model_requests": len(captured),
            "refresh_updated_original": True, "symlinks_preserved": True,
            "logout_and_replacement_followed": True,
            "personal_markers_sent": [], "model_tools": [],
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--codex", default="codex", help="CLI executable or path (default: discover codex on PATH)")
    parser.add_argument("--timeout", type=float, default=30, help="Timeout per CLI invocation, in seconds (default: 30)")
    args = parser.parse_args()
    binary = shutil.which(args.codex)
    if not binary:
        parser.error(f"Codex executable not found: {args.codex}")
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    try:
        print(json.dumps(run_canary(str(Path(binary).absolute()), args.timeout), indent=2))
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
