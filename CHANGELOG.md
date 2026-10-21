# Changelog

User-visible changes are recorded here. Unreleased entries describe development builds and are not included in the latest published JAR.

## Unreleased

### Added

- Anthropic-compatible API profiles with custom base URLs, encrypted API keys, model selection, connection testing, and a configurable output-token limit. Native Messages API requests support streamed plans and review images, with fallbacks for services without structured output or image support.
- Public demo players in the README and a [catalog of all 24 videos](docs/demos/README.md).

### Changed

- Saved API keys are retained only while both the provider and base URL remain the same.

## [0.1.0](https://github.com/MrSeizy/justbuild/releases/tag/v0.1.0) — 2026-10-21

First public release for Minecraft 26.3, Fabric Loader 0.20.1 or newer, Fabric API, and Java 25 or newer.

### Added

- AI build planning with ghost previews, placement confirmation, editable builds, version history, and undo.
- Builder Wand area selection and in-world preview movement and rotation.
- Claude Code, Codex, Grok Build, and OpenAI-compatible API profiles, with encrypted keys, connection testing, and Doctor diagnostics.
- Player-owned AI in multiplayer, with per-server opt-in and server validation of plans.
- Named parts, protected areas, material swaps, repairs, interior planning, and village connections.
- Saved designs, Litematica and Sponge schematic import/export, and Cobble's building animation.
