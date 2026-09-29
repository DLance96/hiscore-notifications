# Hiscore Notifications
Gives a leagues style notification to the player when they achieve certain milestones on the OSRS hiscores.

## Credit:
Huge credit to Antimated for their original plugin [Milestone Levels](https://github.com/Antimated/milestone-levels)!
Hiscore Notifications builds off of the great work that was done in Milestone Levels.
For customizable notifications at XP, Level, and Virtual Level intervals, check out that plugin instead of this one.

## Features:
* Get notified when ranking up on the OSRS leaderboards.
* Choose which leaderboard to use (Normal, Ironman, Hardcore Ironman, Ultimate Ironman, etc.).
* Customize how often you should be notified about leaderboard ranks by setting rank intervals.
* Enable or disable notifications for any skills.
* Customize the notification message when gaining hiscore ranks.
* Track up to 10 players by name and get notified when you surpass them in any skill or boss.

## Notes:
This plugin is not enabled on a skill until you reach level 60. The leaderboards at low levels are too densely 
populated. This would result in notification spam, and spammed requests to Jagex's hiscores page, which we are
trying to avoid.

This plugin only starts gathering hiscore data about skills after gaining XP in the respective skill, and only
starts tracking boss KC after your first KC of the session. This is another safeguard that exists to prevent
sending too many requests to the hiscore servers. Because of this, notifications are not available until your
second KC or a little while after you start gaining XP for a skill. 

### Tracked Players
Tracked players are off by default. Turn them on in the Tracked Players config section and enter a
comma-separated list of names. Only the first 10 names are used.

**Please be respectful.** Only track people who know about it and welcome the friendly competition, such as
friends, clanmates, or rivals who've agreed to race you. Don't use this feature to single out, pressure, or harass
anyone. Nobody can opt in or out of being on the hiscores, so it's on you to use this kindly.

* The lookups use the same leaderboard you chose for rank notifications. A player who isn't on that
  leaderboard is skipped.
* Tracked player notifications follow the same skill, boss, pop-up, and game chat settings as rank
  notifications, and use the same Skill Messages and Boss Messages templates. In those templates, `$name`
  (or `$player`) is the player you passed.
* The same requirements apply as for rank notifications: you must be ranked in the skill or boss, and at
  least level 60 for skills.

## Screenshot:
![screenshot showing a rank up notification](screenshot.png)
