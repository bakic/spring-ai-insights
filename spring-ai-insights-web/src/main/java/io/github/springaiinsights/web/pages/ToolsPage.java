package io.github.springaiinsights.web.pages;

import java.util.List;

import io.github.springaiinsights.analytics.ToolUsage;

/** Tools, most called first. */
public record ToolsPage(List<ToolUsage> tools) {

    public ToolsPage {
        tools = List.copyOf(tools);
    }
}
