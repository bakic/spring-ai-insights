package io.github.springaiinsights.web.pages;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import io.github.springaiinsights.analytics.TimeBucket;
import io.github.springaiinsights.analytics.UserUsage;

/**
 * Who uses the assistant. Daily, weekly and monthly active users count the day, 7 and 30 days before the end of the
 * range; {@code activeUsers} and {@code newUsers} count the whole range; {@code turnsByHourOfDay} has 24 entries in
 * the query zone.
 */
public record UsagePage(long dailyActiveUsers, long weeklyActiveUsers, long monthlyActiveUsers, long activeUsers,
        long newUsers,
        double turnsPerUser, double turnsPerConversation, List<TimeBucket> days, long[] turnsByHourOfDay,
        List<UserUsage> topUsers) {

    public UsagePage {
        days = List.copyOf(days);
        turnsByHourOfDay = turnsByHourOfDay.clone();
        topUsers = List.copyOf(topUsers);
    }

    @Override
    public long[] turnsByHourOfDay() {
        return turnsByHourOfDay.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof UsagePage page && dailyActiveUsers == page.dailyActiveUsers
                && weeklyActiveUsers == page.weeklyActiveUsers && monthlyActiveUsers == page.monthlyActiveUsers
                && activeUsers == page.activeUsers && newUsers == page.newUsers
                && Double.compare(turnsPerUser, page.turnsPerUser) == 0
                && Double.compare(turnsPerConversation, page.turnsPerConversation) == 0 && days.equals(page.days)
                && Arrays.equals(turnsByHourOfDay, page.turnsByHourOfDay) && topUsers.equals(page.topUsers);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(dailyActiveUsers, weeklyActiveUsers, monthlyActiveUsers, activeUsers, newUsers,
                turnsPerUser, turnsPerConversation, days, topUsers) + Arrays.hashCode(turnsByHourOfDay);
    }

    @Override
    public String toString() {
        return "UsagePage[dailyActiveUsers=" + dailyActiveUsers + ", weeklyActiveUsers=" + weeklyActiveUsers
                + ", monthlyActiveUsers=" + monthlyActiveUsers + ", activeUsers=" + activeUsers + ", newUsers="
                + newUsers + ", turnsByHourOfDay=" + Arrays.toString(turnsByHourOfDay) + "]";
    }
}
