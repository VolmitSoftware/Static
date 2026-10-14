# Static

Player statistics, rankings, and activity reports for Minecraft 26.1 through 26.3. Requires Java 25+.

Targets Spigot, Paper, Purpur, Leaf, Folia, and Canvas. See the [compatibility notes](https://github.com/VolmitSoftware/docs/blob/main/static/04-compatibility-operations.md) for tested versions.

- Player profiles, personal bests, and lifetime or daily, weekly, and monthly rankings
- Detailed counters, active/AFK time, and session history
- Seasons and cosmetic milestones
- Optional web dashboard and player-controlled profile visibility

## Commands

| Command | Use |
| --- | --- |
| `/stats [player]` | Open a profile |
| `/static top <statistic>` | View rankings |
| `/static bests` | View personal records |
| `/static seasons` | Browse competitions |
| `/static milestones` | View milestone progress |
| `/static config` | Edit settings |
| `/static language` | Choose or edit a language |
| `/static debug dump upload=false` | Save a local debug report |

Settings use TOML and support hot reload. English is generated on startup; other languages download when selected. Missing translations fall back to English. PlaceholderAPI and Votifier are optional.

## Building

With JDK 25 installed, run `./gradlew build` or `.\gradlew.bat build` on Windows. The build places `Static.jar` in the workspace `BUILDS` directory.

See the [documentation](https://github.com/VolmitSoftware/docs/blob/main/static.md) for setup and the full command list.

Based on the statistics from [cyberpwnn/Static](https://github.com/cyberpwnn/Static).
