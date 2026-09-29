package com.malafel.hiscore.leaderboard;

import com.malafel.hiscore.HiscoreNotificationsConfig;
import com.malafel.hiscore.util.Util;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.client.hiscore.HiscoreClient;
import net.runelite.client.hiscore.HiscoreEndpoint;
import net.runelite.client.hiscore.HiscoreResult;
import net.runelite.client.hiscore.HiscoreSkill;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * Issues requests and processes results from the OSRS hiscores website for each tracked skill to maintain lists of XP
 * milestones. Also controls rate limiting and retries, to limit strain on the hiscores page.
 * <p>
 * The objective is to ensure that these lists keep up with the player gaining XP. To accomplish this, an arbitrary
 * minimum list length is chosen `MINIMUM_LEADERBOARD_LIST_LENGTH`. If there are fewer milestones than the minimum list
 * size for a given skill, the LeaderboardManager will issue a request for more leaderboard data. At most one active
 * request may exist at a time for each skill.
 * <p>
 * All logic is performed on the main game thread as part of `onGameTick`, while requests are performed asynchronously.
 * Because of the asynchronous nature, LeaderboardManager operates on a state machine to ensure correct sequencing of
 * operations.
 * <p>
 * NOTE: the term `hiscore` already exists in RuneLite to indicate a player's specific hiscore page results. This class,
 * and related classes continue to use the term `hiscore` in that way, though references may be made to the
 * `hiscore servers`, which vaguely mean the black box of Jagex servers that provide all similar data. This project uses
 * the term `leaderboard` to refer to the array of entries containing [ranks, player names, levels, xp values],
 * ordered by rank for a single skill.
 */
@Slf4j
@Singleton
public class LeaderboardManager {
    @Inject
    private Client client;

    @Inject
    private HiscoreClient hiscoreClient;

    @Inject
    private LeaderboardClient leaderboardClient;

    @Inject
    private HiscoreNotificationsConfig config;

    @Inject
    private BossInfoRegistry bossInfoRegistry;

    private static final int MIN_LEADERBOARD_SIZE = 2;
    private static final int MAX_REQUEST_RETRIES = 3;
    // The minimum level required in a skill before leaderboard tracking begins. The lower the player's level, the more
    // densely packed the leaderboards should be. A densely packed leaderboard defeats the purpose of these sorts of
    // milestones, and results in lots of outgoing requests to the hiscore servers. This acts as a first line of defense
    // for rate limiting, out of respect to Jagex.
    private static final int MIN_REQUIRED_LEVEL_FOR_TRACKING = 60;

    // State machine indicating the 3 main stages of operation, and an error state that ceases operation.
    private LeaderboardManagerState state = LeaderboardManagerState.AWAITING_BOSS_INFO;

    // Future that is completed after the player's hiscore data is fetched from the hiscore server.
    private Future<HiscoreResult> hiscoreFuture = null;

    // Holds a complete, valid version of the player's hiscore data after it has been fetched from the hiscore server.
    private HiscoreResult playerHiscore = null;

    // Tracks retries for fetching player hiscore data.
    private int hiscoreRetryCount = 0;

    private final Map<Skill, LeaderboardSkillState> skillStates = new EnumMap<>(Skill.class);
    private final HashMap<String, LeaderboardBossState> bossStates = new HashMap<>();

    private boolean wasEnabled = false;

    LeaderboardManager() {
        reset();
    }

    public void process(GameTick event) {
        switch (state) {
            case AWAITING_BOSS_INFO:
                processAwaitingBossInfo();
                break;
            case AWAITING_PLAYER_NAME:
                processAwaitingPlayerName();
                break;
            case AWAITING_PLAYER_HISCORE:
                processAwaitingPlayerHiscore();
                break;
            case ACTIVE:
                processActive();
                break;
            case UNRECOVERABLE_ERROR:
                // cease operation.
                break;
        }
    }

    /**
     * Returns all `LeaderboardEntry` values for a skill whose xp value lies between previousXP and currentXP exclusive.
     *
     * @param skill Skill
     * @param previousXp int
     * @param currentXp int
     * @return List<LeaderboardEntry>
     */
    public List<SkillLeaderboardEntry> getMilestoneSkillLeaderboardEntries(Skill skill, int previousXp, int currentXp) {
        LeaderboardSkillState skillState = skillStates.get(skill);
        return skillState.validLeaderboardEntries.stream()
                                                 .filter(entry -> entry.xp > previousXp && entry.xp < currentXp)
                                                 .distinct()
                                                 .collect(Collectors.toList());
    }

    /**
     * Returns all `LeaderboardEntry` values for a skill whose xp value lies between previousXP and currentXP exclusive.
     *
     * @param boss BossInfo
     * @param currentKc int
     * @return List<LeaderboardEntry>
     */
    public List<BossLeaderboardEntry> getMilestoneBossLeaderboardEntries(BossInfo boss, int prevKc, int currentKc) {
        LeaderboardBossState bossState = bossStates.get(boss.hiscoreSkillName);
        return bossState.validLeaderboardEntries.stream()
                .filter(entry -> entry.score >= prevKc && entry.score < currentKc)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Whether the local player's own hiscore data has been fetched and active processing has begun.
     */
    public boolean hasPlayerHiscore() {
        return state == LeaderboardManagerState.ACTIVE && playerHiscore != null;
    }

    /**
     * The local player's name, as used for their own hiscore lookup. Null until `hasPlayerHiscore()` is true.
     */
    public String getPlayerName() {
        return playerHiscore == null ? null : playerHiscore.getPlayer();
    }

    /**
     * Whether rank notifications can occur for a skill: the local player is ranked in it, and meets
     * `MIN_REQUIRED_LEVEL_FOR_TRACKING`.
     */
    public boolean isSkillEligibleForNotifications(Skill skill) {
        if (!hasPlayerHiscore()) {
            return false;
        }
        try {
            var skillResult = playerHiscore.getSkill(HiscoreSkill.valueOf(skill.name()));
            return skillResult != null && skillResult.getRank() > 0 && skillResult.getLevel() >= MIN_REQUIRED_LEVEL_FOR_TRACKING;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Whether rank notifications can occur for a boss: the local player is ranked at it.
     */
    public boolean isBossEligibleForNotifications(BossInfo boss) {
        if (!hasPlayerHiscore() || !boss.isValid()) {
            return false;
        }
        try {
            var bossResult = playerHiscore.getSkill(HiscoreSkill.valueOf(boss.hiscoreSkillName));
            return bossResult != null && bossResult.getRank() > 0;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Set LeaderboardManager to the state it should be in on initialization.
     */
    public void reset() {
        if (leaderboardClient != null) {
            leaderboardClient.reset();
        }
        state = LeaderboardManagerState.AWAITING_BOSS_INFO;
        hiscoreFuture = null;
        playerHiscore = null;
        hiscoreRetryCount = 0;
        for (Skill s: Skill.values()) {
            skillStates.put(s, new LeaderboardSkillState());
        }
        // Cannot populate bossStates until we know the full list of bosses is available. Resetting the state to
        // AWAITING_BOSS_INFO, as done above makes sure the bossStates map is properly reset.
        bossStates.clear();
    }

    private void processAwaitingBossInfo() {
        if (bossInfoRegistry.isReady()) {
            for (BossInfo b: bossInfoRegistry.getAllBosses()) {
                bossStates.put(b.hiscoreSkillName, new LeaderboardBossState());
            }
            state = LeaderboardManagerState.AWAITING_PLAYER_NAME;
        }
    }

    private void processAwaitingPlayerName() {
        if (client.getGameState() == GameState.LOGGED_IN && client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null) {
            if (hiscoreFuture != null) {
                log.error("Already had a future for player hiscore data when entering the AWAITING_PLAYER_HISCORE state.");
                state = LeaderboardManagerState.UNRECOVERABLE_ERROR;
                return;
            }
            hiscoreFuture = hiscoreClient.lookupAsync(client.getLocalPlayer().getName(), HiscoreEndpoint.valueOf(config.chosenLeaderboard().name()));
            state = LeaderboardManagerState.AWAITING_PLAYER_HISCORE;
        }
    }

    public void enableSkillTracking(Skill skill) {
        var skillState = skillStates.get(skill);
        skillState.isActive = true;
    }

    public void enableBossTracking(BossInfo boss) {
        var bossState = bossStates.get(boss.hiscoreSkillName);
        bossState.isActive = true;
    }

    public void updateBossKc(BossInfo boss, int kc) {
        var bossState = bossStates.get(boss.hiscoreSkillName);
        bossState.currentKc = kc;
    }

    private void processAwaitingPlayerHiscore() {
        if (hiscoreFuture == null) {
            // This is effectively an assert. Reaching this point indicates a programming error.
            log.error("Missing future when waiting for player hiscore data.");
            state = LeaderboardManagerState.UNRECOVERABLE_ERROR;
            return;
        }

        if (hiscoreFuture.isDone()) {
            try {
                playerHiscore = hiscoreFuture.get();
                ingestPlayerHiscoreData();
                state = LeaderboardManagerState.ACTIVE;
                hiscoreFuture = null;
            } catch (ExecutionException e) {
                log.warn("Encountered an exception when trying to fetch player specific hiscore data.", e);
                if (hiscoreRetryCount < MAX_REQUEST_RETRIES) {
                    hiscoreFuture = hiscoreClient.lookupAsync(client.getLocalPlayer().getName(), HiscoreEndpoint.valueOf(config.chosenLeaderboard().name()));
                    hiscoreRetryCount++;
                } else {
                    log.warn("Reached max retries when fetching player specific hiscore data. Stopping.");
                    state = LeaderboardManagerState.UNRECOVERABLE_ERROR;
                }
            } catch (InterruptedException e) {
                log.warn("Attempt to fetch player specific hiscore data was interrupted. Stopping.", e);
                state = LeaderboardManagerState.UNRECOVERABLE_ERROR;
            }
        }
    }

    /**
     * Updates all LeaderboardSkillStates based on information contained within the player's hiscore data. This must be
     * called after that data becomes available and before active processing begins.
     */
    private void ingestPlayerHiscoreData() {
        for (Skill s: Skill.values()) {
            try {
                var skillResult = playerHiscore.getSkill(HiscoreSkill.valueOf(s.name()));
                skillStates.get(s).nextRankToMeasure = skillResult.getRank() - 1;
            } catch (Exception e) {
                log.warn("Missing hiscore data for {} skill. Either level is too low for a rank, or the wrong leaderboard is being used. Check Hiscore Notifications plugin config.", s.name(), e);
                skillStates.get(s).isDisabledFromError = true;
            }
        }

        for (BossInfo b: bossInfoRegistry.getAllBosses()) {
            if (!b.isValid()) {
                continue;
            }

            try {
                var bossResult = playerHiscore.getSkill(HiscoreSkill.valueOf(b.hiscoreSkillName));
                bossStates.get(b.hiscoreSkillName).nextRankToMeasure = bossResult.getRank() - 1;
                bossStates.get(b.hiscoreSkillName).currentKc = bossResult.getLevel();
            } catch (Exception e) {
                log.warn("Missing hiscore data for {}. Either KC is too low for a rank, or the wrong leaderboard is being used. Check Hiscore Notifications plugin config.", b.hiscoreSkillName, e);
                bossStates.get(b.hiscoreSkillName).isDisabledFromError = true;
            }
        }
    }

    /**
     * Steady state processing that happens every game tick. Loop over all skills, and ensure that for all the tracked
     * skills, the list of XP milestones is growing until it reaches the adequate length.
     */
    private void processActive() {
        for (Skill s: Skill.values()) {
            if (Util.skillEnabledInConfig(config, s)) {
                processSkill(s);
            }
        }
        if (config.showBossNotifications() || config.showBossChatNotifications()) {
            for (BossInfo b: bossInfoRegistry.getAllBosses()) {
                if (!b.isValid()) {
                    continue;
                }
                processBoss(b);
            }
        }
    }

    private void processSkill(Skill skill) {
        LeaderboardSkillState skillState = skillStates.get(skill);
        if (skillState.isDisabledFromError ||
            skillState.nextRankToMeasure < 1 ||
            !skillState.isActive ||
            playerHiscore.getSkill(HiscoreSkill.valueOf(skill.name())).getLevel() < MIN_REQUIRED_LEVEL_FOR_TRACKING) {
            return;
        }

        // There might already be an outgoing request for more leaderboard data. Nothing can be done until this is
        // future is completed.
        if (skillState.leaderboardFuture != null) {
            if (!skillState.leaderboardFuture.isDone()) {
                return;
            }

            try {
                // Success case: we just got a new leaderboard page. Now add that data to `skillState`.
                SkillLeaderboardResult leaderboardResult = skillState.leaderboardFuture.get();
                skillState.leaderboardFuture = null;
                skillState.currentPageRetryCount = 0;

                // The results are ordered high XP to low XP since reading begins at the top of the page. Even though
                // the hiscores change over time, all results from this page will be a greater than or equal to the
                // highest XP value from the previous page.
                //
                // It's possible that several people have the same XP for the current skill. This is especially probable
                // at low
                //
                // There might be some duplicate names on this list that increased their rank since last time we
                // checked. It's hard to say how this ought to be handled. The leaderboard is constantly changing and
                // this plugin is using an approximation of the current leaderboar state. For now, duplicates will
                // be allowed.
                ArrayList<SkillLeaderboardEntry> resultEntries = new ArrayList<>(leaderboardResult.getEntries());
                Collections.reverse(resultEntries);
                List<SkillLeaderboardEntry> filteredEntries = resultEntries.stream().filter(entry -> shouldConsiderLeaderboardEntry(entry.rank)).collect(Collectors.toList());

                skillState.validLeaderboardEntries.addAll(filteredEntries);

                // De-dupe XP values, only keeping the best (lowest numerical) rank.
                // TODO: Maybe revisit. This is O(n^2) and it doesn't need to be. Shouldn't matter for small lists.
                for (int i = 0; i < skillState.validLeaderboardEntries.size()-1; i++) {
                    if (skillState.validLeaderboardEntries.get(i).xp == skillState.validLeaderboardEntries.get(i+1).xp) {
                        skillState.validLeaderboardEntries.remove(i);
                        i--;
                    }
                }

                // Rank is in decreasing order, meaning the final element is the lowest rank numerically.
                skillState.nextRankToMeasure =
                        nextRankToConsider(resultEntries.get(resultEntries.size()-1).rank);

                // There can be lots of people with 200m experience. Short circuit this and skip straight to rank 1 to
                // prevent tons of pointless queries.
                if (skillState.nextRankToMeasure > 1 &&
                        resultEntries.get(resultEntries.size()-1).xp == 200_000_000) {
                    skillState.nextRankToMeasure = 1;
                }
            } catch (ExecutionException e) {
                // Error handling has lots of failure cases. We only want to retry if there was some sort of network
                // issue, which would manifest as an IOException wrapped with an ExecutionException from the future.
                Throwable cause = e.getCause();
                if (cause instanceof ParseException && skillState.currentPageRetryCount < MAX_REQUEST_RETRIES) {
                    // This is sometimes caused by server-side rate limiting.
                    log.warn("Failed to parse fetched hiscore data for skill: {}.", skill, cause);
                    skillState.currentPageRetryCount++;
                } else if (cause instanceof IOException && skillState.currentPageRetryCount < MAX_REQUEST_RETRIES) {
                    log.warn("Failed to fetch hiscore data for skill: {} due to possible network issue. Retrying.", skill, cause);
                        skillState.currentPageRetryCount++;
                        skillState.leaderboardFuture = null;
                        requestMoreLeaderboardDataForSkill(skill);
                } else if (cause instanceof IOException) {
                    log.warn("Failed to fetch hiscore data for skill: {} due to possible network issue. Reached max retries.", skill, cause);
                        skillState.isDisabledFromError = true;
                } else if (skillState.currentPageRetryCount >= MAX_REQUEST_RETRIES) {
                    log.warn("Failed to fetch hiscore data for skill: {}. Reached maximum retries. Disabling future lookups for that boss.", skill, cause);
                    skillState.isDisabledFromError = true;
                }else {
                    log.warn("Failed to fetch hiscore data for skill: {}. Cause was unexpected. Disabling future lookups for that skill.", skill, cause);
                    skillState.isDisabledFromError = true;
                }

            } catch (InterruptedException e) {
                log.warn("Attempt to fetch data for skill: {} was interrupted. Disabling future lookups for that skill.", skill, e);
                skillState.isDisabledFromError = true;
            }

        }
        if (skillState.leaderboardFuture != null) {
            return;
        }
        // This point should now only be reached if there is no leaderboardFuture (it's possible that there was one when
        // this function was initially called).

        // Trim the list of leaderboard entries to remove all XP milestones lower than the player's current XP value for
        // this skill.
        skillState.validLeaderboardEntries =
                skillState.validLeaderboardEntries.stream()
                                                  .filter(entry -> entry.xp > client.getSkillExperience(skill))
                                                  .distinct()
                                                  .collect(Collectors.toList());

        if (skillState.validLeaderboardEntries.size() < MIN_LEADERBOARD_SIZE) {
            requestMoreLeaderboardDataForSkill(skill);
        }
    }

    private void processBoss(BossInfo boss) {
        LeaderboardBossState bossState = bossStates.get(boss.hiscoreSkillName);
        if (bossState.isDisabledFromError ||
            !bossState.isActive ||
            bossState.nextRankToMeasure < 1) {
            return;
        }

        // There might already be an outgoing request for more leaderboard data. Nothing can be done until this is
        // future is completed.
        if (bossState.leaderboardFuture != null) {
            if (!bossState.leaderboardFuture.isDone()) {
                return;
            }

            try {
                // Success case: we just got a new leaderboard page. Now add that data to `bossState`.
                BossLeaderboardResult leaderboardResult = bossState.leaderboardFuture.get();
                bossState.leaderboardFuture = null;
                bossState.currentPageRetryCount = 0;

                // Follows similar logic to `processSkill`.
                ArrayList<BossLeaderboardEntry> resultEntries = new ArrayList<>(leaderboardResult.getEntries());
                Collections.reverse(resultEntries);
                List<BossLeaderboardEntry> filteredEntries = resultEntries.stream().filter(entry ->
                        shouldConsiderLeaderboardEntry(entry.rank)).collect(Collectors.toList());

                bossState.validLeaderboardEntries.addAll(filteredEntries);

                // De-dupe XP values, only keeping the best (lowest numerical) rank.
                // TODO: Maybe revisit. This is O(n^2) and it doesn't need to be. Shouldn't matter for small lists.
                for (int i = 0; i < bossState.validLeaderboardEntries.size()-1; i++) {
                    if (bossState.validLeaderboardEntries.get(i).score == bossState.validLeaderboardEntries.get(i+1).score) {
                        bossState.validLeaderboardEntries.remove(i);
                        i--;
                    }
                }

                // Rank is in decreasing order, meaning the final element is the lowest rank numerically.
                bossState.nextRankToMeasure =
                        nextRankToConsider(resultEntries.get(resultEntries.size()-1).rank);
            } catch (ExecutionException e) {
                // Error handling has lots of failure cases. We only want to retry if there was some sort of network
                // issue, which would manifest as an IOException wrapped with an ExecutionException from the future.
                Throwable cause = e.getCause();
                if (cause instanceof ParseException && bossState.currentPageRetryCount < MAX_REQUEST_RETRIES) {
                    log.warn("Failed to parse fetched hiscore data for boss: {}. Disabling future lookups for that boss.", boss, cause);
                    bossState.currentPageRetryCount++;
                } else if (cause instanceof IOException && bossState.currentPageRetryCount < MAX_REQUEST_RETRIES) {
                    log.warn("Failed to fetch hiscore data for boss: {} due to possible network issue. Retrying.", boss, cause);
                    bossState.currentPageRetryCount++;
                    bossState.leaderboardFuture = null;
                    requestMoreLeaderboardDataForBoss(boss);
                } else if (cause instanceof IOException) {
                    log.warn("Failed to fetch hiscore data for boss: {} due to possible network issue. Reached max retries.", boss, cause);
                    bossState.isDisabledFromError = true;
                } else if (bossState.currentPageRetryCount >= MAX_REQUEST_RETRIES) {
                    log.warn("Failed to fetch hiscore data for boss: {}. Reached maximum retries. Disabling future lookups for that boss.", boss, cause);
                    bossState.isDisabledFromError = true;
                } else {
                    log.warn("Failed to fetch hiscore data for boss: {}. Cause was unexpected. Disabling future lookups for that boss.", boss, cause);
                    bossState.isDisabledFromError = true;
                }

            } catch (InterruptedException e) {
                log.warn("Attempt to fetch data for boss: {} was interrupted. Disabling future lookups for that boss.", boss, e);
                bossState.isDisabledFromError = true;
            }

        }
        if (bossState.leaderboardFuture != null) {
            return;
        }
        // This point should now only be reached if there is no leaderboardFuture (it's possible that there was one when
        // this function was initially called).

        // Trim the list of leaderboard entries to remove all KC milestones lower than the player's current XP value for
        // this skill (include the current kc so we have a data point about who we just passed.).
        bossState.validLeaderboardEntries =
                bossState.validLeaderboardEntries.stream()
                        .filter(entry -> entry.score >= bossState.currentKc)
                        .distinct()
                        .collect(Collectors.toList());

        if (bossState.validLeaderboardEntries.size() < MIN_LEADERBOARD_SIZE) {
            requestMoreLeaderboardDataForBoss(boss);
        }
    }

    /**
     * Helper function that initiates a request for the next leaderboard page for a skill.
     */
    private void requestMoreLeaderboardDataForSkill(Skill skill) {
        LeaderboardSkillState skillState = skillStates.get(skill);
        if (skillState.leaderboardFuture != null) {
            log.warn("Attempted to fetch more leaderboard data for skill: {} while a request was already pending. Disabling future lookups.", skill);
            skillState.isDisabledFromError = true;
            return;
        }

        if (skillState.nextRankToMeasure <= 0) {
            return;
        }

        // For example: page 2 contains ranks 26 to 50 inclusive.
        //   ((25-1) / 25) + 1 == 1
        //   ((26-1) / 25) + 1 == 2
        //   ((50-1) / 25) + 1 == 2
        //   ((51-1) / 25) + 1 == 3
        int pageToRequest = ((skillState.nextRankToMeasure - 1) / 25) + 1;

        skillState.leaderboardFuture = leaderboardClient.lookupSkillAsync(
                skill, pageToRequest, LeaderboardEndpoint.valueOf(config.chosenLeaderboard().name()));
    }

    private void requestMoreLeaderboardDataForBoss(BossInfo boss) {
        if (!boss.isValid()) {
            return;
        }

        LeaderboardBossState bossState = bossStates.get(boss.hiscoreSkillName);
        if (bossState.leaderboardFuture != null) {
            log.warn("Attempted to fetch more leaderboard data for boss: {} while a request was already pending. Disabling future lookups.", boss);
            bossState.isDisabledFromError = true;
            return;
        }

        if (bossState.nextRankToMeasure <= 0) {
            return;
        }

        // For example: page 2 contains ranks 26 to 50 inclusive.
        //   ((25-1) / 25) + 1 == 1
        //   ((26-1) / 25) + 1 == 2
        //   ((50-1) / 25) + 1 == 2
        //   ((51-1) / 25) + 1 == 3
        int pageToRequest = ((bossState.nextRankToMeasure - 1) / 25) + 1;

        bossState.leaderboardFuture = leaderboardClient.lookupBossAsync(
                boss, pageToRequest, LeaderboardEndpoint.valueOf(config.chosenLeaderboard().name()));
    }

    /**
     * Determines whether a leaderboard entry should be considered a milestone based on the configured intervals.
     *
     * @param rank int
     * @return int
     */
    private boolean shouldConsiderLeaderboardEntry(int rank) {
        Util.IntervalConfig ic = new Util.IntervalConfig();
        ic.tensInterval = config.tensInterval();
        ic.hundredsInterval = config.hundredsInterval();
        ic.thousandsInterval = config.thousandsInterval();
        ic.tenThousandsInterval = config.tenThousandsInterval();
        ic.hundredThousandsInterval = config.hundredThousandsInterval();
        ic = Util.cleanupIntervalData(ic);

        if (rank > ic.firstRankToConsider) {
            return false;
        }

        int interval;
        if (rank > 99999) {
            interval = ic.hundredThousandsInterval;
        } else if (rank > 9999) {
            interval = ic.tenThousandsInterval;
        } else if (rank > 999) {
            interval = ic.thousandsInterval;
        } else if (rank > 99){
            interval = ic.hundredsInterval;
        } else {
            interval = ic.tensInterval;
        }
        if (interval < 1) {
            interval = 1;
        }
        return rank % interval == 0;
    }

    /**
     * Given that the leaderboard entry at `rank` was just checked, returns which leaderboard entry should be considered
     * next, based on configured notification rank intervals.
     *
     * @param rank int
     * @return int
     */
    public int nextRankToConsider(int rank) {
        Util.IntervalConfig ic = new Util.IntervalConfig();
        ic.tensInterval = config.tensInterval();
        ic.hundredsInterval = config.hundredsInterval();
        ic.thousandsInterval = config.thousandsInterval();
        ic.tenThousandsInterval = config.tenThousandsInterval();
        ic.hundredThousandsInterval = config.hundredThousandsInterval();
        ic = Util.cleanupIntervalData(ic);

        if (rank > ic.firstRankToConsider) {
            return ic.firstRankToConsider;
        }

        if (rank > 99999) {
            int nextRank = Util.nextRankInInterval(rank, ic.hundredThousandsInterval);
            if (nextRank <= 99999) {
                return Util.nextRankInInterval(rank, ic.tenThousandsInterval);
            }
            return nextRank;
        } else if (rank > 9999) {
            int nextRank = Util.nextRankInInterval(rank, ic.tenThousandsInterval);
            if (nextRank <= 9999) {
                return Util.nextRankInInterval(rank, ic.thousandsInterval);
            }
            return nextRank;
        } else if (rank > 999) {
            int nextRank = Util.nextRankInInterval(rank, ic.thousandsInterval);
            if (nextRank <= 999) {
                return Util.nextRankInInterval(rank, ic.hundredsInterval);
            }
            return nextRank;
        } else if (rank > 99) {
            int nextRank = Util.nextRankInInterval(rank, ic.hundredsInterval);
            if (nextRank <= 99) {
                return Util.nextRankInInterval(rank, ic.tensInterval);
            }
            return nextRank;
        }

        return Util.nextRankInInterval(rank, ic.tensInterval);
    }

}
