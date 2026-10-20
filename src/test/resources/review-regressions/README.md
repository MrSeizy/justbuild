# Destructive interior review regressions

These five draft/review pairs were captured by the 17 October 2026 quality-gallery comparison using
Claude (`claude-sonnet-5-5`) and Codex (`gpt-6.1-sol`). Before/after names refer to the design-guidance
conditions in that experiment. Each review omitted the room shell from a complete replacement plan.
`source.json` is the shared room plan supplied to the initial edit request.

`ReviewCheckTest` replays the plans against a reconstructed source world and asserts rejection. Keep
the raw operation lists intact so the regression does not become a simplified example of the guard.
The source experiment and its limits are documented in `docs/quality-gallery.md`.
