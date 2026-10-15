package io.github.springaiinsights.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HelpdeskApplication {

    private static final Logger log = LoggerFactory.getLogger(HelpdeskApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(HelpdeskApplication.class, args);
    }

    @EventListener
    void started(WebServerInitializedEvent event) {
        log.warn("Spring AI Insights demo: answers are simulated, no model provider is called. "
                + "Dashboard: http://localhost:{}/ai-insights/ (admin / admin)", event.getWebServer().getPort());
    }
}
