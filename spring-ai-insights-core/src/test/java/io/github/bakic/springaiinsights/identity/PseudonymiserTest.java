package io.github.bakic.springaiinsights.identity;

import java.util.ArrayList;

import io.github.bakic.springaiinsights.TestTurns;
import io.github.bakic.springaiinsights.model.ToolCallRecord;
import io.github.bakic.springaiinsights.model.ToolOutcome;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class PseudonymiserTest {

    @Test
    void sameInputSameSecretGivesSameId() {
        var p = new Pseudonymiser("s3cret".getBytes(UTF_8));
        assertThat(p.pseudonymise("alice")).isEqualTo(p.pseudonymise("alice"))
            .hasSize(64).matches("[0-9a-f]{64}").isNotEqualTo("alice");
    }

    @Test
    void differentSecretsGiveDifferentIds() {
        assertThat(new Pseudonymiser("a".getBytes(UTF_8)).pseudonymise("alice"))
            .isNotEqualTo(new Pseudonymiser("b".getBytes(UTF_8)).pseudonymise("alice"));
    }

    @Test
    void knownVector() {
        assertThat(new Pseudonymiser("key".getBytes(UTF_8))
            .pseudonymise("The quick brown fox jumps over the lazy dog"))
            .isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }

    @Test
    void randomSecretIsStableWithinInstance() {
        var p = Pseudonymiser.withRandomSecret();
        assertThat(p.pseudonymise("bob")).isEqualTo(p.pseudonymise("bob"));
        assertThat(Pseudonymiser.withRandomSecret().pseudonymise("bob")).isNotEqualTo(p.pseudonymise("bob"));
    }

    @Test
    void turnCopiesCollections() {
        var tools = new ArrayList<ToolCallRecord>();
        var turn = TestTurns.minimal().withToolCalls(tools).build();
        tools.add(new ToolCallRecord("x", 1, ToolOutcome.SUCCESS, null, 0));
        assertThat(turn.toolCalls()).isEmpty();
    }
}
