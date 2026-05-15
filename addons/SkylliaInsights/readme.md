# SkylliaInsight

**SkylliaInsight** is an addon for the [Insights](https://modrinth.com/plugin/insights) plugin that integrates
Skyllia islands as Insights regions. Once installed, any limit configured in Insights (tile limits, group limits,
permission-based limits) is automatically scoped to the island the player is standing on rather than to an arbitrary
area.

---

## How it works

When Insights needs to resolve the region at a player's location, SkylliaInsight:

1. Checks whether the world is a Skyllia skyblock world.
2. Resolves the island at the player's chunk via `SkylliaAPI`.
3. Returns an island region whose chunk coverage is computed from the island's center chunk and block radius
   (`ceil(radius / 16)` chunks in each direction). The chunk list is cached per region object and only rebuilt if the
   island is resized.

The addon name used in Insights limit configurations is the Skyllia plugin name in lowercase (e.g. `skyllia`).

---

## Requirements

| Dependency                                               | Required | Notes                  |
|----------------------------------------------------------|----------|------------------------|
| Paper / Folia 1.20.5+                                    | ✅        | Server software        |
| [Skyllia](https://modrinth.com/plugin/skyllia)           | ✅        | Skyblock platform      |
| [Insights](https://modrinth.com/plugin/insights) 6.19.2+ | ✅        | Region limiting engine |
| Java 21                                                  | ✅        |                        |

---

## Installation

1. Download the latest `SkylliaInsight.jar` from the
   [official repository](https://github.com/Euphillya/Skyllia/tree/dev/addons/SkylliaInsights).
2. Place the jar in `plugins/Insights/addons/`.
3. Restart the server. Insights will load the addon automatically on startup.

No additional configuration is required on the SkylliaInsight side. All limit rules are defined in Insights' own
`limits/` directory.

---

## Setting up limits

Create `.yml` files inside `plugins/Insights/limits/`. Use `enabled-addons` to scope a limit to Skyllia islands only.

The addon identifier to use in `enabled-addons` is `skyllia` (Skyllia's plugin name, lowercased).

### Scope a limit to Skyllia islands only

```yaml
limit:
  type: "GROUP"
  bypass-permission: "insights.bypass.limit.redstone"
  name: "Redstone"
  limit: 64
  settings:
    enabled-addons:
      whitelist: true   # true = whitelist (only apply to listed addons)
      addons:
        - "skyllia"
  regex: false
  materials:
    - "REDSTONE_WIRE"
    - "REDSTONE_BLOCK"
    - "HOPPER"
    - "DISPENSER"
    - "DROPPER"
    - "REPEATER"
    - "COMPARATOR"
```

### Tile limit

```yaml
limit:
  type: "TILE"
  bypass-permission: "insights.bypass.limit.tile"
  name: "Tiles"
  limit: 256
```

### Group limit

```yaml
limit:
  type: "GROUP"
  bypass-permission: "insights.bypass.limit.island"
  name: "SkylliaIslandLimits"
  limit: 1000
  regex: false
  materials:
    - "STONE"
    - "DIRT"
  entities:
    - "ARMOR_STAND"
    - "PAINTING"
```

### Permission limit

```yaml
limit:
  type: "PERMISSION"
  bypass-permission: "insights.bypass.limit.permission"
  materials:
    "DIAMOND_BLOCK": 10
    "GOLD_BLOCK": 20
  entities:
    "ITEM_FRAME": 15
    "PAINTING": 5
```

### Disallow placement outside any island

```yaml
limit:
  type: "GROUP"
  settings:
    enabled-addons:
      whitelist: true
      addons:
        - "skyllia"
    disallow-placement-outside-region: true
  ...
```

---

## Configuration reference

| Field                                        | Description                                                                             |
|----------------------------------------------|-----------------------------------------------------------------------------------------|
| `type`                                       | `TILE`, `GROUP`, or `PERMISSION`                                                        |
| `bypass-permission`                          | Permission node that lets a player bypass this limit                                    |
| `settings.enabled-worlds.whitelist`          | `true` = only apply to listed worlds; `false` = apply to all except listed worlds       |
| `settings.enabled-worlds.worlds`             | List of world names                                                                     |
| `settings.enabled-addons.whitelist`          | `true` = only apply inside listed addon regions; `false` = exclude listed addon regions |
| `settings.enabled-addons.addons`             | List of addon names (use `skyllia` for Skyllia islands)                                 |
| `settings.disallow-placement-outside-region` | Prevents block placement when the player is not inside any matching region              |

For the full Insights configuration reference, see the
[Insights documentation](https://github.com/InsightsPlugin/Insights/wiki).

---

## Support

For help, please join the [Discord server](https://discord.gg/uUJQEB7XNN).

## Contributing

Contributions are welcome! Please read the [contribution guidelines](../../CONTRIBUTING.md) before opening a pull
request.

## License

SkylliaInsight is licensed under the [MIT License](../../LICENSE).