# Changelog

User-visible changes are recorded here. Unreleased entries describe development builds and are not included in the latest published JAR.

## Unreleased

### Added

- Survival construction projects with Guide, Assist, and Cobble modes sharing a saved blueprint and world-checked progress. Guide tracks manual building; Assist places correctly oriented blocks from inventory within normal reach; Cobble uses an explicitly linked chest or barrel and follows neighboring blocks around each wall course.
- A construction HUD with the project status and up to six remaining materials for the current layer, shortages first in red, and a Right Shift project panel listing the whole build's remaining materials with the missing amounts. The panel pages with buttons or the mouse wheel; current-layer ghosts, supply linking, and pause/resume controls share the same project. Projects survive restarts in a paused state, and a paused project says why it paused. Missing supplies, unloaded chunks, and blocks that need the player never get placed for free or overwritten.
- A single **Send** prompt action followed by **Instant Build** and **Guided Build** choices once the preview is ready. Guided Build opens the Guide, Assist, and Cobble mode cards. The menus include highlighted mode and supply controls, readable material tables, item icons, search, and a missing-supplies filter.
- Cobble steps past blocks he can't build yet (obstructions, protected cells, hanging blocks waiting for their ceiling) and returns to them once the problem clears, clears grass and other replaceable plants from the site, and outlines in red the block that needs the player, with its coordinates in the status.
- Link chest also works without aiming: the panel closes and the next chest or barrel opened becomes the supply.
- A project file that can't be read is explained in the project panel, and **End project** sets it aside under a new name so new plans can start.
- **Revise** a waiting new-building or design-copy preview with the AI before building (`/justbuild revise <change>`). The revised plan replaces the preview at its current position after the usual checks; **Previous** (`/justbuild preview previous`) steps back through up to 10 earlier versions without another AI call, and each alternative keeps its own history. Stop ends only the revision. Players using their own AI on a server need this version of the client; no relayed request reaches older clients.
- A separate reviewer checks each draft at **One pass** and **Two passes**. It returns a short list of problems instead of rewriting the plan, and the builder revises only when the reviewer (or the draft's own checks) finds something worth fixing, so drafts that need nothing finish after one short step. **Settings → Reviewer** or `/justbuild reviewer <profile|same>` can point it at another profile, such as a faster model or a different provider; the default is the builder's own profile. Progress lines say what the reviewer found and whether a revision was kept.

### Changed

- The chat button and panel action **[Build it]** are now **[Instant Build]**, and the prompt's **Build** button is now **Send**.
- Players using their own AI on a server need this version of the client: relay protocol 21 adds reviewer requests. An AI-starting command now allows 14 AI calls (up from 12) so that two review rounds can each critique and repair; the 30-per-hour ceiling is unchanged.

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
