# Verification

Use JDK 25 and the checked-in Gradle wrapper. The unit suite and scripted game tests use local fixtures or stub
providers; they do not need paid AI accounts. The packaged mod excludes the `gametest` source set.

## Routine checks

```sh
./gradlew build compileGametestJava
./gradlew test --tests '*EncryptedCredentialsTest'
./gradlew runClientGameTest -PclientTests=PanelScreenshotTest,FormDraftGameTest
```

`clientTests` accepts comma-separated simple class names or fully qualified entrypoint names. Unknown names fail
with the available choices. The selector changes only the generated manifest under `build/`; the source manifest
always contains the complete suite. Prefer affected scenarios for routine changes and follow-up fixes. The full
graphical suite is slow; reserve it for changes or investigations that need broad integration coverage. Omit the
property to run every registered scenario:

```sh
./gradlew runClientGameTest
```

Client tests need a graphical session (or `xvfb-run -a` with software OpenGL on Linux). Screenshots are under
`build/run/clientGameTest/screenshots/`; logs are under `build/run/clientGameTest/logs/`. A failed scenario can
stop the run before later scenarios execute. Inspect the log before treating a partial run as a full pass.

Credential tests use temporary vaults, including when launched directly from an IDE. Game tests use
`build/gametest-credentials/`. Keep that directory with the generated game config between runs, or delete both
to reset; deleting only the vault deliberately makes existing encrypted test credentials unreadable. Test output
contains synthetic credentials only. Do not copy personal keys into these fixtures.

## Native credentials and CI

Windows runs exercise user-scoped DPAPI and protected file ACLs automatically. The macOS Keychain check is opt-in
because it creates an OS credential item:

```sh
JUSTBUILD_TEST_KEYCHAIN=1 ./gradlew test --tests '*NativeCredentialKeysTest' --rerun-tasks
```

The item has a random identifier and synthetic bytes and is deleted after the check. No existing credentials are
read. See [credential storage](security-credentials.md) for migration and recovery behavior.

Tests that execute POSIX shell fixtures are explicitly skipped on Windows; pure command construction, model
validation, HTTP, storage, relay, and native Windows credential checks still run there. A passing macOS run does
not establish Windows CLI launch or credential behavior.

The [Verify workflow](../.github/workflows/verify.yml) builds and runs units on Linux, macOS, and Windows. It also
compiles all client tests. Use **Run workflow → Run the Minecraft graphical smoke suite** to run the selected
panel, form, relay, and config-recovery scenarios on Linux. Reports, the packaged JAR, and smoke-test screenshots
are uploaded; vaults and game configs are excluded. A workflow file validated locally still needs a hosted run
to establish that each runner's native dependencies work.

## Live provider evaluations

`LivePlanningTest` is separate from deterministic checks and skipped unless explicitly enabled:

```sh
JUSTBUILD_LIVE_PROVIDERS=1 ./gradlew test --tests '*LivePlanningTest' --rerun-tasks
```

Read its provider setup before running: it invokes installed, authenticated AI CLIs and can consume account usage.
Live output varies with the model and provider. CI never enables it. The [quality gallery](quality-gallery.md) adds five fixed scenes, screenshots and side-by-side reports,
with separately enabled live/imported plans. Live evaluations do not substitute for deterministic safety and undo checks.

Run Gradle builds and client game tests sequentially in the same checkout. A concurrent compile can replace classes
while Minecraft is loading them and cause `NoClassDefFoundError`; separate logs do not isolate build outputs.

## Preview benchmark

`PreviewBenchmarkGameTest` times large ghost previews (50,000 to 250,000 cells) without calling a provider. It skips
its timing work unless enabled:

```sh
JUSTBUILD_PREVIEW_BENCHMARK=local ./gradlew runClientGameTest -PclientTests=PreviewBenchmarkGameTest
```

The report is `build/run/clientGameTest/preview-benchmark-local.json`. Copy it out before another game-test run,
which recreates its run directory. Compare builds on the same machine with similar camera, graphics and background
load. Use `LargePreviewGameTest` for rendering regressions.

## CLI isolation canaries

The mod runs the Claude, Codex and Grok Build CLIs with host tools, personal instructions, memory and skills
disabled (see [backends](user-guide.md#backends)). Each CLI needs its own flags and environment for that, and a CLI
upgrade can change what they do. These canaries check the request each installed CLI actually builds. They use
synthetic homes and credentials and a loopback server in place of a real model, so they need no account and use no
usage:

```sh
python3 scripts/security/claude-context-canary.py
python3 scripts/security/codex-auth-isolation-canary.py
JUSTBUILD_TEST_GROK=1 ./gradlew test --tests '*NativeGrokProviderTest' --rerun-tasks
```

The scripts find `claude` and `codex` on `PATH`; pass `--claude PATH` or `--codex PATH` otherwise. The Codex canary
reads the arguments from `CodexCliProvider` itself. Add `JUSTBUILD_LIVE_GROK=1` to the Grok command to also check
your existing `grok login` with one small live request; that one does use your account.

Re-run them after upgrading a CLI or changing `ClaudeCliProvider`, `CodexCliProvider` or `GrokCliProvider`. They
were last verified with Claude Code 2.1.300, codex-cli 0.162.1 and Grok Build 1.0.52.
