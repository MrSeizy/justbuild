# Changelog

User-visible changes are recorded here. Unreleased entries describe development builds and are not included in the latest published JAR.

## Unreleased

### Changed

- The chat button and panel action **[Build it]** are now **[Instant Build]**, and the prompt's **Build** button is now **Send**.

## [0.2.0](https://github.com/MrSeizy/justbuild/releases/tag/v0.2.0) — 2026-10-23

### Added

- **Save & use** on successful profile connection tests saves the draft and activates it in one step for local or server profiles.
- Automatic saving of completed previews and alternatives across disconnects and world restarts. Retry saved plan restores the chosen option and placement with fresh world, version, and permission checks, without another AI request. Unsent prompt drafts also survive game restarts on the local computer.
- Anthropic-compatible API profiles with custom base URLs, encrypted API keys, model selection, connection testing, and a configurable output-token limit. Native Messages API requests support streamed plans and review images, with fallbacks for services without structured output or image support.
- Anthropic presets for OpenRouter, DeepSeek, Moonshot/Kimi, Z.AI/GLM, Alibaba/Qwen, Qwen Token Plan and Amazon Bedrock, with service-specific authentication. Added the missing OpenAI-compatible presets and a Vertex AI setup template, with official documentation and account/region requirements in the user guide.
- Public demo players in the README and a [catalog of all 24 videos](docs/demos/README.md).

### Changed

- Saved API keys are retained only while both the provider and base URL remain the same.
- Quality gallery scenes and screenshots clear dropped items, and bridge acceptance checks require continuous fence rails at the correct height along both deck edges.

### Fixed

- Cancelling an API request while its connection is completing now reports cancellation instead of a connection failure.

## [0.1.0](https://github.com/MrSeizy/justbuild/releases/tag/v0.1.0) — 2026-10-21

First public release for Minecraft 26.3, Fabric Loader 0.20.1 or newer, Fabric API, and Java 25 or newer.

### Added

- AI build planning with ghost previews, placement confirmation, editable builds, version history, and undo.
- Builder Wand area selection and in-world preview movement and rotation.
- Claude Code, Codex, Grok Build, and OpenAI-compatible API profiles, with encrypted keys, connection testing, and Doctor diagnostics.
- Player-owned AI in multiplayer, with per-server opt-in and server validation of plans.
- Named parts, protected areas, material swaps, repairs, interior planning, and village connections.
- Saved designs, Litematica and Sponge schematic import/export, and Cobble's building animation.
