# 🏝️ SkylliaIslandLevel

> **Addon plugin for Skyllia** — Assign a **score** and a **level** to each island based on the blocks it contains.

> ⚠️ **This plugin is currently in beta.** Bugs may be present. Please report them via Discord.

---

## 📋 Table of Contents

1. [Overview](#overview)
2. [Requirements](#requirements)
3. [Installation](#installation)
4. [How It Works](#how-it-works)
5. [Configuration](#configuration)
6. [Commands](#commands)
7. [Permissions](#permissions)
8. [PlaceholderAPI](#placeholderapi)
9. [Advanced Configuration Examples](#advanced-configuration-examples)
10. [Troubleshooting](#troubleshooting)

---

## Overview

**SkylliaIslandLevel** is an addon for the [Skyllia](https://modrinth.com/plugin/skyllia) plugin. It automatically calculates the **value** of each skyblock island by scanning its blocks, then converts that raw score into a **readable level** using a fully customizable formula.

**What this plugin does:**
- Periodically scans the islands of online players
- Assigns a value (score) to each block type according to your configuration
- Calculates a level from a configurable mathematical formula
- Exposes an island leaderboard, viewable in-game and via PlaceholderAPI
- Stores score and level directly in Skyllia's persistent data (no external database required)

---

## Requirements

| Dependency                                                                | Minimum Version                         | Required     |
|---------------------------------------------------------------------------|-----------------------------------------|--------------|
| Paper or Folia                                                            | 1.20.5+                                 | ✅            |
| Java                                                                      | 21+                                     | ✅            |
| [Skyllia](https://modrinth.com/plugin/skyllia)                            | Must support `getCountAllBlocksInChunk` | ✅            |
| [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) | Any recent version                      | ❌ (optional) |

> **Folia note:** The plugin natively supports Folia by using the `RegionScheduler` for chunk scanning.

---

## Installation

1. Download the `SkylliaIslandValue-X.X-all.jar` file from the official repository.
2. Place the `.jar` in your `plugins/` folder.
3. Make sure **Skyllia** is already present in `plugins/`.
4. Start or restart your server.
5. The plugin automatically generates its configuration file at `plugins/SkylliaIslandLevel/config/config.toml`.

---

## How It Works

### Automatic Scan Cycle

Every N seconds (configurable, default: **300 s**), the plugin:

1. Retrieves the list of online players.
2. For each player, identifies their island via `SkylliaAPI`.
3. Determines all chunks covered by the island (chunk radius = `ceil(island_size / 16)`).
4. Scans each chunk one by one, counting blocks via `getCountAllBlocksInChunk`.
5. Multiplies each block count by its configured value → **raw score**.
6. Applies the level formula → **calculated level**.
7. Saves the score and level into the island's persistent data.

### Data Storage

Data is stored in Skyllia's **PersistentData** system (key `skylliaislandlevel:data`), under two entries:
- `score` (Double) — weighted raw score
- `level` (Long) — calculated level

No external database is required.

---

## Configuration

The configuration file is located at:

```
plugins/SkylliaIslandLevel/config/config.toml
```

### Default File

```toml
[island-level]
# Mathematical formula to calculate the level from the score.
# Available variables: score, level, size
# Available functions: FLOOR, SQRT, CEIL, ABS, POW, LOG, MIN, MAX, ...
level-expression = "FLOOR(SQRT(score / 10))"

# Minimum guaranteed level (no island can drop below this)
min-level = 0

# Interval (in seconds) between two automatic scans of online islands
timer-interval-seconds = 300

# Cache validity duration for the Top leaderboard (in seconds)
timer-interval-seconds-top = 120

[blocks]
# Assign each block type a value (positive decimal number)
# Use Bukkit names in UPPERCASE
DIRT = 1
GRASS_BLOCK = 2
STONE = 3
```

### Parameter Reference

#### `level-expression`

Formula evaluated by [EvalEx](https://github.com/ezylang/EvalEx). It receives three variables:

| Variable | Type     | Description                                 |
|----------|----------|---------------------------------------------|
| `score`  | `double` | Raw score calculated from blocks            |
| `level`  | `long`   | Current island level (before recalculation) |
| `size`   | `double` | Island size (radius) in blocks              |

**Formula examples:**

```toml
# Simple square root (default)
level-expression = "FLOOR(SQRT(score / 10))"

# Logarithmic progression (level increases more slowly)
level-expression = "FLOOR(LOG(score + 1, 2))"

# Linear formula with tiers every 1000 points
level-expression = "FLOOR(score / 1000)"

# Formula incorporating island size
level-expression = "FLOOR(SQRT(score / 10) * (size / 100))"
```

> **PlaceholderAPI integration in the formula:** You can include PAPI placeholders inside `level-expression`. They are resolved numerically for the relevant island before evaluation.
>
> Example: `"FLOOR(SQRT(score / 10) + %skyllia_island_members%)"`

#### `timer-interval-seconds`

Time between two automatic scan passes. Setting this to `0` disables automatic scanning (players will need to use `/island level scan` manually).

#### `[blocks]`

Each entry follows the format:

```toml
BUKKIT_NAME = value
```

Invalid names (non-existent block in 1.20.5+) are ignored with a warning in the logs. Zero or negative values are also ignored.

**Example of a more complete block configuration:**

```toml
[blocks]
# Common — low value
DIRT = 1
GRAVEL = 1
SAND = 1
GRASS_BLOCK = 2

# Stone and basic ores
STONE = 3
COBBLESTONE = 2
ANDESITE = 2
GRANITE = 2
DIORITE = 2
IRON_ORE = 5
COPPER_ORE = 4
COAL_ORE = 3

# Rare ores
GOLD_ORE = 10
DIAMOND_ORE = 20
EMERALD_ORE = 25
NETHERITE_BLOCK = 100

# Crafted valuable blocks
IRON_BLOCK = 30
GOLD_BLOCK = 60
DIAMOND_BLOCK = 150
EMERALD_BLOCK = 175

# Exotic / end-game
BEACON = 500
DRAGON_EGG = 1000
```

---

## Commands

All subcommands of this plugin are registered in Skyllia under the `/island` (or `/is`) command.

| Command                    | Description                                                        |
|----------------------------|--------------------------------------------------------------------|
| `/island level scan`       | Manually triggers a scan of your island and displays score + level |
| `/island level top [page]` | Displays the island leaderboard (10 entries per page)              |
| `/island level rank`       | Displays your position in the global leaderboard                   |

> A French alias is also registered: `/island niveau`.

### `/island level scan` Behavior

- A scan cannot be started twice simultaneously on the same island.
- If a scan is already in progress, the player is notified.
- At the end of the scan, the score and level are displayed directly in chat.

---

## Permissions

| Permission                     | Description                           | Default              |
|--------------------------------|---------------------------------------|----------------------|
| `skyllia.island-value.command` | Access to `/island level` subcommands | `true` (all players) |

---

## PlaceholderAPI

If PlaceholderAPI is installed, the following placeholders are automatically available. The expansion identifier is `islandlevel`.

### Individual Placeholders (per player)

| Placeholder           | Returned Value                              |
|-----------------------|---------------------------------------------|
| `%islandlevel_level%` | Level of the player's island                |
| `%islandlevel_score%` | Raw score of the player's island            |
| `%islandlevel_rank%`  | Player's position in the global leaderboard |

### Leaderboard Placeholders (Top)

| Placeholder                   | Returned Value                         |
|-------------------------------|----------------------------------------|
| `%islandlevel_top_<N>_name%`  | Name of the island owner at position N |
| `%islandlevel_top_<N>_level%` | Level of the island at position N      |
| `%islandlevel_top_<N>_score%` | Score of the island at position N      |

Replace `<N>` with an integer (e.g. `%islandlevel_top_1_name%` = 1st place).

### Cache Notes

Values are cached to avoid repeated database reads. The cache expires according to `timer-interval-seconds-top`. If a value is missing from the cache at request time, the last known value from the database is returned immediately while an asynchronous refresh is triggered in the background.

---

## Advanced Configuration Examples

### Competitive Server — High values, frequent scans

```toml
[island-level]
level-expression = "FLOOR(SQRT(score / 50))"
min-level = 0
timer-interval-seconds = 120
timer-interval-seconds-top = 60

[blocks]
NETHERITE_BLOCK = 2000
BEACON = 1000
DIAMOND_BLOCK = 500
EMERALD_BLOCK = 400
GOLD_BLOCK = 200
IRON_BLOCK = 100
DIAMOND_ORE = 80
EMERALD_ORE = 70
GOLD_ORE = 40
IRON_ORE = 20
STONE = 5
DIRT = 1
```

### Beginner Server — Gentle progression

```toml
[island-level]
level-expression = "FLOOR(score / 100)"
min-level = 1
timer-interval-seconds = 600
timer-interval-seconds-top = 300

[blocks]
DIRT = 5
GRASS_BLOCK = 8
STONE = 10
COBBLESTONE = 7
IRON_ORE = 30
GOLD_ORE = 60
DIAMOND_ORE = 120
IRON_BLOCK = 200
GOLD_BLOCK = 400
DIAMOND_BLOCK = 800
```

### Displaying the Leaderboard in a Scoreboard (e.g. FeatherBoard)

```
<gold>🏆 Top Islands
<yellow>1. <white>%islandlevel_top_1_name% <gray>- Lvl <green>%islandlevel_top_1_level%
<yellow>2. <white>%islandlevel_top_2_name% <gray>- Lvl <green>%islandlevel_top_2_level%
<yellow>3. <white>%islandlevel_top_3_name% <gray>- Lvl <green>%islandlevel_top_3_level%
<gray>Your rank: <white>#%islandlevel_rank%
```

---

## Troubleshooting

### The plugin disables itself on startup with a `getCountAllBlocksInChunk` error

Your version of Skyllia is too old and does not support this method. Update Skyllia to the latest version.

### Levels are not updating

Check that `timer-interval-seconds` is not set to `0`. If intentional, players must use `/island level scan` manually. Also verify that blocks with a non-zero value are defined under `[blocks]`.

### A block in my config is being ignored

The block name must exactly match the Bukkit name in UPPERCASE (e.g. `GRASS_BLOCK` not `GRASS`). Check the server logs on startup — invalid names are listed there.

### The `level-expression` formula is invalid

The plugin detects this on startup and logs an error. When the formula is invalid, the minimum level (`min-level`) is returned for all islands. Check your EvalEx syntax and test your formula on the [EvalEx Playground](https://ezylang.github.io/EvalEx/).

### PAPI placeholders return an empty value

Make sure the player actually has a Skyllia island. The expansion returns an empty string if no island is found for the player.

---

## Support

For questions or bug reports, join the [official Discord](https://discord.gg/uUJQEB7XNN).

## License

SkylliaIslandLevel is released under the **MIT License**.