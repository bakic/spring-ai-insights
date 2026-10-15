package io.github.springaiinsights.web.pages;

import java.util.List;

import io.github.springaiinsights.analytics.ToolUsage;

/** Tools, most called first, and the tools offered to the model but never called, sorted. */
public record ToolsPage(List<ToolUsage> tools, List<String> neverCalled) {

    public ToolsPage {
        tools = List.copyOf(tools);
        neverCalled = List.copyOf(neverCalled);
    }
}
