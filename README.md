# Static

Player statistics and rankings for Bukkit-compatible servers on Minecraft 26.1 through 26.3, including patch releases, with Java 25 or newer. Earlier Minecraft versions and release lines after 26.3 are unsupported; startup checks the server version before initializing services.

Spigot, Paper, Purpur, Leaf, Folia, and Canvas are platform targets. Build checks and runtime evidence are separate: see the [compatibility reference](https://github.com/VolmitSoftware/docs/blob/main/static/04-compatibility-operations.md) for the verified scope and remaining limits. Earlier artifact results do not certify the current distribution.

`/stats` opens your profile. `/stats <player>` also accepts recorded offline names and UUIDs. `/static top <statistic>` shows rankings. Administrators use `/static config`, `/static language`, and `/static debug dump upload=false`.

Profiles include category filters, up to eight saved favorites, localized values, and recording coverage. Rankings show shared tie ranks, your position and gap, nearby players, and update times. Lifetime, calendar-day, calendar-week, and calendar-month views share an asynchronous ranking cache. Active/AFK counters and recent sessions retain observed activity with configurable history limits.

`/static milestones` shows configurable lifetime targets, progress, and earned cosmetic badges. Completions persist with player statistics before private live messages and API callbacks run. Existing totals qualify silently when targets are installed; no material rewards are attached.

`/static seasons` browses named events. Each event has a fixed time window, scoring statistic, and independent eligibility rules; `/static season <id>` shows personal progress and `/static seasontop <id>` shows standings. Completed results retain tied winners and survive restarts. The Seasons configuration category edits future definitions as drafts with explicit saves.

`/static details` shows selected lifetime material and entity breakdowns, catches, breeding, crafted output, and traded output. The fixed catalog has 55 counters, with 41 selected by default. Configuration changes retain recorded values, and each counter reports its first and latest observation. These totals have their own API and do not enter calendar rankings or season scores. See the [detailed-counter reference](https://github.com/VolmitSoftware/docs/blob/main/static/08-detailed-counters.md).

`/static detailtop <counter> [page]` ranks positive observed detailed totals, including offline profiles. Click a counter in the details menu to request its chat board. Rankings preserve exact integer values, share ranks for ties, and show personal rank and gaps. Disabled selections retain their recorded standings; these boards use lifetime totals.

`/static participation [period] [offset]` reports retained unique players, new/returning/unknown cohorts, and median eligible duration for normally ended sessions. Operators can select current or earlier calendar days, weeks, and months. Reports disclose retention limits and sample size; API-added counters do not create participants. See the [participation reference](https://github.com/VolmitSoftware/docs/blob/main/static/10-participation.md).

The optional operator web dashboard provides participation reports, paged rankings, player search, and profiles with detailed counters, personal bests, and bounded session/history views. It binds to loopback, requires a separate access token for operator API requests, and supports an HTTPS reverse proxy for remote access. It is disabled by default; see the [dashboard reference](https://github.com/VolmitSoftware/docs/blob/main/static/11-web-dashboard.md) for setup, limits, and language behavior.

`/static privacy` selects private, server-only, or public visibility, with server-only as the default. Private players remain accessible to their owner and authorized administrators but are excluded from normal ranking boards and placeholders. Public web pages additionally require server enablement and explicit player opt-in; shared UUID links show lifetime totals, detailed counters, and personal-best values without activity dates or sessions. Visibility does not stop collection, delete data, or restrict trusted plugin APIs and operator reports.

Ratio boards use configurable minimum activity thresholds. Profiles show numerator/denominator totals and qualification, while unqualified players keep their recorded totals. The Ranking configuration category supports hot-reloaded thresholds, and qualification data is available through the API and PlaceholderAPI.

`/static bests` shows highest observed day, week, and month totals for base counters, plus online, active, and AFK session-duration records. Right-click the profile's recording information item to open it. Best values and source dates survive history pruning; recovered and ongoing records are labeled explicitly. See the [personal-best reference](https://github.com/VolmitSoftware/docs/blob/main/static/09-personal-bests.md).

Make JDK 25 available to Gradle, then run `./gradlew build` or `gradlew.bat build`. Runtime code, tests, and the gameplay fixture compile against Spigot 26.1 with Java 25; separate compiler checks use Paper 26.3 build 140-beta and Spigot 26.3. The `verifyJava25Artifact` task checks all shaded classes, allowing valid multi-release entries, and requires Java 25 bytecode for the plugin entry point and Bukkit API 26.1 metadata. The build stages `Static.jar` in the workspace `BUILDS` directory. VolmLib is included and relocated into the jar.

Documentation is maintained in the central [VolmitSoftware docs repository](https://github.com/VolmitSoftware/docs/blob/main/static.md). Translation sources are under `src/main/resources/languages`; they are excluded from runtime jars. Only English is generated at startup. Other languages download from this repository's `main` branch when selected.

The original [cyberpwnn/Static](https://github.com/cyberpwnn/Static) supplies the statistics reference. This version uses its own current data format and does not require Phantom, MySQL, PlaceholderAPI, or Votifier. PlaceholderAPI and Votifier integrations activate when those plugins are installed.

Configuration and language changes are applied through the in-game editors and existing hot reload. Language selection supports personal preferences, server defaults, reset, and message editing. Diagnostic tools are available under `/static debug`, including `dump upload=false` and `version`. bStats reporting is not enabled.

Malformed language file edits are rejected while the last valid messages remain active. Missing message entries use built-in English.





