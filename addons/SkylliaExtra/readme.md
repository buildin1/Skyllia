# SkylliaExtra

**SkylliaExtra** is an addon for the [Skyllia](https://github.com/Euphillya/Skyllia) skyblock plugin. It bundles small
quality-of-life features and extra commands that are too lightweight to deserve their own dedicated addon.

---

## Requirements

| Dependency     | Version             |
|----------------|---------------------|
| Paper / Folia  | 1.20.6+             |
| Java           | 21+                 |
| Skyllia        | 3-.x                |
| PlaceholderAPI | Latest *(optional)* |

---

## Features

- Players can set a **custom name** for their island via `/is setname`
- Players can set a **custom description** for their island via `/is setdescription`
- Both support **MiniMessage formatting** for rich text
- Both support a **`reset`** argument to restore defaults
- Admins can override any player's island name or description via `/isadmin`
- Island **permission system** integration — only members with the right role can use the commands
- **PlaceholderAPI** support with 4 placeholders (raw and rendered variants)
- **Folia compatible**
- More extra commands to come 🚧

---

## Commands

### Player Commands

```
/is setname <name|reset>
/is setdescription <description|reset>
```

| Command                     | Description                                                |
|-----------------------------|------------------------------------------------------------|
| `/is setname <name>`        | Sets a custom name for your island (MiniMessage supported) |
| `/is setname reset`         | Resets the island name to the default                      |
| `/is setdescription <desc>` | Sets a custom description for your island                  |
| `/is setdescription reset`  | Resets the island description                              |

| Permission                            | Description                       |
|---------------------------------------|-----------------------------------|
| `skylliaextra.command.setname`        | Allows using `/is setname`        |
| `skylliaextra.command.setdescription` | Allows using `/is setdescription` |

> In addition to the Bukkit permission, the island's internal permission system is checked. Only members with the
`set_name` / `set_description` island permission can use these commands.

### Admin Commands

```
/isadmin setname <player|uuid> <name|reset>
/isadmin setdescription <player|uuid> <description|reset>
```

| Command                                   | Description                                          |
|-------------------------------------------|------------------------------------------------------|
| `/isadmin setname <player> <name>`        | Sets the name of the target player's island          |
| `/isadmin setname <player> reset`         | Resets the name of the target player's island        |
| `/isadmin setdescription <player> <desc>` | Sets the description of the target player's island   |
| `/isadmin setdescription <player> reset`  | Resets the description of the target player's island |

| Permission                     | Description                            |
|--------------------------------|----------------------------------------|
| `skyllia.admin.setname`        | Allows using `/isadmin setname`        |
| `skyllia.admin.setdescription` | Allows using `/isadmin setdescription` |

> The `<player>` argument accepts both a player name and a UUID.

---

## PlaceholderAPI

When PlaceholderAPI is installed, the following placeholders are available.  
The identifier is `skylliaextra` (the plugin name in lowercase).

| Placeholder                      | Description                                                             |
|----------------------------------|-------------------------------------------------------------------------|
| `%skylliaextra_name%`            | The island name, rendered from MiniMessage to legacy color codes        |
| `%skylliaextra_name_raw%`        | The island name as stored (raw MiniMessage string)                      |
| `%skylliaextra_description%`     | The island description, rendered from MiniMessage to legacy color codes |
| `%skylliaextra_description_raw%` | The island description as stored (raw MiniMessage string)               |

If no custom name is set, `%skylliaextra_name%` and `%skylliaextra_name_raw%` fall back to `<PlayerName>'s Island`.  
If no custom description is set, the description placeholders return an empty string.

---

## Language Keys

The following keys must be present in your Skyllia language file:

### Player — `/is setname`

| Key                                                    | Description                                       |
|--------------------------------------------------------|---------------------------------------------------|
| `addons.skylliaextra.player.setname.no-permission`     | Shown when the sender lacks the Bukkit permission |
| `addons.skylliaextra.player.setname.player-only`       | Shown when a non-player runs the command          |
| `addons.skylliaextra.player.setname.no-island`         | Shown when the player has no island               |
| `addons.skylliaextra.player.setname.permission-denied` | Shown when the island permission is denied        |
| `addons.skylliaextra.player.setname.usage`             | Usage hint                                        |
| `addons.skylliaextra.player.setname.reset`             | Shown after a successful reset                    |
| `addons.skylliaextra.player.setname.success`           | Shown on success (placeholder: `%name%`)          |
| `addons.skylliaextra.player.setname.failed`            | Shown on failure                                  |

### Player — `/is setdescription`

| Key                                                           | Description                                       |
|---------------------------------------------------------------|---------------------------------------------------|
| `addons.skylliaextra.player.setdescription.no-permission`     | Shown when the sender lacks the Bukkit permission |
| `addons.skylliaextra.player.setdescription.player-only`       | Shown when a non-player runs the command          |
| `addons.skylliaextra.player.setdescription.no-island`         | Shown when the player has no island               |
| `addons.skylliaextra.player.setdescription.permission-denied` | Shown when the island permission is denied        |
| `addons.skylliaextra.player.setdescription.usage`             | Usage hint                                        |
| `addons.skylliaextra.player.setdescription.reset`             | Shown after a successful reset                    |
| `addons.skylliaextra.player.setdescription.success`           | Shown on success (placeholder: `%description%`)   |
| `addons.skylliaextra.player.setdescription.failed`            | Shown on failure                                  |

### Admin — `/isadmin setname`

| Key                                                  | Description                                                       |
|------------------------------------------------------|-------------------------------------------------------------------|
| `addons.skylliaextra.admin.setname.no-permission`    | Shown when the sender lacks the admin permission                  |
| `addons.skylliaextra.admin.setname.usage`            | Usage hint                                                        |
| `addons.skylliaextra.admin.setname.player-not-found` | Shown when the target player is unknown (placeholder: `%player%`) |
| `addons.skylliaextra.admin.setname.no-island`        | Shown when the target has no island                               |
| `addons.skylliaextra.admin.setname.reset`            | Shown after a successful reset (placeholder: `%player%`)          |
| `addons.skylliaextra.admin.setname.success`          | Shown on success (placeholders: `%player%`, `%name%`)             |
| `addons.skylliaextra.admin.setname.failed`           | Shown on failure                                                  |

### Admin — `/isadmin setdescription`

| Key                                                         | Description                                                       |
|-------------------------------------------------------------|-------------------------------------------------------------------|
| `addons.skylliaextra.admin.setdescription.no-permission`    | Shown when the sender lacks the admin permission                  |
| `addons.skylliaextra.admin.setdescription.usage`            | Usage hint                                                        |
| `addons.skylliaextra.admin.setdescription.player-not-found` | Shown when the target player is unknown (placeholder: `%player%`) |
| `addons.skylliaextra.admin.setdescription.no-island`        | Shown when the target has no island                               |
| `addons.skylliaextra.admin.setdescription.reset`            | Shown after a successful reset (placeholder: `%player%`)          |
| `addons.skylliaextra.admin.setdescription.success`          | Shown on success (placeholders: `%player%`, `%description%`)      |
| `addons.skylliaextra.admin.setdescription.failed`           | Shown on failure                                                  |

---

## Installation

1. Make sure **Skyllia** is installed and enabled.
2. Drop the `SkylliaExtra.jar` into your `plugins/` folder.
3. Start or reload the server.
4. Add the required language keys to your Skyllia language file.
5. *(Optional)* Install **PlaceholderAPI** to enable the placeholders.