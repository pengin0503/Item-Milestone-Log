# Changelog

The section for a version is used as it is for the GitHub Release (created when a
`v<version>` tag is pushed) and for the CurseForge file changelog.

## 1.0.0

First release.

- Records your cumulative playtime (Time Played statistic) the first time each of your target items enters your inventory, and notifies you in chat.
- Per-player targets, server-wide default targets (config), presets: atm_star, vanilla_endgame.
- In-game GUI when the mod is also installed on the client. It has no default key: assign one under Controls → Item Milestone Log. Vanilla clients can join a server with the mod and use all /milestonelog (/iml) commands.
- Nothing is recorded in creative or spectator mode, and items you still carry when switching back to survival are only recorded once they have left your inventory and you obtain them again (config: ignoreCreativeAndSpectator, on by default).
- Minecraft 26.x versions bring over the records of a world played with the 1.21.1 version.
- English and Japanese.
