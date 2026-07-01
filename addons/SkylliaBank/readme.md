# SkylliaBank

**SkylliaBank** is a banking add-on for the [Skyllia](https://github.com/Euphillya/Skyllia) Skyblock plugin. It adds a
shared island bank where players can deposit and withdraw money, with full admin controls, a leaderboard system, and
PlaceholderAPI support.

By default, the island bank works alongside an existing Vault economy plugin — players transfer money between their
personal wallet and the island bank. Optionally, an **island economy mode** can be enabled in the configuration, which
makes SkylliaBank register itself as the Vault Economy provider so that any plugin interacting with Vault (shops,
auction houses, etc.) reads and writes the island bank directly, without needing a separate economy plugin.

---

## Features

- **Island Bank Account:** Each island has a single shared bank balance, separate from players' personal wallets (
  classic mode) or as the primary economy account (island economy mode).
- **Deposit & Withdrawal:** Players can transfer money between their personal wallet and the island bank, with automatic
  rollback on failure.
- **Island Economy Mode:** SkylliaBank can register itself as the Vault Economy provider. Any plugin that calls the
  standard Vault API (`getBalance`, `withdrawPlayer`, `depositPlayer`) will transparently operate on the player's island
  bank instead of a per-player wallet.
- **Island Permission System:** Deposit and withdrawal actions are gated by Skyllia's per-island role permissions, so
  island owners can control who has access.
- **Admin Commands:** Administrators can view, deposit, withdraw, or set the balance of any island's bank directly.
- **Leaderboard (Top):** Players can consult a paginated ranking of islands sorted by bank balance, and check their own
  island's rank.
- **PlaceholderAPI Integration:** Exposes placeholders to display balances and top rankings in scoreboards, holograms,
  or any PAPI-compatible plugin.
- **Multi-Database Support:** Compatible with MariaDB, PostgreSQL, and SQLite. The database used is automatically
  inherited from the main Skyllia configuration.
- **Async Cache:** Balance and leaderboard data used by PlaceholderAPI are cached with configurable TTLs to avoid
  hammering the database, with non-blocking async refresh. Economy operations (balance checks, withdrawals, deposits)
  always hit the database directly to guarantee consistency.
- **Folia Support:** Fully compatible with Folia.

---

## Requirements

### Classic mode

| Dependency                                                                | Required | Role                                          |
|---------------------------------------------------------------------------|----------|-----------------------------------------------|
| Paper / Folia 1.20.6+                                                     | ✅        | Server software                               |
| [Skyllia](https://modrinth.com/plugin/skyllia)                            | ✅        | Skyblock platform                             |
| [Vault](https://www.spigotmc.org/resources/vault.34315/)                  | ✅        | Economy abstraction                           |
| An economy plugin (e.g. EssentialsX)                                      | ✅        | Actual money provider                         |
| [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) | ❌        | Optional — enables `%skybank_*%` placeholders |

### Island economy mode

| Dependency                                                                | Required | Role                                              |
|---------------------------------------------------------------------------|----------|---------------------------------------------------|
| Paper / Folia 1.20.5+                                                     | ✅        | Server software                                   |
| [Skyllia](https://modrinth.com/plugin/skyllia)                            | ✅        | Skyblock platform                                 |
| [Vault](https://www.spigotmc.org/resources/vault.34315/)                  | ✅        | Economy abstraction (SkylliaBank is the provider) |
| [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) | ❌        | Optional — enables `%skybank_*%` placeholders     |

No separate economy plugin (EssentialsX, etc.) is needed in island economy mode — SkylliaBank provides the economy
itself.

The database (MariaDB, PostgreSQL, or SQLite) is shared with Skyllia and requires no separate configuration.

---

## Installation

1. Download the latest `SkylliaBank.jar` from [modrinth](https://modrinth.com/plugin/skyllia/versions).
2. Place the jar in your server's `plugins/` directory alongside Skyllia and Vault.
3. Start or restart the server. SkylliaBank will auto-detect the database configured in Skyllia and create its own
   tables.
4. *(Optional)* Edit `plugins/SkylliaBank/config.toml` to adjust balance formatting, cache TTLs, and economy mode (
   see [Configuration](#configuration)).

---

## Configuration

The configuration file is located at `plugins/SkylliaBank/config.toml` and is generated automatically on first launch.

```toml
[bank-account]
# Java DecimalFormat pattern used to display balances
format = "#,##0.##"

# Locale used for decimal/grouping separators (e.g. "fr_FR", "en_US")
locale = "fr_FR"

# If true, the island bank account will be reset (balance set to 0) when the island is deleted
reset-on-island-delete = false

[cache]
# Time-to-live in seconds for individual island balance cache entries (PAPI only)
ttl = 60

# Time-to-live in seconds for the top-balances leaderboard cache
ttl-top = 300

[vault]
# Set to true to make SkylliaBank register itself as the Vault Economy provider.
# Any plugin that calls the Vault Economy API (shops, auction houses, etc.)
# will then read and write the island bank of the player instead of a
# per-player wallet. No separate economy plugin is needed in this mode.
# Set to false (default) to use an existing economy plugin as usual.
enable-island-economy = false

# Currency name displayed by third-party plugins through Vault
currency-name-singular = "Coin"
currency-name-plural = "Coins"

# Number of decimal digits Vault reports (-1 = no rounding)
fractional-digits = -1
```

---

## Economy Modes

### Classic mode (`enable-island-economy = false`)

The default behaviour. SkylliaBank consumes an existing Vault Economy provider (EssentialsX, CMI, etc.). Players
transfer money between their personal wallet and the island bank using `/is bank deposit` and `/is bank withdraw`.

### Island economy mode (`enable-island-economy = true`)

SkylliaBank registers itself as the Vault Economy provider at `ServicePriority.Highest`. Every Vault economy call made
by any plugin is transparently routed to the island bank of the involved player:

```
ShopAdmin buys an item
  └─▶ economy.withdrawPlayer(player, price)   [standard Vault call]
        └─▶ VaultIslandEconomy.withdrawPlayer(player, price)
              └─▶ looks up player's island via SkylliaAPI
                    └─▶ BankManager.withdraw(islandId, price)
```

**Balance consistency:** economy reads (`getBalance`, `has`) and writes (`withdraw`, `deposit`) always go directly to
the database — no cache is used on this path. This prevents any duplication exploit where a plugin checks `has()` and
then calls `withdraw()` without verifying the result, since the database itself enforces the `balance >= amount`
constraint atomically.

**If a player has no island**, all read operations return `0` and all write operations return a `FAILURE` response. No
money is silently lost.

**Legacy Vault methods** (`String playerName` overloads, deprecated since VaultAPI 1.4) resolve the player name against
online players only. Offline player lookups via `Bukkit.getOfflinePlayer(name)` are intentionally avoided to prevent
blocking I/O and stale name-to-UUID mappings after a rename. If the player is offline, a `FAILURE` response is returned.
No modern plugin uses these methods.

---

## Commands

### Player Commands (`/is bank ...`)

| Command                                           | Description                                                                                             | Permission                        |
|---------------------------------------------------|---------------------------------------------------------------------------------------------------------|-----------------------------------|
| `/is bank` or `/is bank balance`                  | Show your island's current bank balance.                                                                | `skyllia.bank.balance`            |
| `/is bank deposit <amount>`                       | Transfer money from your wallet to the island bank. Also requires the island-level deposit permission.  | `skyllia.bank.deposit`            |
| `/is bank withdraw <amount>`                      | Transfer money from the island bank to your wallet. Also requires the island-level withdraw permission. | `skyllia.bank.withdraw`           |
| `/is bank top [page]` or `/is bank baltop [page]` | Display the paginated leaderboard of islands by balance (10 per page).                                  | `skyllia.bank.top`                |
| `/is bank rank`                                   | Show your island's current rank in the leaderboard.                                                     | *(none beyond island membership)* |

Aliases for `/is bank`: `/is money`, `/is bal`, `/is balance`.

### Admin Commands (`/skylliadmin bank ...`)

| Command                                          | Description                                                                  |
|--------------------------------------------------|------------------------------------------------------------------------------|
| `/skylliadmin bank balance <player>`             | View the bank balance of the island belonging to `<player>`.                 |
| `/skylliadmin bank deposit <player> <amount>`    | Add money directly to an island's bank (does not touch any player's wallet). |
| `/skylliadmin bank withdraw <player> <amount>`   | Remove money from an island's bank.                                          |
| `/skylliadmin bank setbalance <player> <amount>` | Overwrite an island's bank balance with an exact value.                      |

All admin commands require the `skyllia.bank.admin` permission.

---

## Island Permissions

SkylliaBank registers two per-island permission nodes through Skyllia's permission system:

| Permission Node (NamespacedKey)     | What it controls                                                    |
|-------------------------------------|---------------------------------------------------------------------|
| `skylliabank:command.bank.deposit`  | Whether a member of the island is allowed to deposit into the bank  |
| `skylliabank:command.bank.withdraw` | Whether a member of the island is allowed to withdraw from the bank |

These are configured per island role via Skyllia's permission manager, independently of the Bukkit permissions above.

---

## PlaceholderAPI Placeholders

The identifier is `skybank`. All placeholders are resolved for the requesting player's island.

| Placeholder                           | Description                                                                           |
|---------------------------------------|---------------------------------------------------------------------------------------|
| `%skybank_balance%`                   | Raw balance of the player's island bank (e.g. `12500.5`).                             |
| `%skybank_balance_formatted%`         | Balance formatted using the locale and pattern from `config.toml` (e.g. `12 500,50`). |
| `%skybank_rank%`                      | The leaderboard rank of the player's island (empty if unranked).                      |
| `%skybank_top_<n>_name%`              | Island owner name at leaderboard position `<n>` (1-indexed).                          |
| `%skybank_top_<n>_balance%`           | Raw balance of the island at position `<n>`.                                          |
| `%skybank_top_<n>_balance_formatted%` | Formatted balance of the island at position `<n>`.                                    |

Balance and top-list results are served from an async-refreshed cache. When a value is not yet cached, the placeholder
returns an empty string or `0` and triggers a background refresh.

---

## How It Works

### Deposit Flow

1. The player runs `/is bank deposit <amount>`.
2. SkylliaBank checks Bukkit permission (`skyllia.bank.deposit`) and the island-level role permission.
3. Vault withdraws `<amount>` from the player's personal wallet.
4. The amount is credited to the island's database record.
5. If the database write fails, the money is automatically refunded to the player's wallet. If the refund also fails, a
   critical error is logged for manual intervention.

### Withdrawal Flow

1. The player runs `/is bank withdraw <amount>`.
2. SkylliaBank checks permissions and verifies the island bank has sufficient balance.
3. The amount is deducted from the island's database record.
4. Vault deposits the amount into the player's personal wallet.
5. If the Vault deposit fails, the amount is automatically re-deposited into the island bank.

### Caching

- The PAPI cache stores each island's balance for `cache.ttl` seconds (default 60 s) and the leaderboard for
  `cache.ttl-top` seconds (default 300 s). Any deposit, withdrawal, or admin operation immediately invalidates the
  relevant cache entries.
- PAPI placeholder reads that hit a cold cache return a fallback value instantly and schedule a non-blocking async
  reload.
- Vault Economy calls (used by shops and other plugins) **bypass the cache entirely** and always read the live database
  value to guarantee correctness.

---

## Support

For help, please join the [Discord server](https://discord.gg/uUJQEB7XNN).

## Contributing

Contributions are welcome! Please read the [contribution guidelines](../../CONTRIBUTING.md) before opening a pull
request.

## License

SkylliaBank is licensed under the [MIT License](../../LICENSE).
