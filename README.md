# Auto Clip

A RuneLite plugin that functions as an instant replay recorder for Old School RuneScape. It continuously keeps the last N seconds (default 10s, configurable) of gameplay in memory and automatically saves `.mp4` video clips when notable events occur, organized using the same folder and naming conventions as RuneLite's Screenshot plugin.

## Features

- **Instant Replay Buffer**: Continuously records gameplay in memory (configurable clip duration, post-event buffer, and frame rate) and exports standard H.264 `.mp4` clips.
- **Privacy Protection**: Automatically pauses frame capture whenever the client is not logged in or when sensitive interfaces (such as the Bank PIN keypad, Bank PIN settings, or the post-login Welcome screen) are open.
- **Screenshot-Style Organization**: Saves clips into `<plugin-data>/auto-clip/<PlayerName>/<Category>/<Event> yyyy-MM-dd_HH-mm-ss.mp4`.
- **Automatic Event Triggers**:
  - Level ups and 99s (both dialogue popups and chat-only level ups)
  - Quest completions, Clue Scroll rewards, Barrows, Raids (CoX, ToB, ToA), Moons of Peril, The Gauntlet, Fortis Colosseum, and Doom of Mokhaiotl
  - Boss kills, Pets, Valuable drops, and Untradeable drops
  - Collection Log entries, Combat Achievements, and League tasks
  - Player, Friend/FC, and Clan deaths, PvP kills, Duels, Kingdom rewards, BA high gambles, Friends Chat kicks, and Wilderness loot chests
- **Manual Clip Saving**: Save the current replay buffer at any time via a configurable hotkey or the client toolbar button.