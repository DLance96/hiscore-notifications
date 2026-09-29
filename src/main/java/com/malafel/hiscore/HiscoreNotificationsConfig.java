package com.malafel.hiscore;

import com.malafel.hiscore.leaderboard.ValidLeaderboard;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("hiscorenotifications")
public interface HiscoreNotificationsConfig extends Config
{
	@ConfigSection(
			name = "Info",
			description = "Please read",
			position = 100
	)
	String SECTION_INFO = "info";
	@ConfigItem(
			keyName = "beCool",
			name = "<html>Please be cool. Remember that people cannot opt in or out of being on the leaderboards. Please don't go reaching out to people unless you know they're welcoming friendly competition.</html>",
			description = "Please be cool. Remember that people cannot opt in or out of being on the leaderboards. Please don't go reaching out to people unless you know they're welcoming friendly competition.",
			section = SECTION_INFO,
			position = 1
	)
	default void beCool() {}

	@ConfigSection(
			name = "Hiscore Ranks",
			description = "All hiscore rank notification settings",
			position = 200
	)
	String SECTION_LEADERBOARD = "leaderboardRanks";

	@ConfigItem(
			keyName = "chosenLeaderboard",
			name = "Leaderboard",
			description = "Configures which leaderboard to use for rank notifications.",
			section = SECTION_LEADERBOARD,
			position = 1
	)
	default ValidLeaderboard chosenLeaderboard()
	{
		return ValidLeaderboard.NORMAL;
	}

	@ConfigItem(
			keyName = "tensInterval",
			name = "1-99 Interval",
			description = "Configures gaps between notifications when your rank is between 1 and 99. Should be less than or equal to 100s Interval. 0 disables notifications for all ranks except 1.",
			section = SECTION_LEADERBOARD,
			position = 2
	)
	default int tensInterval()
	{
		return 1;
	}

	@ConfigItem(
			keyName = "hundredsInterval",
			name = "100s Interval",
			description = "Configures gaps between notifications when your rank is between 100 and 999. Should be less than or equal to 1000s Interval. 0 disables notifications for this range and higher.",
			section = SECTION_LEADERBOARD,
			position = 3
	)
	default int hundredsInterval()
	{
		return 1;
	}

	@ConfigItem(
			keyName = "thousandsInterval",
			name = "1000s Interval",
			description = "Configures gaps between notifications when your rank is between 1000 and 9999. Should be less than or equal to 10,000s Interval. 0 disables notifications for this range and higher.",
			section = SECTION_LEADERBOARD,
			position = 4
	)
	default int thousandsInterval()
	{
		return 10;
	}

	@ConfigItem(
			keyName = "tenThousandsInterval",
			name = "10,000s Interval",
			description = "Configures gaps between notifications when your rank is between 10,000 and 99,999. Should be less than or equal to 100,000+ Interval. 0 disables notifications for this range and higher.",
			section = SECTION_LEADERBOARD,
			position = 5
	)
	default int tenThousandsInterval()
	{
		return 100;
	}

	@ConfigItem(
			keyName = "hundredThousandsInterval",
			name = "100,000+ Interval",
			description = "Configures gaps between notifications when your rank is 100,000 or higher. 0 disables notifications for this range.",
			section = SECTION_LEADERBOARD,
			position = 6
	)
	default int hundredThousandsInterval()
	{
		return 1000;
	}

	@ConfigSection(
			name = "Tracked Players",
			description = "Notifications for surpassing specific players",
			position = 250
	)
	String SECTION_TRACKED_PLAYERS = "trackedPlayers";

	@ConfigItem(
			keyName = "trackedPlayersEnabled",
			name = "Enable Tracked Players",
			description = "Notify when you surpass any of the tracked players in a skill or boss. Uses the Skill and Boss notification settings.",
			section = SECTION_TRACKED_PLAYERS,
			position = 1
	)
	default boolean trackedPlayersEnabled()
	{
		return false;
	}

	@ConfigItem(
			keyName = "trackedPlayers",
			name = "Players",
			description = "Comma-separated list of player names to track. Only the first 10 names are used.",
			section = SECTION_TRACKED_PLAYERS,
			position = 2
	)
	default String trackedPlayers()
	{
		return "";
	}

	@ConfigSection(
			name = "Bosses",
			description = "Settings for what skills we want to display notifications on",
			position = 300
	)
	String SECTION_BOSSES = "bosses";

	@ConfigItem(
			keyName = "showBossNotifications",
			name = "Enable Boss Pop-up Notifications",
			description = "Should we show leagues style notifications for boss KC ranks?",
			section = SECTION_BOSSES,
			position = 1
	)
	default boolean showBossNotifications()
	{
		return true;
	}

	@ConfigItem(
			keyName = "showBossChatNotifications",
			name = "Enable Boss Game Chat Notifications",
			description = "Should we show game chat notifications for boss KC ranks?",
			section = SECTION_BOSSES,
			position = 2
	)
	default boolean showBossChatNotifications()
	{
		return false;
	}

	@ConfigSection(
			name = "Skills",
			description = "Settings for what skills we want to display notifications on",
			position = 400
	)
	String SECTION_SKILLS = "skills";

	@ConfigItem(
			keyName = "showSkillPopupNotifications",
			name = "Enable Skill Pop-up Notifications",
			description = "Should we show leagues style notifications for skill ranks?",
			section = SECTION_SKILLS,
			position = 1
	)
	default boolean showSkillPopupNotifications() { return true; }

	@ConfigItem(
			keyName = "showSkillChatNotifications",
			name = "Enable Skill Game Chat Notifications",
			description = "Should we show game chat notifications for skill ranks?",
			section = SECTION_SKILLS,
			position = 2
	)
	default boolean showSkillChatNotifications() { return false; }

	@ConfigItem(
			keyName = "showAttackNotifications",
			name = "Attack",
			description = "Should we show Attack notifications?",
			section = SECTION_SKILLS,
			position = 3
	)
	default boolean showAttackNotifications()
	{
		return true;
	}

	@ConfigItem(
			keyName = "showDefenceNotifications",
			name = "Defence",
			description = "Should we show Defence notifications?",
			section = SECTION_SKILLS,
			position = 4
	)
	default boolean showDefenceNotifications()
	{
		return true;
	}

	@ConfigItem(
			keyName = "showStrengthNotifications",
			name = "Strength",
			description = "Should we show Strength notifications?",
			section = SECTION_SKILLS,
			position = 5
	)
	default boolean showStrengthNotifications()
	{
		return true;
	}

	@ConfigItem(
			keyName = "showHitpointsNotifications",
			name = "Hitpoints",
			description = "Should we show Hitpoints notifications?",
			section = SECTION_SKILLS,
			position = 6
	)
	default boolean showHitpointsNotifications()
	{
		return true;
	}

	@ConfigItem(
			keyName = "showRangedNotifications",
			name = "Ranged",
			description = "Should we show Ranged notifications?",
			section = SECTION_SKILLS,
			position = 7
	)
	default boolean showRangedNotifications()
	{
		return true;
	}

	@ConfigItem(
			keyName = "showPrayerNotifications",
			name = "Prayer",
			description = "Should we show Prayer notifications?",
			section = SECTION_SKILLS,
			position = 8
	)
	default boolean showPrayerNotifications()
	{
		return true;
	}

	@ConfigItem(
			keyName = "showMagicNotifications",
			name = "Magic",
			description = "Should we show Magic notifications?",
			section = SECTION_SKILLS,
			position = 9
	)
	default boolean showMagicNotifications()
	{
		return true;
	}

	@ConfigItem(
			keyName = "showCookingNotifications",
			name = "Cooking",
			description = "Should we show Cooking notifications?",
			section = SECTION_SKILLS,
			position = 10
	)
	default boolean showCookingNotifications()
	{
		return true;
	}

	@ConfigItem(
			keyName = "showWoodcuttingNotifications",
			name = "Woodcutting",
			description = "Should we show Woodcutting notifications?",
			section = SECTION_SKILLS,
			position = 11
	)
	default boolean showWoodcuttingNotifications()
	{
		return true;
	}

	@ConfigItem(
			keyName = "showFletchingNotifications",
			name = "Fletching",
			description = "Should we show Fletching notifications?",
			section = SECTION_SKILLS,
			position = 12
	)
	default boolean showFletchingNotifications()
	{
		return true;
	}

	@ConfigItem(
			keyName = "showFishingNotifications",
			name = "Fishing",
			description = "Should we show Fishing notifications?",
			section = SECTION_SKILLS,
			position = 13
	)
	default boolean showFishingNotifications()
	{
		return true;
	}

	@ConfigItem(
			keyName = "showFiremakingNotifications",
			name = "Firemaking",
			description = "Should we show Firemaking notifications?",
			section = SECTION_SKILLS,
			position = 14
	)
	default boolean showFiremakingNotifications()
	{
		return true;
	}


	@ConfigItem(
			keyName = "showCraftingNotifications",
			name = "Crafting",
			description = "Should we show Crafting notifications?",
			section = SECTION_SKILLS,
			position = 15
	)
	default boolean showCraftingNotifications()
	{
		return true;
	}

	@ConfigItem(
			keyName = "showSmithingNotifications",
			name = "Smithing",
			description = "Should we show Smithing notifications?",
			section = SECTION_SKILLS,
			position = 16
	)
	default boolean showSmithingNotifications()
	{
		return true;
	}

	@ConfigItem(
			keyName = "showMiningNotifications",
			name = "Mining",
			description = "Should we show Mining notifications?",
			section = SECTION_SKILLS,
			position = 17
	)
	default boolean showMiningNotifications()
	{
		return true;
	}

	@ConfigItem(
			keyName = "showHerbloreNotifications",
			name = "Herblore",
			description = "Should we show Herblore notifications?",
			section = SECTION_SKILLS,
			position = 18
	)
	default boolean showHerbloreNotifications()
	{
		return true;
	}

	@ConfigItem(
			keyName = "showAgilityNotifications",
			name = "Agility",
			description = "Should we show Agility notifications?",
			section = SECTION_SKILLS,
			position = 19
	)
	default boolean showAgilityNotifications()
	{
		return true;
	}

	@ConfigItem(
			keyName = "showThievingNotifications",
			name = "Thieving",
			description = "Should we show Thieving notifications?",
			section = SECTION_SKILLS,
			position = 20
	)
	default boolean showThievingNotifications()
	{
		return true;
	}

	@ConfigItem(
			keyName = "showSlayerNotifications",
			name = "Slayer",
			description = "Should we show Slayer notifications?",
			section = SECTION_SKILLS,
			position = 21
	)
	default boolean showSlayerNotifications()
	{
		return true;
	}

	@ConfigItem(
			keyName = "showFarmingNotifications",
			name = "Farming",
			description = "Should we show Farming notifications?",
			section = SECTION_SKILLS,
			position = 22
	)
	default boolean showFarmingNotifications()
	{
		return true;
	}

	@ConfigItem(
			keyName = "showRunecraftNotifications",
			name = "Runecraft",
			description = "Should we show Runecraft notifications?",
			section = SECTION_SKILLS,
			position = 23
	)
	default boolean showRunecraftNotifications()
	{
		return true;
	}

	@ConfigItem(
			keyName = "showHunterNotifications",
			name = "Hunter",
			description = "Should we show Hunter notifications?",
			section = SECTION_SKILLS,
			position = 24
	)
	default boolean showHunterNotifications()
	{
		return true;
	}

	@ConfigItem(
			keyName = "showConstructionNotifications",
			name = "Construction",
			description = "Should we show Construction notifications?",
			section = SECTION_SKILLS,
			position = 25
	)
	default boolean showConstructionNotifications()
	{
		return true;
	}

	@ConfigItem(
			keyName = "showSailingNotifications",
			name = "Sailing",
			description = "Should we show Sailing notifications?",
			section = SECTION_SKILLS,
			position = 26
	)
	default boolean showSailingNotifications()
	{
		return true;
	}

	@ConfigSection(
			name = "Skill Messages",
			description = "Settings for the notification messages",
			position = 500
	)
	String SECTION_NOTIFICATION = "notifications";
	@ConfigItem(
			keyName = "notificationLeaderboardRankTitle",
			name = "Title",
			description = "Can include $rank, $xp, $player, and $skill variables.",
			section = SECTION_NOTIFICATION,
			position = 1
	)
	default String notificationLeaderboardRankTitle()
	{
		return "Hiscore rank milestone";
	}
	@ConfigItem(
			keyName = "notificationLeaderboardRankText",
			name = "Text",
			description = "Can include $rank, $xp, $player, and $skill variables.",
			section = SECTION_NOTIFICATION,
			position = 2
	)
	default String notificationLeaderboardRankText()
	{
		return "Achieved rank $rank in $skill, surpassing $name!";
	}

	@ConfigSection(
			name = "Boss Messages",
			description = "Settings for the notification boxes",
			position = 600
	)
	String SECTION_BOSS_NOTIFICATION = "bossNotifications";
	@ConfigItem(
			keyName = "notificationBossLeaderboardRankTitle",
			name = "Title",
			description = "Can include $rank, $kc, $passedkc, $player, and $boss variables.",
			section = SECTION_BOSS_NOTIFICATION,
			position = 1
	)
	default String notificationBossLeaderboardRankTitle()
	{
		return "Boss rank milestone";
	}
	@ConfigItem(
			keyName = "notificationBossLeaderboardRankText",
			name = "Text",
			description = "Can include $rank, $kc, $passedkc, $player, and $boss variables.",
			section = SECTION_BOSS_NOTIFICATION,
			position = 2
	)
	default String notificationBossLeaderboardRankText()
	{
		return "Achieved rank $rank at $boss with $kc KC, surpassing $name!";
	}
}
