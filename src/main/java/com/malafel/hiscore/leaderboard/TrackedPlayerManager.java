package com.malafel.hiscore.leaderboard;

import com.malafel.hiscore.HiscoreNotificationsConfig;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.client.hiscore.HiscoreClient;
import net.runelite.client.hiscore.HiscoreEndpoint;
import net.runelite.client.hiscore.HiscoreResult;
import net.runelite.client.hiscore.HiscoreSkill;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Fetches hiscore data for a user-specified list of players, so the local player can be notified when they overtake
 * one of them in a skill or boss.
 * <p>
 * Data is fetched once per `reset()`, after the local player's own hiscore lookup completes. Lookups are issued one at a
 * time from `onGameTick`, spaced at least `MIN_TIME_BETWEEN_LOOKUPS` apart, out of respect to Jagex.
 */
@Slf4j
@Singleton
public class TrackedPlayerManager {
    @Inject
    private HiscoreClient hiscoreClient;

    @Inject
    private HiscoreNotificationsConfig config;

    @Inject
    private LeaderboardManager leaderboardManager;

    private static final int MAX_TRACKED_PLAYERS = 10;
    private static final int MAX_REQUEST_RETRIES = 3;
    private static final Duration MIN_TIME_BETWEEN_LOOKUPS = Duration.ofMillis(5000);

    // Names still waiting to be looked up. Null until the lookups have been queued for the current reset.
    private Deque<String> pendingNames = null;

    // The in-flight lookup, and the name it belongs to.
    private Future<HiscoreResult> lookupFuture = null;
    private String lookupName = null;
    private int lookupRetryCount = 0;
    private Instant timeOfLastLookup = null;

    // Fetched hiscore data, keyed by the tracked player's name as entered in config.
    private final Map<String, HiscoreResult> trackedHiscores = new LinkedHashMap<>();

    /**
     * Set TrackedPlayerManager to the state it should be in on initialization. All tracked player data is discarded and
     * will be fetched again.
     */
    public void reset() {
        if (lookupFuture != null) {
            lookupFuture.cancel(true);
        }
        pendingNames = null;
        lookupFuture = null;
        lookupName = null;
        lookupRetryCount = 0;
        trackedHiscores.clear();
    }

    public void process(GameTick event) {
        if (!config.trackedPlayersEnabled() || !leaderboardManager.hasPlayerHiscore()) {
            return;
        }

        if (pendingNames == null) {
            pendingNames = new ArrayDeque<>(parseTrackedPlayerNames(leaderboardManager.getPlayerName()));
        }

        if (lookupFuture != null) {
            if (!lookupFuture.isDone()) {
                return;
            }
            processCompletedLookup();
        }

        if (lookupFuture != null || pendingNames.isEmpty()) {
            return;
        }

        if (timeOfLastLookup != null && timeOfLastLookup.plus(MIN_TIME_BETWEEN_LOOKUPS).isAfter(Instant.now())) {
            return;
        }

        lookupName = pendingNames.poll();
        timeOfLastLookup = Instant.now();
        lookupFuture = hiscoreClient.lookupAsync(lookupName, HiscoreEndpoint.valueOf(config.chosenLeaderboard().name()));
    }

    private void processCompletedLookup() {
        try {
            HiscoreResult result = lookupFuture.get();
            if (result == null) {
                log.debug("Tracked player {} is not on the chosen leaderboard. Skipping.", lookupName);
            } else {
                trackedHiscores.put(lookupName, result);
            }
            lookupRetryCount = 0;
        } catch (ExecutionException e) {
            if (lookupRetryCount < MAX_REQUEST_RETRIES) {
                log.warn("Failed to fetch hiscore data for tracked player {}. Retrying.", lookupName, e);
                lookupRetryCount++;
                pendingNames.addFirst(lookupName);
            } else {
                log.warn("Reached max retries when fetching hiscore data for tracked player {}. Skipping.", lookupName, e);
                lookupRetryCount = 0;
            }
        } catch (InterruptedException e) {
            log.warn("Attempt to fetch hiscore data for tracked player {} was interrupted. Skipping.", lookupName, e);
            lookupRetryCount = 0;
        }
        lookupFuture = null;
        lookupName = null;
    }

    /**
     * Returns an entry for every tracked player whose XP in `skill` the local player just surpassed.
     *
     * @param skill Skill
     * @param previousXp int
     * @param currentXp int
     * @return List<SkillLeaderboardEntry>
     */
    public List<SkillLeaderboardEntry> getPassedSkillEntries(Skill skill, int previousXp, int currentXp) {
        List<SkillLeaderboardEntry> passed = new ArrayList<>();
        if (!config.trackedPlayersEnabled()) {
            return passed;
        }

        for (Map.Entry<String, HiscoreResult> tracked : trackedHiscores.entrySet()) {
            net.runelite.client.hiscore.Skill result = getHiscoreSkill(tracked.getValue(), skill.name());
            if (result == null || result.getRank() < 1) {
                continue;
            }

            long xp = result.getExperience();
            if (previousXp <= xp && currentXp > xp) {
                passed.add(new SkillLeaderboardEntry(tracked.getKey(), result.getRank(), result.getLevel(), (int) xp));
            }
        }
        return passed;
    }

    /**
     * Returns an entry for every tracked player whose KC at `boss` the local player just surpassed.
     *
     * @param boss BossInfo
     * @param previousKc int
     * @param currentKc int
     * @return List<BossLeaderboardEntry>
     */
    public List<BossLeaderboardEntry> getPassedBossEntries(BossInfo boss, int previousKc, int currentKc) {
        List<BossLeaderboardEntry> passed = new ArrayList<>();
        if (!config.trackedPlayersEnabled()) {
            return passed;
        }

        for (Map.Entry<String, HiscoreResult> tracked : trackedHiscores.entrySet()) {
            net.runelite.client.hiscore.Skill result = getHiscoreSkill(tracked.getValue(), boss.hiscoreSkillName);
            if (result == null || result.getRank() < 1) {
                continue;
            }

            int kc = result.getLevel();
            if (previousKc <= kc && currentKc > kc) {
                passed.add(new BossLeaderboardEntry(tracked.getKey(), result.getRank(), kc));
            }
        }
        return passed;
    }

    private static net.runelite.client.hiscore.Skill getHiscoreSkill(HiscoreResult result, String hiscoreSkillName) {
        try {
            return result.getSkill(HiscoreSkill.valueOf(hiscoreSkillName));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Parses the configured comma-separated list of names, dropping blanks, duplicates, and the local player's own name.
     * Only the first `MAX_TRACKED_PLAYERS` names are kept.
     */
    private List<String> parseTrackedPlayerNames(String localPlayerName) {
        String localNormalised = localPlayerName == null ? null : normaliseName(localPlayerName);
        Set<String> seen = new HashSet<>();
        List<String> names = new ArrayList<>();

        for (String raw : config.trackedPlayers().split(",")) {
            String name = raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            String normalised = normaliseName(name);
            if (normalised.equals(localNormalised) || !seen.add(normalised)) {
                continue;
            }
            names.add(name);
        }

        if (names.size() > MAX_TRACKED_PLAYERS) {
            log.warn("Only the first {} tracked players are used. Ignoring: {}",
                    MAX_TRACKED_PLAYERS, names.subList(MAX_TRACKED_PLAYERS, names.size()));
            return new ArrayList<>(names.subList(0, MAX_TRACKED_PLAYERS));
        }
        return names;
    }

    // OSRS treats spaces, underscores and hyphens in names as equivalent, and names are case-insensitive.
    private static String normaliseName(String name) {
        return name.replaceAll("[\\s_\\-\\u00A0]+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
