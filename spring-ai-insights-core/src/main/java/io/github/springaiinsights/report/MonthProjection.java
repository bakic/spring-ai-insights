package io.github.springaiinsights.report;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * The cost of a month so far, and that cost extended to the whole month at the same daily rate.
 *
 * @param daysElapsed days from the first of the month to the report's last day, included
 */
public record MonthProjection(YearMonth month, BigDecimal costToDate, int daysElapsed, BigDecimal projectedCost) {
}
