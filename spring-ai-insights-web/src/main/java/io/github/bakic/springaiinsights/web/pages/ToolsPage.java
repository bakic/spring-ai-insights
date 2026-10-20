package io.github.bakic.springaiinsights.web.pages;

import java.util.List;

import io.github.bakic.springaiinsights.analytics.ToolUsage;

/**
 * Tools, most called first, and the tools offered to the model but never called, sorted.
 *
 * @param offeredRecorded whether any offered tool was recorded in the period; if not, {@code neverCalled} is empty
 * because nothing is known, not because every tool was called
 */
public record ToolsPage(List<ToolUsage> tools, List<String> neverCalled, boolean offeredRecorded) {

    public ToolsPage {
        tools = List.copyOf(tools);
        neverCalled = List.copyOf(neverCalled);
    }
}
