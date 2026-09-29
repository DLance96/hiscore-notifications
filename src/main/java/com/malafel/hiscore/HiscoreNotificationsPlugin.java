package com.malafel.hiscore;

import com.google.inject.Provides;
import javax.inject.Inject;

import com.malafel.hiscore.leaderboard.*;
import com.malafel.hiscore.notifications.NotificationManager;
import com.malafel.hiscore.util.RateLimitedHttpClientInterface;
import com.malafel.hiscore.util.Util;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.chatcommands.ChatCommandsPlugin;
import net.runelite.client.ui.JagexColors;
import net.runelite.client.util.QuantityFormatter;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Slf4j
@PluginDescriptor(
	name = "Hiscore Notifications"
)
public class HiscoreNotificationsPlugin extends Plugin
{
	private static final String CHAT_COMMANDS_PLUGIN_NAME = ChatCommandsPlugin.class.getSimpleName().toLowerCase();
	private static final String CONFIG_GROUP = "hiscorenotifications";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private HiscoreNotificationsConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private NotificationManager notifications;

	@Inject BossInfoRegistry bossInfoRegistry;

	@Inject
	private LeaderboardManager leaderboardManager;

	@Inject
	private TrackedPlayerManager trackedPlayerManager;

	@Inject
    RateLimitedHttpClientInterface clientInterface;

	private final Map<Skill, Integer> previousXpMap = new EnumMap<>(Skill.class);
	private ValidLeaderboard previousChosenLeaderboard = ValidLeaderboard.NORMAL;

	private int lastTensInterval = 1;
	private int lastHundredsInterval = 1;
	private int lastThousandsInterval = 10;
	private int lastTenThousandsInterval = 100;
	private int lastHundredThousandsInterval = 1000;

	@Provides
	HiscoreNotificationsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(HiscoreNotificationsConfig.class);
	}

	@Override
	protected void startUp()
	{
		lastTensInterval = config.tensInterval();
		lastHundredsInterval = config.hundredsInterval();
		lastThousandsInterval = config.thousandsInterval();
		lastTenThousandsInterval = config.tenThousandsInterval();
		lastHundredThousandsInterval = config.hundredThousandsInterval();

		previousChosenLeaderboard = config.chosenLeaderboard();
		notifications.startUp();
		previousXpMap.clear();
		resetManagers();
	}

	@Override
	protected void shutDown()
	{
		previousXpMap.clear();
		notifications.shutDown();
		resetManagers();
	}

	private void resetManagers()
	{
		leaderboardManager.reset();
		trackedPlayerManager.reset();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		// Clear previous XP when not logged in
		switch (gameStateChanged.getGameState())
		{
			case HOPPING:
			case LOGGING_IN:
			case LOGIN_SCREEN:
			case LOGIN_SCREEN_AUTHENTICATOR:
			case CONNECTION_LOST:
				previousXpMap.clear();
				resetManagers();
				break;
		}

	}

	@Subscribe
	public void onGameTick(GameTick event) {
		bossInfoRegistry.process(event);
		clientInterface.process(event);
		leaderboardManager.process(event);
		trackedPlayerManager.process(event);
	}

	private boolean isChatCommandsDisabled() {
		return "false".equals(configManager.getConfiguration(RuneLiteConfig.GROUP_NAME, CHAT_COMMANDS_PLUGIN_NAME));
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		// Chat commands plugin required for detecting KC changes. It makes this information available through config
		// changes in the `killcount` group.
		if (event.getGroup().equals("killcount") && !isChatCommandsDisabled()) {
			if (event.getNewValue() != null) {
				String boss = event.getKey();
				int kc = Integer.parseInt(event.getNewValue());
				onKcChanged(boss, kc);
			}
		}

		if (previousChosenLeaderboard != config.chosenLeaderboard()) {
			resetManagers();
			previousChosenLeaderboard = config.chosenLeaderboard();
		}

		if (event.getGroup().equals(CONFIG_GROUP) &&
			(event.getKey().equals("trackedPlayersEnabled") || event.getKey().equals("trackedPlayers"))) {
			trackedPlayerManager.reset();
		}

		if (lastTensInterval != config.tensInterval() ||
			lastHundredsInterval != config.hundredsInterval() ||
			lastThousandsInterval != config.thousandsInterval() ||
			lastTenThousandsInterval != config.tenThousandsInterval() ||
			lastHundredThousandsInterval != config.hundredThousandsInterval()) {
			lastTensInterval = config.tensInterval();
			lastHundredsInterval = config.hundredsInterval();
			lastThousandsInterval = config.thousandsInterval();
			lastTenThousandsInterval = config.tenThousandsInterval();
			lastHundredThousandsInterval = config.hundredThousandsInterval();
			resetManagers();
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged statChanged)
	{
		final Skill skill = statChanged.getSkill();

		final int currentXp = statChanged.getXp();
		final int previousXp = previousXpMap.getOrDefault(skill, -1);

		if (previousXp >= 0) {
			leaderboardManager.enableSkillTracking(skill);
		}
		previousXpMap.put(skill, currentXp);

		// Previous xp has to be set, and our current xp has to be higher or equal to the previous xp
		if (previousXp == -1 || previousXp >= currentXp)
		{
			return;
		}

		// Only standard worlds are allowed, and if a player is in LMS, we should abort.
		if (!Util.isStandardWorld(client) || Util.isInLMS(client))
		{
			log.debug("Not on a standard world nor in LMS.");
			return;
		}

		final List<SkillLeaderboardEntry> milestoneLeaderboardEntries = getMilestoneXpLeaderboardEntries(skill, previousXp, currentXp);
		if (shouldNotifyForSkill(skill) && !milestoneLeaderboardEntries.isEmpty())
		{
			log.debug("Milestone leaderboard skill rank to pop-up for {}", skill.getName());

			for (SkillLeaderboardEntry entry: milestoneLeaderboardEntries) {
				notifySkillLeaderboard(skill, entry);
			}
		}

		if (shouldNotifyForSkill(skill) && leaderboardManager.isSkillEligibleForNotifications(skill))
		{
			for (SkillLeaderboardEntry entry: trackedPlayerManager.getPassedSkillEntries(skill, previousXp, currentXp)) {
				log.debug("Passed tracked player {} in {}", entry.name, skill.getName());
				notifySkillLeaderboard(skill, entry);
			}
		}
	}

	private void onKcChanged(String boss, int kc) {
		if (!bossInfoRegistry.isReady()) {
			// It overcomplicates things to work around the list of bosses not being present when we get our first boss
			// kc of the session. Just ignore KC until the boss registry is populated.
			return;
		}

		final BossInfo bossInfo = bossInfoRegistry.fromChatCommandsLongName(boss);
		if (!bossInfo.isValid()) {
			return;
		}

		// Only standard worlds are allowed, and if a player is in LMS, we should abort.
		if (!Util.isStandardWorld(client) || Util.isInLMS(client))
		{
			log.debug("Not on a standard world nor in LMS.");
			return;
		}

		leaderboardManager.enableBossTracking(bossInfo);
		leaderboardManager.updateBossKc(bossInfo, kc);
		final List<BossLeaderboardEntry> milestoneLeaderboardEntries = getMilestoneKcLeaderboardEntries(bossInfo, kc-1, kc);
		if (shouldNotifyForBoss(bossInfo) && !milestoneLeaderboardEntries.isEmpty())
		{
			log.debug("Milestone leaderboard boss KC rank to notify for {}", bossInfo.chatCommandsLongName);

			for (BossLeaderboardEntry entry: milestoneLeaderboardEntries) {
				notifyBossLeaderboard(bossInfo, entry);
			}
		}

		if (shouldNotifyForBoss(bossInfo) && leaderboardManager.isBossEligibleForNotifications(bossInfo))
		{
			for (BossLeaderboardEntry entry: trackedPlayerManager.getPassedBossEntries(bossInfo, kc-1, kc)) {
				log.debug("Passed tracked player {} at {}", entry.name, bossInfo.chatCommandsLongName);
				notifyBossLeaderboard(bossInfo, entry);
			}
		}
	}

	/**
	 * Gets the list of milestone xp values between two numbers from values that were fetched from the OSRS hiscores
	 *
	 * @param skill Skill
	 * @param previousXp int
	 * @param currentXp int
	 * @return List<LeaderboardEntry>
	 */
	private List<SkillLeaderboardEntry> getMilestoneXpLeaderboardEntries(Skill skill, int previousXp, int currentXp) {
		return leaderboardManager.getMilestoneSkillLeaderboardEntries(skill, previousXp, currentXp);
	}

	/**
	 * Gets the list of milestone kc values between two numbers from values that were fetched from the OSRS hiscores
	 *
	 * @param boss BossInfo
	 * @param currentKc int
	 * @return List<LeaderboardEntry>
	 */
	private List<BossLeaderboardEntry> getMilestoneKcLeaderboardEntries(BossInfo boss, int prevKc, int currentKc) {
		return leaderboardManager.getMilestoneBossLeaderboardEntries(boss, prevKc, currentKc);
	}

	/**
	 * Adds a leaderboard rank notification to the queue if certain requirements are met.
	 *
	 * @param skill Skill
	 * @param leaderboardEntry LeaderboardEntry
	 */
	private void notifySkillLeaderboard(Skill skill, SkillLeaderboardEntry leaderboardEntry)
	{
		String title = Util.replaceSkillLeaderboardValues(config.notificationLeaderboardRankTitle(), skill, leaderboardEntry);
		String text = Util.replaceSkillLeaderboardValues(config.notificationLeaderboardRankText(), skill, leaderboardEntry);
		int color = Util.getIntValue(JagexColors.DARK_ORANGE_INTERFACE_TEXT);

		log.debug("Pop-up leaderboard milestone reached for {} to rank {} (xp {})",
				skill.getName(),
				QuantityFormatter.formatNumber(leaderboardEntry.rank),
				QuantityFormatter.formatNumber(leaderboardEntry.xp));

		if (config.showSkillPopupNotifications()) {
			notifications.addNotification(title, text, color);
		}

		String singleLineMessage =
				text.replace("<br>", " ").replace("\n", " ").replace("  ", " ");

		if (config.showSkillChatNotifications()) {
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", singleLineMessage, null, true);
		}
	}



	/**
	 * Check if we should notify for the given skill based off of our config settings.
	 *
	 * @param skill Skill
	 * @return boolean
	 */
	private boolean shouldNotifyForSkill(Skill skill)
	{
		return Util.skillEnabledInConfig(config, skill);
	}

	/**
	 * Adds a leaderboard rank notification to the queue if certain requirements are met.
	 *
	 * @param boss BossInfo
	 * @param leaderboardEntry LeaderboardEntry
	 */
	private void notifyBossLeaderboard(BossInfo boss, BossLeaderboardEntry leaderboardEntry)
	{
		String title = Util.replaceBossLeaderboardValues(config.notificationBossLeaderboardRankTitle(), boss, leaderboardEntry);
		String text = Util.replaceBossLeaderboardValues(config.notificationBossLeaderboardRankText(), boss, leaderboardEntry);
		int color = Util.getIntValue(JagexColors.DARK_ORANGE_INTERFACE_TEXT);

		log.debug("Pop-up leaderboard milestone reached for {} to rank {} (kc {})",
				boss.chatCommandsLongName,
				QuantityFormatter.formatNumber(leaderboardEntry.rank),
				QuantityFormatter.formatNumber(leaderboardEntry.score));

		if (config.showBossNotifications()) {
			notifications.addNotification(title, text, color);
		}

		String singleLineMessage =
				text.replace("<br>", " ").replace("\n", " ").replace("  ", " ");

		if (config.showBossChatNotifications()) {
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", singleLineMessage, null, true);
		}
	}

	/**
	 * Check if we should notify for the given skill based off of our config settings.
	 *
	 * @param bossInfo BossInfo
	 * @return boolean
	 */
	private boolean shouldNotifyForBoss(BossInfo bossInfo)
	{
		return true;
	}
}
