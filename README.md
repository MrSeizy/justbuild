<div align="center">

# JustBuild

**Describe a build. An AI plans it, you preview it, and it goes up block by block in your world.**

![Minecraft 26.3](https://img.shields.io/badge/Minecraft-26.3-62B47A)
![Fabric](https://img.shields.io/badge/Mod%20Loader-Fabric-DBD0B4)
![Java 25](https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white)
![Client and server](https://img.shields.io/badge/Environment-Client%20%2B%20Server-8A63D2)
[![License: LGPL-3.0](https://img.shields.io/badge/License-LGPL--3.0-blue)](LICENSE)

[Features](#features) · [Bring your own AI](#bring-your-own-ai) · [Installation](#installation) ·
[Quick start](#quick-start) · [Multiplayer](#multiplayer) · [Documentation](#documentation) · [Changelog](CHANGELOG.md)

</div>

https://github.com/user-attachments/assets/59ce1fbd-447a-43ee-8875-940fb22dd875

JustBuild is a Fabric mod that turns plain-English requests into real builds. Ask for a cottage, a watchtower, or
a bridge between two houses. The AI plans the structure and JustBuild checks the plan. By default, you inspect a
ghost preview and confirm before any blocks are placed. Optional **Auto-build** places fresh AI results immediately.
Every build stays editable afterwards, with saved versions and undo.

It works with the AI you already have: Claude Code, Codex, Grok Build, or an OpenAI-compatible or
Anthropic-compatible API (see the [changelog](CHANGELOG.md)).

> [!TIP]
> [Download JustBuild 0.2.0](https://github.com/MrSeizy/justbuild/releases/tag/v0.2.0) for Minecraft 26.3,
> or [build the jar from source](#building-from-source).

## Features

### Describe it, preview it, build it

Type what you want. The plan appears as a ghost preview you can walk around, raise, lower, or rotate before
choosing **Instant Build** to place it or **Guided Build** to construct it with real materials.

https://github.com/user-attachments/assets/0c271b24-1ef3-477d-a592-ab5fda1a7f0c

### Pick exactly where it goes

The Builder Wand marks out the area: two corners for a rectangle, or any number of points for an irregular footprint.
Corners can be moved right in the world.

https://github.com/user-attachments/assets/fc2965f8-d7da-4c3b-9848-a26387aa700c

### Keep editing

Select a finished building and describe the change: "add a chimney", "swap the roof to dark oak". Compare
before and after layer by layer, and lock the parts you want left alone.

https://github.com/user-attachments/assets/36716f2e-f9ac-442e-850c-9ef34fd429a6

### Go back to any version

Every change is saved. Browse the visual version history, restore an earlier version, or undo your last action.

https://github.com/user-attachments/assets/241a76eb-4a67-430f-b283-a86bd6f9b55a

### Meet Cobble

Cobble, a small builder robot, walks around the site and hammers away while the AI plans and blocks go down.
Nearby players with the mod see him too.

https://github.com/user-attachments/assets/52d469d5-08d9-4333-99a5-e59327357e9d

### And more

<table>
  <tr>
    <td width="50%"><b>Building workshop</b><br>Swap materials, repair damage, plan interiors, and name parts.<br><img src="docs/images/building-workshop.png" alt="The building workshop menu"></td>
    <td width="50%"><b>Village projects</b><br>Group buildings under a shared style and palette, then connect them with paths and bridges.<br><img src="docs/images/village-palette.png" alt="A shared village palette of twelve blocks"></td>
  </tr>
  <tr>
    <td><b>Schematics</b><br>Export and import Litematica <code>.litematic</code> and Sponge <code>.schem</code> files.<br><img src="docs/images/schematic-export.png" alt="Export options for Litematica, Sponge, and JustBuild formats"></td>
    <td><b>Build checks</b><br>Find construction problems and check that every room can be reached on foot.<br><img src="docs/images/walkability.png" alt="Walkability check results"></td>
  </tr>
</table>

Also included: 24 starting templates, two or three alternatives to choose between, reusable prompts, height and
material limits, and adding builds you made by hand so the AI can edit them.

## Bring your own AI

JustBuild doesn't run a service of its own. Requests go to the provider you pick, using your own subscription,
API key, or local AI server.

| Provider | Uses | Setup |
| --- | --- | --- |
| Claude Code | Your Claude subscription | Install the `claude` CLI and run `claude auth login`. The desktop app alone isn't enough. |
| Codex | Your ChatGPT subscription | Install the `codex` CLI and run `codex login`. |
| Grok Build | Your Grok account | Install the `grok` CLI and run `grok login`. |
| Anthropic-compatible API | API key if required | Choose Anthropic-compatible, enter the base URL and key, then pick a model. |
| OpenAI-compatible API | API key if required | Pick a hosted-provider preset or a local Ollama or LM Studio server, then choose a model. |

See [backend setup](docs/user-guide.md#backends) for CLI login requirements and
[CLI configuration](docs/user-guide.md#config) for executable support on your operating system.

Save up to 20 profiles, such as one fast model and one careful one, and switch between them in one click.

- **Cost:** builds use your plan's limits or API credits. **Workshop → AI usage** shows calls, tokens, and AI time,
  and can cap a session.
- **What the AI sees:** your request plus snapshots of the build area. Container contents, sign text, books, and
  entity data are left out. CLI providers run with your personal instructions, skills, and memory turned off.
- **Your keys:** API keys are encrypted on disk, and the key that unlocks them is kept outside the game folder, in your
  operating system's credential store or a private vault folder. See [credential storage](docs/security-credentials.md).

## Safe by default

- Every new AI build waits as a preview until you confirm it, unless you enable Auto-build or change the preview settings.
- Occupied containers and locked areas are protected.
- Builds won't bury players, pets, named mobs, or villagers.
- Fire, lava, and TNT are rejected.
- Undo restores the saved blocks and asks for confirmation first.

## Installation

**Requirements:** Minecraft Java Edition 26.3 · Fabric Loader 0.20.1 or newer · Fabric API · Java 25 or newer

1. Create a Fabric instance for Minecraft 26.3 in your launcher.
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) and
   [`justbuild-0.2.0.jar`](https://github.com/MrSeizy/justbuild/releases/download/v0.2.0/justbuild-0.2.0.jar)
   in the instance's `mods` folder.
3. Start the game, open a world, and press **Right Shift**.

For multiplayer, install the same version on the server too. Building through the panel or commands requires
server operator permissions. Singleplayer owners and LAN hosts can build without enabling cheats.

## Quick start

Press **Right Shift → Guide** for an in-game walkthrough, or follow these steps.

1. **Connect your AI.** Open **Settings → Manage profiles…** (**My profiles…** on a server), choose a provider and
   model, and press **Test connection**. Save the profile and make it the active one. On a server, also enable
   **Settings → Use my AI here**.
2. **Mark the area.** You get a Builder Wand when you join a world. Left-click one ground corner, then right-click
   the opposite corner.
3. **Describe the build.** On the **Create** tab, type something like
   *"A small medieval cottage with a steep spruce roof and a stone foundation"* and press **Send**.
4. **Choose how to build.** Walk around the preview, then open the panel and choose **Instant Build** or **Guided Build**. **Discard** removes an unwanted preview.
5. **Change it.** Sneak-right-click the building with the wand, describe the change, and press **Apply**.

Everything is also available as commands. `/jb` is short for `/justbuild`.

| Command | What it does |
| --- | --- |
| `/jb build <description>` | Start a new build |
| `/jb edit <change>` | Change the selected building |
| `/jb confirm` | Place the waiting preview |
| `/jb undo` | Undo your last action |
| `/jb doctor` | Check your AI setup |
| `/jb help` | List every command |

## Multiplayer

Each player can plan builds with their own AI from their own game, so nobody has to share an account. Turn on
**Settings → Use my AI here** for a server you trust. It's off for every server until you do. Your credentials aren't
shared with the Minecraft server; your game contacts the AI provider, and the server receives the plan, checks it,
and places the blocks.

https://github.com/user-attachments/assets/6967f5ab-e4d8-475a-949d-c953b8ef46bc

Players must bring their own AI by default. Operators can change this in **Settings → Server AI…** or with
`/jb playerai require|prefer|off`, allowing fallback to the server's AI or always using it.
See [your own AI on a server](docs/user-guide.md#your-own-ai-on-a-server).

## Documentation

- [Video demos](docs/demos/README.md): 24 short walkthroughs
- [User guide](docs/user-guide.md): every control, command, and setting
- [Schematic formats](docs/schematic-formats.md): what imports and exports, and the limits
- [Credential storage](docs/security-credentials.md): where keys live and how to recover them

Settings are in `config/justbuild.json`. Builds, versions, and undo history are saved in each world's `justbuild`
folder, so back it up with the world.

## Building from source

You need a JDK 25. The Gradle wrapper handles the rest. If Gradle can't find your JDK, add its path as
`org.gradle.java.installations.paths` in `~/.gradle/gradle.properties`.

```sh
./gradlew build
```

The mod jar is written to `build/libs/`. On Windows, use `gradlew.bat`.

| Task | What it does |
| --- | --- |
| `./gradlew runClient` | Start a development game with the mod loaded |
| `./gradlew test` | Run the unit tests |
| `./gradlew runClientGameTest` | Open Minecraft and run the scripted game tests |

See [testing](docs/testing.md) for running single game tests, the opt-in checks, and CI.

## Reporting problems

[Open an issue](../../issues) with what you asked for, what happened, and the output of `/jb doctor`.

## License

JustBuild is licensed under the [GNU Lesser General Public License v3.0](LICENSE) (`LGPL-3.0-only`).
