package com.autoclip;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup("auto-clip")
public interface AutoClipConfig extends Config
{
	@ConfigSection(
		name = "Recording Settings",
		description = "Configure how gameplay replay clips are recorded and saved.",
		position = 0
	)
	String recordingSection = "recording";

	@ConfigSection(
		name = "What to Clip",
		description = "Select which gameplay events automatically save a replay clip.",
		position = 99
	)
	String whatSection = "what";

	@Range(min = 3, max = 60)
	@Units(Units.SECONDS)
	@ConfigItem(
		keyName = "clipDuration",
		name = "Clip duration",
		description = "How many seconds of gameplay before the event to keep in memory and save.",
		position = 0,
		section = recordingSection
	)
	default int clipDuration()
	{
		return 10;
	}

	@Range(min = 0, max = 10)
	@Units(Units.SECONDS)
	@ConfigItem(
		keyName = "postEventDelay",
		name = "Post-event buffer",
		description = "How many extra seconds to continue recording after an event triggers before saving.",
		position = 1,
		section = recordingSection
	)
	default int postEventDelay()
	{
		return 2;
	}

	@Range(min = 5, max = 30)
	@Units(" fps")
	@ConfigItem(
		keyName = "frameRate",
		name = "Frame rate",
		description = "Target frame rate for the replay buffer.",
		position = 2,
		section = recordingSection
	)
	default int frameRate()
	{
		return 15;
	}

	@ConfigItem(
		keyName = "includeFrame",
		name = "Include client frame",
		description = "Configures whether or not the client frame is included in recorded clips.",
		position = 3,
		section = recordingSection
	)
	default boolean includeFrame()
	{
		return false;
	}

	@ConfigItem(
		keyName = "notifyWhenSaved",
		name = "Notify when clip saved",
		description = "Configures whether or not you are notified when a clip has been saved.",
		position = 4,
		section = recordingSection
	)
	default boolean notifyWhenSaved()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hotkey",
		name = "Save clip hotkey",
		description = "When you press this key a replay clip of the current buffer will be saved.",
		position = 5,
		section = recordingSection
	)
	default Keybind hotkey()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(
		keyName = "rewards",
		name = "Clip rewards",
		description = "Configures whether clips are saved for clues, barrows, and quest completion.",
		position = 0,
		section = whatSection
	)
	default boolean clipRewards()
	{
		return true;
	}

	@ConfigItem(
		keyName = "levels",
		name = "Clip levels",
		description = "Configures whether clips are saved for level ups.",
		position = 1,
		section = whatSection
	)
	default boolean clipLevels()
	{
		return true;
	}

	@ConfigItem(
		keyName = "kingdom",
		name = "Clip Kingdom Rewards",
		description = "Configures whether clips are saved for Kingdom Rewards.",
		position = 2,
		section = whatSection
	)
	default boolean clipKingdom()
	{
		return true;
	}

	@ConfigItem(
		keyName = "pet",
		name = "Clip Pets",
		description = "Configures whether clips are saved for receiving pets.",
		position = 3,
		section = whatSection
	)
	default boolean clipPet()
	{
		return true;
	}

	@ConfigItem(
		keyName = "kills",
		name = "Clip PvP Kills",
		description = "Configures whether or not clips are automatically saved for PvP kills.",
		position = 4,
		section = whatSection
	)
	default boolean clipKills()
	{
		return false;
	}

	@ConfigItem(
		keyName = "boss",
		name = "Clip Boss Kills",
		description = "Configures whether or not clips are automatically saved for boss kills.",
		position = 5,
		section = whatSection
	)
	default boolean clipBossKills()
	{
		return false;
	}

	@ConfigItem(
		keyName = "playerDeath",
		name = "Clip Player Death",
		description = "Configures whether or not clips are automatically saved when you die.",
		position = 6,
		section = whatSection
	)
	default boolean clipPlayerDeath()
	{
		return false;
	}

	@ConfigItem(
		keyName = "friendDeath",
		name = "Clip Friend Death",
		description = "Configures whether or not clips are automatically saved when friends or friends chat members die.",
		position = 7,
		section = whatSection
	)
	default boolean clipFriendDeath()
	{
		return false;
	}

	@ConfigItem(
		keyName = "clanDeath",
		name = "Clip Clan Death",
		description = "Configures whether or not clips are automatically saved when clan members die.",
		position = 8,
		section = whatSection
	)
	default boolean clipClanDeath()
	{
		return false;
	}

	@ConfigItem(
		keyName = "duels",
		name = "Clip Duels",
		description = "Configures whether or not clips are automatically saved of the duel end screen.",
		position = 9,
		section = whatSection
	)
	default boolean clipDuels()
	{
		return false;
	}

	@ConfigItem(
		keyName = "valuableDrop",
		name = "Clip Valuable drops",
		description = "Configures whether or not clips are automatically saved when you receive a valuable drop.",
		position = 10,
		section = whatSection
	)
	default boolean clipValuableDrop()
	{
		return false;
	}

	@ConfigItem(
		keyName = "valuableDropThreshold",
		name = "Valuable threshold",
		description = "The minimum value to save clips of valuable drops.",
		position = 11,
		section = whatSection
	)
	default int valuableDropThreshold()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "untradeableDrop",
		name = "Clip Untradeable drops",
		description = "Configures whether or not clips are automatically saved when you receive an untradeable drop.",
		position = 12,
		section = whatSection
	)
	default boolean clipUntradeableDrop()
	{
		return false;
	}

	@ConfigItem(
		keyName = "kick",
		name = "Clip Kicks from FC",
		description = "Take a clip when you kick a user from a friends chat.",
		position = 13,
		section = whatSection
	)
	default boolean clipKick()
	{
		return false;
	}

	@ConfigItem(
		keyName = "baHighGamble",
		name = "Clip BA high gambles",
		description = "Take a clip of your reward from a high gamble at Barbarian Assault.",
		position = 14,
		section = whatSection
	)
	default boolean clipHighGamble()
	{
		return false;
	}

	@ConfigItem(
		keyName = "collectionLogEntries",
		name = "Clip Collection log entries",
		description = "Take a clip when completing an entry in the collection log.",
		position = 15,
		section = whatSection
	)
	default boolean clipCollectionLogEntries()
	{
		return true;
	}

	@ConfigItem(
		keyName = "combatAchievements",
		name = "Clip Combat achievements",
		description = "Take a clip when completing a combat achievement task.",
		position = 16,
		section = whatSection
	)
	default boolean clipCombatAchievements()
	{
		return true;
	}

	@ConfigItem(
		keyName = "leagueTasks",
		name = "Clip League tasks",
		description = "Take a clip when completing a League task.",
		position = 17,
		section = whatSection
	)
	default boolean clipLeagueTasks()
	{
		return true;
	}

	@ConfigItem(
		keyName = "wildernessLootChest",
		name = "Clip Wilderness loot chest",
		description = "Take a clip when opening wilderness loot chest.",
		position = 18,
		section = whatSection
	)
	default boolean clipWildernessLootChest()
	{
		return true;
	}
}
