package io.github.springaiinsights.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import io.github.springaiinsights.analytics.InsightsFilter;
import org.junit.jupiter.api.Test;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.ServerRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DashboardQueryTest {

    private static final Instant NOW = Instant.parse("2026-10-02T22:00:00Z");

    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static InsightsFilter parse(Map<String, String> params) {
        MockHttpServletRequest servlet = new MockHttpServletRequest("GET", "/ai-insights/api/overview");
        params.forEach(servlet::addParameter);
        return DashboardQuery.parse(ServerRequest.create(servlet, List.of()), "shop", CLOCK);
    }

    @Test
    void defaults() {
        InsightsFilter filter = parse(Map.of());

        assertThat(filter).isEqualTo(
                new InsightsFilter("shop", NOW.minus(Duration.ofDays(7)), NOW, null, ZoneOffset.UTC));
    }

    @Test
    void explicitValues() {
        InsightsFilter filter = parse(Map.of("app", "help", "from", "2026-09-01T00:00:00Z", "to",
                "2026-09-02T00:00:00Z", "tenant", "acme", "zone", "Europe/Paris"));

        assertThat(filter).isEqualTo(new InsightsFilter("help", Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-02T00:00:00Z"), "acme", ZoneId.of("Europe/Paris")));
    }

    @Test
    void blankValuesAreDefaults() {
        InsightsFilter filter = parse(Map.of("app", " ", "tenant", ""));

        assertThat(filter.application()).isEqualTo("shop");
        assertThat(filter.tenantId()).isNull();
    }

    @Test
    void fromAloneEndsNow() {
        InsightsFilter filter = parse(Map.of("from", "2026-10-01T00:00:00Z"));

        assertThat(filter.from()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(filter.to()).isEqualTo(NOW);
    }

    @Test
    void invalidValues() {
        assertThatIllegalArgumentException().isThrownBy(() -> parse(Map.of("zone", "Mars/Base")))
            .withMessage("invalid zone: Mars/Base");
        assertThatIllegalArgumentException().isThrownBy(() -> parse(Map.of("from", "yesterday")))
            .withMessage("invalid from: yesterday");
        assertThatIllegalArgumentException().isThrownBy(() -> parse(Map.of("to", "2026-10-02")))
            .withMessage("invalid to: 2026-10-02");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> parse(Map.of("from", "2026-10-02T00:00:00Z", "to", "2026-10-02T00:00:00Z")))
            .withMessage("from must be before to");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> parse(Map.of("from", "2025-10-01T00:00:00Z", "to", "2026-10-02T00:00:01Z")))
            .withMessage("range must not exceed 366 days");
    }

    @Test
    void exactly366DaysIsAllowed() {
        InsightsFilter filter = parse(Map.of("from", "2025-10-01T00:00:00Z", "to", "2026-10-02T00:00:00Z"));

        assertThat(Duration.between(filter.from(), filter.to())).isEqualTo(Duration.ofDays(366));
    }
}
