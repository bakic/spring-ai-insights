package io.github.springaiinsights;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuildSmokeTest {

    @Test
    void runsOnJava21OrLater() {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(21);
    }
}
