# DSH Assistant

**Cursor-style AI coding assistant for PyCharm — powered by DeepSeek Harness (DSH).**

[![PyCharm 2024.3+](https://img.shields.io/badge/PyCharm-2024.3%2B-21D789?style=flat-square&logo=pycharm&logoColor=white)](https://www.jetbrains.com/pycharm/)
[![IntelliJ Platform 243–253](https://img.shields.io/badge/platform-243--253-4B5563?style=flat-square)](https://plugins.jetbrains.com/docs/intellij/)
[![Kotlin 2.0](https://img.shields.io/badge/Kotlin-2.0-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![License: MIT](https://img.shields.io/badge/license-MIT-3DA639?style=flat-square)](LICENSE)

DSH Assistant brings **DeepSeek Harness** into PyCharm as a native panel: chat and agent
sessions, inline editing, Tab autocomplete, a review workflow for agent changes, checkpoints
with rollback, and the same session history you get from `dsh web`.

The plugin is a client, not a second brain. It runs `dsh --profile sdk` in your project,
talks to it over JSON-RPC, and leaves models, tools, permissions and history to DSH itself.

> **Status:** 2.1.1 — actively developed. PyCharm 2024.3+ is the supported platform.
> **Author:** [@ribla9000](https://github.com/ribla9000), built together with DeepSeek Harness Agent.

---

## Table of contents

- [Installation](#installation)
  - [Requirements](#requirements)
  - [Step 1: Install DSH](#step-1-install-dsh)
  - [Step 2: Clone and install with one command](#step-2-clone-and-install-with-one-command)
  - [Step 3: Or install the ZIP manually](#step-3-or-install-the-zip-manually)
  - [Build from source (Gradle)](#build-from-source-gradle)
- [Quick start](#quick-start)
- [Description](#description)
- [Features](#features)
- [Keyboard shortcuts](#keyboard-shortcuts)
- [Using the assistant](#using-the-assistant)
  - [Chat](#chat)
  - [Ask, Agent, and Plan modes](#ask-agent-and-plan-modes)
  - [Model and effort](#model-and-effort)
  - [Context and attachments](#context-and-attachments)
  - [Reviewing changes](#reviewing-changes)
  - [Checkpoints and rollback](#checkpoints-and-rollback)
  - [Sessions and history](#sessions-and-history)
  - [Token metrics](#token-metrics)
  - [Agent permissions](#agent-permissions)
  - [TAB completion](#tab-completion)
  - [Web DSH](#web-dsh)
  - [Panel layout](#panel-layout)
- [Configuration](#configuration)
  - [Application settings](#application-settings)
  - [Project settings](#project-settings)
  - [The project settings file](#the-project-settings-file)
- [Architecture](#architecture)
- [Privacy and data](#privacy-and-data)
- [Compatibility](#compatibility)
- [Troubleshooting](#troubleshooting)
- [FAQ](#faq)
- [Changelog](#changelog)
- [Contributing](#contributing)
- [License](#license)
- [Acknowledgements](#acknowledgements)

---

## Installation

### Requirements

| Requirement | Details |
|---|---|
| **IDE** | PyCharm 2024.3 (build 243) through 2025.3 (build 253). Built against IntelliJ IDEA Community 2024.3.5. |
| **DSH** | `@deepseek-ai/dsh` installed and runnable as `dsh`, with the `sdk` profile (any recent version). |
| **Node.js** | Required by the DSH CLI. Node.js LTS is recommended. |
| **Provider** | A DeepSeek API key (`deepseek-official`) or a signed-in DeepSeek account (`deepseek-account`), configured once in DSH. |
| **JCEF** | Bundled with PyCharm — nothing to install. |

`make` and `unzip` are needed only for the one-command install in Step 2; the manual ZIP
install has no extra requirements.

Already using DSH? Skip to [Step 2](#step-2-clone-and-install-with-one-command).

### Step 1: Install DSH

```bash
npm install --global @deepseek-ai/dsh
dsh --version
```

Configure a provider once. The simplest way is to open the DSH web app and finish the setup
in its settings screen:

```bash
dsh web
```

Add your DeepSeek API key (provider `deepseek-official`), or sign in to your DeepSeek
account (provider `deepseek-account`). The plugin reuses the same `~/.dsh` directory, so it
inherits this configuration — there is no separate sign-in inside the IDE.

### Step 2: Clone and install with one command

```bash
git clone https://github.com/ribla9000/pycharm-deepseek-harness.git
cd pycharm-deepseek-harness
make install
```

`make install` unpacks the prebuilt plugin from `dist/` into your PyCharm plugins directory
and prints the location it used. It does **not** run Gradle, download dependencies or need a
JDK — the ZIP is committed to the repository for exactly this. Restart PyCharm afterwards.

| Command | What it does |
|---|---|
| `make install` | Installs `dist/dsh-assistant-2.1.1.zip` into the detected PyCharm plugins directory. |
| `make install PLUGINS_DIR=…` | Same, with an explicit directory (use it when detection fails). |
| `make uninstall` | Removes the installed plugin directory. |
| `make build` | Rebuilds `dist/dsh-assistant-2.1.1.zip` with Gradle (needs JDK 17; the first build downloads the IDE and dependencies). |
| `make rebuild` | `make build` followed by `make install`. |
| `make run` | Starts a sandbox PyCharm with the plugin (`runIde`). |
| `make verify` | Runs the JetBrains Plugin Verifier. |

If the IDE is not detected, pass the plugins directory explicitly:

```bash
# Linux
make install PLUGINS_DIR="$HOME/.local/share/JetBrains/PyCharm2025.1/plugins"

# macOS
make install PLUGINS_DIR="$HOME/Library/Application Support/JetBrains/PyCharm2025.1/plugins"

# Windows (Git Bash or WSL)
make install PLUGINS_DIR="$APPDATA/JetBrains/PyCharm2025.1/plugins"
```

Replace `PyCharm2025.1` with the directory that matches your installation, for example
`PyCharm2024.3` or `PyCharmCE2024.3`.

`make` ships with macOS and most Linux distributions; `unzip` is usually preinstalled too
(on Debian/Ubuntu: `sudo apt install unzip`). On Windows, run these commands in Git Bash or
WSL, or use [Step 3](#step-3-or-install-the-zip-manually) instead.

### Step 3: Or install the ZIP manually

The same archive is available in two places:

- `dist/dsh-assistant-2.1.1.zip` in this repository;
- the [latest release](../../releases/latest), the recommended source for the newest version.

1. Download or copy the ZIP — do **not** unzip it.
2. Open **Settings** (`Ctrl+Alt+S`) → **Plugins**.
3. Click the gear icon → **Install Plugin from Disk…**
4. Select the ZIP file → **OK**.
5. Restart the IDE when prompted.

The plugin shows up as **DSH Assistant** in the tool windows and under **Settings → Tools**.

### Build from source (Gradle)

Prerequisites: **JDK 17** and Git. The Gradle wrapper is included, so a separate Gradle
installation is not needed. JDK 17 is required by the Kotlin toolchain — point `JAVA_HOME` at
it even if a newer JDK is installed.

```bash
git clone https://github.com/ribla9000/pycharm-deepseek-harness.git
cd pycharm-deepseek-harness

export JAVA_HOME=/path/to/jdk-17

make build          # wraps ./gradlew buildPlugin and refreshes dist/
make install
```

Without `make`:

```bash
./gradlew buildPlugin
cp build/distributions/dsh-assistant-2.1.1.zip dist/
```

The distributable is written to `build/distributions/`. Use `make build` to also refresh the
committed `dist/` copy.

Gradle tasks:

| Command | What it does |
|---|---|
| `./gradlew runIde` | Starts a sandbox PyCharm with the plugin installed |
| `./gradlew buildPlugin` | Builds the distributable ZIP |
| `./gradlew verifyPlugin` | Runs the JetBrains Plugin Verifier |

The first build downloads IntelliJ IDEA Community 2024.3.5 (a few hundred megabytes) and
caches it for later builds.

## Quick start

1. **Open a project.** The **DSH Assistant** tool window is in the right sidebar; if it is
   hidden, use **View → Tool Windows → DSH Assistant**.
2. **Wait for the green dot.** The plugin starts `dsh --profile sdk` in the project
   directory on its own. The first start takes a few seconds.
3. **Ask something.** Type in the composer and press `Enter` (`Shift+Enter` adds a new line).
4. **Let the agent work.** In **Agent** mode it edits files and runs commands. Every change
   lands in the **Changes** panel, where you can inspect the diff and accept or reject it.
5. **Keep typing in the editor.** Suggestions appear as gray text — press `Tab` to accept.
   `Esc` dismisses them and schedules a fresh suggestion after 3 seconds.

## Description

DeepSeek Harness (DSH) is the agent runtime behind the `dsh` CLI and `dsh web`: it owns the
models, tools, permissions, sessions and settings. DSH Assistant is an IDE client for it.

Instead of embedding a browser tab, the plugin implements natively the parts that benefit
from IDE integration:

- **Chat** as a real tool window with tabs, history, modes and editor context.
- **Changes** as reviewable diffs with accept/reject and checkpoints, because agent edits
  should not be a leap of faith.
- **Autocomplete** in the editor, which is where Tab completion belongs.

Under the hood DSH stays the single source of truth. The plugin launches
`dsh --profile sdk` per project, opens one DSH session per chat tab, and reads the same
`~/.dsh` history as the web app. Nothing is duplicated in a plugin-specific store.

### Why not just open `dsh web` in a browser?

Because the interesting parts live inside the IDE: the selection you are looking at, the file
you are editing, the diff of what the agent just changed, and the ability to roll it back
without switching windows. The web app stays one click away for provider setup, sign-in and
approval dialogs — see [Web DSH](#web-dsh).

## Features

| Cursor feature | How DSH Assistant implements it |
|---|---|
| **Chat** | Native tool window: session tabs, streaming markdown, collapsible reasoning, tool-call chips, stop button. |
| **Agent / Composer** | **Agent** mode edits files and runs commands on its own; every edit is tracked. |
| **Inline edit (`Ctrl+K`)** | `Ctrl+Shift+Y`: instruction → the agent edits the selection or file → diff in the Changes panel. |
| **Ask / Plan** | **Ask** is read-only analysis; **Plan** proposes a plan without touching files. |
| **Model picker** | **Model** and **Effort** dropdowns above the input; switching restarts the SDK with sessions preserved. |
| **Attachments** | Image (PNG/JPEG/WebP/GIF, sent to the model as an image) or file text, applied to a single message. |
| **Change review** | Diff per file, accept/reject one by one or all at once, filters, and a per-turn version history. |
| **Checkpoints** | A snapshot is taken automatically before every agent request; rollback restores the project. |
| **Tab completion** | Gray inline suggestions: `Tab` accepts, `Esc` retries over the whole file after 3 s. |
| **Rules** | A managed block in `AGENTS.md` plus `.dsh/pycharm-assistant.json`, both readable and editable by the agent. |
| **History** | Sessions are stored by DSH and shared with `dsh web`; open tabs are restored per project. |
| **Usage metrics** | Tokens, cache and speed per session, restored from the session log. |

## Keyboard shortcuts

| Action | Shortcut |
|---|---|
| Ask DSH about the selection | `Ctrl+Shift+K` |
| Edit the selection by instruction | `Ctrl+Shift+Y` |
| Request a Tab suggestion at the caret | `Shift+Alt+\` |
| Accept the suggestion | `Tab` |
| Dismiss the suggestion | `Esc` (a new one is requested after 3 s) |
| Send a message | `Enter` |
| New line in the composer | `Shift+Enter` |
| Open the settings | `Ctrl+Alt+S` → **Tools → DSH Assistant** |

Every binding can be changed in **Settings → Keymap** (search for `DSH`). On macOS the
plugin registers `Ctrl`-based shortcuts; rebind them to `Cmd` combinations in the keymap if
that feels more natural.

## Using the assistant

### Chat

- One chat tab is one independent DSH session, with its own context, history and metrics.
- `＋` creates a new chat. Right-click a tab to **Rename…** or **Archive (close)** it.
- Answers stream in place: markdown, code blocks, tool calls as chips, and a collapsible
  "Thought for Ns" section for reasoning.
- **■ Stop** interrupts the running turn (the SDK restarts and the history is preserved).
- Open tabs are saved in the project and restored the next time you open it.
- `A−` / `A+` in the chat header scale the chat text.

### Ask, Agent, and Plan modes

The mode switch sits below the composer:

- **Ask** — read-only. The agent analyses code and answers; it does not edit files.
- **Agent** — the default. The agent edits files, runs commands and reports back.
- **Plan** — the agent proposes a plan and changes nothing.

The last used mode is remembered between sessions.

### Model and effort

The row below the composer exposes the session parameters:

- **Permissions** — full access or ask-before-acting for this chat (see
  [Agent permissions](#agent-permissions)).
- **Model** — the models listed under **Models in the chat switcher**; by default
  `deepseek-v4-pro` and `deepseek-flash`.
- **Effort** — reasoning effort; empty means the provider default ("auto").

Switching a model or effort restarts the SDK process. Sessions survive the restart, so the
conversation continues where it left off. With **Take provider, model, effort and model list
from the original DSH (`~/.dsh`)** enabled, these values follow your normal DSH setup.

### Context and attachments

- The current **selection is always added** to the request.
- **Add the whole open file to the request** (off by default) includes the entire file, up
  to **40,000 characters** by default.
- **Attach file** opens a menu:
  - **Image…** — PNG, JPEG, WebP or GIF; shown as a preview and sent to the model as an
    image.
  - **File (to context)…** — the file's text as context.
- Attachments apply to **one message** and are cleared after sending. Active context is
  shown as chips above the input.
- Chats never share context: each one is a separate session log.

### Reviewing changes

- Every agent edit is tracked, along with changes made by any other process.
- A **watcher** polls project files every **0.3 s** and also reacts to DSH signals
  (`workspace/changes`, turn start/end), so the list appears immediately after the agent
  finishes.
- **Double-click** a file to open its diff. **Accept** keeps the new content; **Reject**
  restores the file to its previous state.
- **Accept all** / **Reject all** handle the whole pending set at once.
- Use the filter (**All / Pending / Accepted / Rejected**), the search field, and grouping
  **By folder** to navigate larger change sets.
- **History · N** next to a file lists versions captured per agent turn; **Diff** compares
  the current file with a stored version.
- Files that have neither a snapshot nor a git version cannot be rolled back; the panel
  marks them.

### Checkpoints and rollback

- A checkpoint is taken **automatically before every agent request**, and manually with the
  camera button in the Changes toolbar.
- **Rollback** (the arrow button) restores the project files to a chosen checkpoint.
- The last **10 checkpoints** are kept by default (configurable).
- Rollback uses the plugin's baselines with a git fallback for files it has no snapshot of.

### Sessions and history

- DSH itself stores sessions under `~/.dsh/sessions/<project>/…` (`session.*.jsonl.zstd`),
  which is why `dsh web` and the plugin see the same history.
- **Chat history** in the `⋯` menu lists the project's sessions: click to open, trash to
  delete.
- Session titles are generated automatically; rename or archive them from the tab menu.
- Archived sessions are hidden from the list, and open tabs are restored from the project
  settings file.

### Token metrics

The compact line in the chat header (`⚡ in·out · 🧠 cache% · tok/s`) is live. Click it for
a popup with totals:

- input and output tokens, reasoning tokens;
- cache reads and writes;
- average generation speed and turn duration;
- the last context reset with its timestamp and compressed size;
- a per-turn table.

Metrics stream in from SDK events and are restored from the session log when you reopen a
session.

### Agent permissions

- The default is **Full access — never ask** (`danger-full-access`). The agent may edit any
  file and run any command without confirmation. Treat it accordingly, especially on a
  shared machine or a production checkout.
- **Ask before important actions** (`workspace-write`) makes the agent ask before file edits
  and commands. Questions arrive as cards with option buttons and a free-text field; the
  answer goes straight back to the agent.
- The `🔓 Full access` / `🔒 Ask` dropdown at the bottom of a chat overrides the global
  policy for that session only.
- **Ask** mode always asks before doing anything consequential.

### TAB completion

The plugin shows a code continuation as **gray text** right in the editor.

- **Tab** accepts the suggestion; **Esc** dismisses it and keeps you typing.
- After **Esc**, the request is repeated after **3 seconds** (configurable) **over the whole
  file**, taking into account everything typed since.
- A suggestion is requested automatically after a short pause in typing (about 450 ms), and
  on demand via `Shift+Alt+\`, **right-click → DSH: Suggest Continuation (TAB)**, or
  **Tools → DSH Assistant**.
- **Extended TAB help** (off by default) allows long continuations of up to **12,000
  tokens** — whole functions or classes, filling in TODOs. Without it, suggestions are
  shorter (up to 4,096 tokens, typically 1–10 lines).
- Completions run through a dedicated `dsh --profile sdk` process with **tools disabled**:
  it cannot modify files and typically answers in **1–2 seconds**. The process starts
  lazily, stops after 10 minutes of inactivity, and its technical sessions are hidden from
  the chat list and cleaned up after a day.
- For the lowest latency, pick a fast model such as `deepseek-flash`; completion uses the
  same provider and model as the chat.

Settings live under **Settings → Tools → DSH Assistant → TAB completion**:
**Enable DSH TAB completion**, **Extended TAB help**, and **Retry after Esc (sec)**.

### Web DSH

The full DSH web application is still available when you need it — provider setup, sign-in,
approval dialogs and workspaces behave there exactly as usual.

- Start it with `dsh web`.
- Set the URL in the project settings page: **DSH URL** (default
  `http://127.0.0.1:3080/`) or paste the full URL printed by `dsh web`, including
  `?token=…`.
- **⋯ → Open web DSH** opens that URL in your external browser.

### Panel layout

- The panel is docked on the right by default. Drag the tool window button to the bottom
  bar, or right-click its tab → **Move to → Bottom**.
- **⋯ → Layout: auto/horizontal/vertical** controls the split between chat and Changes.
  **Auto** stacks them in a narrow window and puts them side by side in a wide one.
- **⋯ → Show/hide Changes** gives the chat the full panel width.
- **View Mode → Float** turns the panel into a floating widget.

## Configuration

Everything is under **Settings → Tools → DSH Assistant**. There are two pages: the
**application** page with defaults for all projects, and the **project** page with URLs for
the current project.

### Application settings

| Setting | Default | Notes |
|---|---|---|
| DSH command | `dsh` | Command that starts the SDK; arguments are allowed, e.g. `npx --yes @deepseek-ai/dsh`. |
| Take provider, model, effort and model list from `~/.dsh` | on | Keeps the plugin aligned with your DSH setup. |
| Hardware-accelerated UI rendering | on | Disable it if the panel shows repaint artefacts. |
| Provider | `deepseek-official` | `deepseek-account` uses your signed-in account instead. |
| Model | `deepseek-v4-pro` | Default model for new sessions. |
| Models in the chat switcher | `deepseek-v4-pro,deepseek-flash` | Comma-separated list shown in the chat dropdown. |
| Reasoning effort | empty (auto) | Passed to the SDK as-is. |
| Max response tokens | 0 (auto) | Upper bound for a single response. |
| Agent permissions | Full access — never ask | Or **Ask before important actions**. |
| Start DSH SDK automatically when a project opens | on | |
| Open a chat when a project opens | on | |
| Add the whole open file to the request | off | The selection is always added. |
| Max file characters in context | 40,000 | |
| Enable DSH TAB completion | on | Master switch for inline suggestions. |
| Extended TAB help | off | Up to 12,000 tokens per suggestion. |
| Retry after Esc | 3 s | Delay before a fresh suggestion is requested. |
| Track file changes | on | Powers the Changes panel. |
| Max file size for diff | 524,288 bytes | Larger files are skipped by the tracker. |
| Excluded directories | `.git`, `.idea`, `.venv`, `venv`, `node_modules`, `__pycache__`, `build`, `dist`, `target`, `out`, `.gradle`, … | Comma-separated. |
| Add a plugin section to `AGENTS.md` | on | Tells the agent where the settings file is. |
| Stop the DSH started by the plugin when the project closes | off | |

### Project settings

| Setting | Default | Notes |
|---|---|---|
| DSH URL | `http://127.0.0.1:3080/` | Where `dsh web` listens. |
| URL from `dsh web` (with `?token=…`) | empty | Takes precedence for **Open web DSH**. |
| Connect automatically when the window opens | on | |

These values are stored per project and applied on the fly.

### The project settings file

Project settings live in **`.dsh/pycharm-assistant.json`** in the project root. Because the
file sits inside the DSH workspace, the agent can read and edit it in chat, and the plugin
applies changes immediately — that is the "settings synchronized through DSH" part.

```json
{
  "dshUrl": "http://127.0.0.1:3080/",
  "tokenUrl": "",
  "autoConnect": true,
  "provider": "deepseek-official",
  "model": "deepseek-v4-pro",
  "models": "deepseek-v4-pro,deepseek-flash",
  "reasoningEffort": "",
  "permissionMode": "danger-full-access",
  "autoContext": false,
  "maxContextChars": 40000,
  "tabCompletionEnabled": true,
  "tabExtendedHelp": false,
  "tabRetryDelayMs": 3000
}
```

- Commit the file if you want settings to travel with the project; leave it untracked if you
  prefer per-machine settings.
- The file also keeps UI state (open chats, session names, archive list, per-session
  policies). Leave those keys alone unless you know what you are doing.
- Delete the file to reset the project to defaults — the plugin recreates it on the next
  project open.

## Architecture

```
PyCharm
└─ "DSH Assistant" tool window
   ├─ Chat tabs ────────────────┐   JSON-RPC over stdio: initialize,
   │  messages, markdown        │   session/prompt, session.event,
   │  modes, context, metrics   ├──► dsh --profile sdk   (cwd = project)
   │  interactive questions     │   the same ~/.dsh: models, settings, history
   ├─ "Changes" panel           │
   │  diff, accept/reject       │
   │  checkpoints, rollback     │
   └─ "Open web DSH" ───────────┘   external browser, URL with token

Editor integration
├─ Ctrl+Shift+K / Ctrl+Shift+Y ──► the same SDK session and agent
└─ Tab completion ───────────────► dedicated dsh --profile sdk
                                   (tools disabled, ~1–2 s per answer)

Project
├─ .dsh/pycharm-assistant.json ──► live two-way synchronization
├─ .dsh/assistant.db ────────────► chat cache, events, file versions
└─ AGENTS.md ────────────────────► managed block describing the settings
```

- **SDK process.** `dsh --profile sdk` runs with the project as its working directory and
  uses the same `~/.dsh` as `dsh web`. It starts when the project opens and can be restarted
  from **⋯ → Restart DSH SDK**.
- **Protocol.** Newline-delimited JSON-RPC: `initialize` (provider, model, cwd),
  `session/prompt` (one chat tab = one `sessionId`), and the `session.event` /
  `session.status` notifications that stream messages, tool calls and agent states.
- **Sessions.** Created and stored by DSH under `~/.dsh/sessions/<project>/…`, so history is
  shared with the web app. The plugin reads the compressed logs with Airlift
  aircompressor's zstd decoder.
- **Change tracking.** Baseline snapshots plus a git fallback produce diffs. A watchdog
  polls project files every 0.3 s and wakes up on agent signals, so the Changes list and
  **History · N** appear right after a turn ends, without waiting for a VFS refresh.
- **Completion.** A separate SDK process with tools disabled answers with one text message;
  the prompt carries the whole file and a caret marker. `Esc` hides the suggestion and
  schedules a retry over the whole file.
- **Storage.** Chat events and file versions are cached in `.dsh/assistant.db` (SQLite);
  global plugin settings live in the IDE configuration directory.

Source layout:

```
.
├── Makefile                             install / build / run / verify targets
├── scripts/plugins-dir.sh               detects the PyCharm plugins directory
├── dist/dsh-assistant-2.1.1.zip         prebuilt plugin installed by "make install"
└── src/main/
    ├── resources/META-INF/plugin.xml        plugin descriptor, actions, shortcuts
    ├── resources/icons/dsh.svg              tool window icon
    ├── resources/shell/shell.html           chat UI (tabs, chats, menu, history)
    ├── resources/changes/changes.html       Changes panel UI
    └── kotlin/com/dsh/assistant/
        ├── startup/                         project-open initialization
        ├── sdk/                             SDK client (JSON-RPC, events), session store
        ├── chat/                            chat bridge, one chat's state, titles, metrics
        ├── completion/                      TAB completion: provider, service, Esc retry
        ├── actions/                         Ctrl+Shift+K, Ctrl+Shift+Y, manual TAB
        ├── changes/                         change tracker, baselines, watchdog, checkpoints
        ├── db/                              SQLite cache for chats, events, file versions
        ├── settings/                        app and project settings, AGENTS.md sync
        └── toolwindow/                      tool window panel and factory
```

`dist/` holds the build that `make install` installs; `make build` refreshes it. Keep only the
current version there so the repository does not grow with every release.

## Privacy and data

- **No telemetry.** The plugin has no analytics, crash reporting or remote calls of its own.
- **Model traffic** goes through your local `dsh` process to the provider you configured in
  DSH (DeepSeek API or your account). Nothing is routed through a plugin-specific server.
- **Sessions and history** stay in `~/.dsh`, managed by DSH.
- **Project state** — settings, baselines, the SQLite cache — stays in the project's `.dsh`
  directory.
- **API keys** are handled by DSH, never stored by the plugin.
- **Open web DSH** simply opens the configured URL in your default browser; the plugin does
  not proxy or intercept it.

## Compatibility

- **PyCharm 2024.3 – 2025.3** (`sinceBuild 243`, `untilBuild 253.*`).
- The plugin depends only on `com.intellij.modules.platform`, so it should load in other
  JetBrains IDEs on the same platform range. Only PyCharm is part of the tested
  configuration.
- There are no Python-specific APIs involved, so it works the same in non-Python projects.
- Works on macOS, Linux and Windows, as long as `dsh` is on `PATH` or its absolute path is
  configured.

## Troubleshooting

**Status shows "DSH unavailable".**
The plugin cannot start the SDK. Most common causes:

- `dsh` is not on the IDE's `PATH`. Set the full path, or `npx --yes @deepseek-ai/dsh`, in
  **Settings → Tools → DSH Assistant → DSH command**.
- DSH is too old and has no `sdk` profile — update it.
- The provider is not configured yet — open **⋯ → Open web DSH** and finish setup.
- After fixing any of the above, restart with **⋯ → Restart DSH SDK**.

**The chat stays silent or shows a red "Model error".**
The selected model may not be available in your DSH. Switch to `deepseek-v4-pro` or
`deepseek-flash` with effort "auto". Sign-in and rate-limit errors are also shown as a red
line with the provider's message. Press **■ Stop** and retry.

**The agent waits for an answer forever.**
A confirmation policy is set to *ask*, but the call cannot surface an approval dialog. Answer
the card in the chat, switch the session to **Full access**, or run that kind of session in
`dsh web`, where approvals work as usual.

**No gray suggestions appear in the editor.**
Check that **Enable DSH TAB completion** is on, that the file type is supported, and that the
completion process has started (it starts on the first request and can take a couple of
seconds). `Shift+Alt+\` forces a request.

**The Changes panel stays empty after the agent edited files.**
Make sure **Track file changes** is enabled, the path is not excluded by **Excluded
directories**, and the file is smaller than the diff limit (512 KB by default).

**The tool window is blank or shows rendering artefacts.**
Turn off **Hardware-accelerated UI rendering** in the plugin settings.

**The plugin does not load.**
Verify the IDE build is 243–253 and that you installed the built ZIP rather than the source
archive. Check **Help → Show Log in Explorer/Finder** for the loading error.

## FAQ

**Is this an official DeepSeek or JetBrains product?**
No. It is a community integration built on the public DSH runtime and the IntelliJ Platform.

**Do I need a Cursor subscription?**
No. You need DSH and a provider configured in it.

**Is the chat the same as the `dsh web` interface?**
No. The chat is a native implementation that talks to the same SDK sessions. The web app
remains available for settings, approvals and workspaces.

**Where does my code go?**
To the provider configured in DSH, through your local `dsh` process. See
[Privacy and data](#privacy-and-data).

**Can I use it with several projects at once?**
Yes. Each project gets its own SDK process, sessions and settings file.

**Does it work in PyCharm Community?**
The plugin depends only on platform APIs and does not use Python-specific features. It is
tested on PyCharm Professional; Community should work but is not part of the tested matrix.

**Does it work offline?**
The IDE integration does, but model requests need the configured provider.

**How do I uninstall and clean up?**
Uninstall the plugin in **Settings → Plugins**, then delete `.dsh/` in the project if you do
not want to keep its settings and cache. The `AGENTS.md` block is removed when you turn off
**Add a plugin section to AGENTS.md** before uninstalling.

## Changelog

### 2.1.1

- Changes and the History list appear right after the agent: the watcher polls project files
  every 0.3 s and wakes up on DSH signals (`workspace/changes`, turn start/end).
- The final file version is recorded in the history when the turn ends.

### 2.1.0

- TAB completion: gray DSH inline suggestions in the editor, `Tab` accepts.
- `Esc` hides the suggestion and repeats the request over the whole file after 3 seconds.
- Extended TAB help for large continuations, up to 12,000 tokens.
- A dedicated tool-less DSH completion process that answers in ~1–2 s and never touches
  files.

### 2.0.0

- DSH inside the PyCharm tool window, with several independent session tabs.
- The Changes panel: diff and accept/reject per file or all at once.
- Settings in `.dsh/pycharm-assistant.json`, synchronized through DSH.

Full release notes are on the [Releases page](../../releases).

## Contributing

Issues and pull requests are welcome.

```bash
git clone https://github.com/ribla9000/pycharm-deepseek-harness.git
cd pycharm-deepseek-harness
export JAVA_HOME=/path/to/jdk-17
make run            # same as ./gradlew runIde
```

- **Requirements:** JDK 17, a working DSH installation, and the Gradle wrapper (included).
- **Style:** Kotlin, 4-space indentation, IntelliJ Platform 2024.3 APIs. Keep dependencies
  minimal and do not add telemetry.
- **Testing:** there is no automated suite yet, so a manual smoke test is expected:
  1. The tool window starts the SDK and reaches the ready state.
  2. A prompt streams a complete answer.
  3. An agent edit shows up in Changes and can be accepted or rejected.
  4. Snapshot followed by rollback restores a file.
  5. A Tab suggestion appears, `Tab` accepts it, `Esc` retries it.
- **Pull requests:** keep them focused, explain the scenario they fix, and include a
  before/after screenshot for UI changes.
- **Bug reports:** include the PyCharm build (**Help → About**), OS, `dsh --version`, steps
  to reproduce, and the relevant excerpt from the IDE log.

## License

[MIT](LICENSE) © 2026 ribla9000.

## Acknowledgements

- [DeepSeek Harness](https://www.deepseek.com/) for the agent runtime that does the actual
  work.
- The [IntelliJ Platform](https://plugins.jetbrains.com/docs/intellij/) and the
  [Gradle IntelliJ Plugin](https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html).
- [Airlift Aircompressor](https://github.com/airlift/aircompressor) for the zstd decoder.
- [Xerial SQLite JDBC](https://github.com/xerial/sqlite-jdbc) for the local cache.
- [Kotlin](https://kotlinlang.org/).
