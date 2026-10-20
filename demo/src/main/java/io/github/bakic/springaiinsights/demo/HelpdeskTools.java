package io.github.bakic.springaiinsights.demo;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/** The helpdesk's tools. Order numbers outside the five known ones fail, as a real lookup would. */
@Component
class HelpdeskTools {

    static final Map<String, String> ORDERS = Map.of(
            "10388", "Order 10388: delivered on May 3.",
            "10423", "Order 10423: shipped on May 6, arriving May 9.",
            "10477", "Order 10477: being packed, ships tomorrow.",
            "10512", "Order 10512: delivered to a parcel locker on May 4.",
            "10560", "Order 10560: cancelled and refunded on May 1.");

    @Tool(description = "Looks up the status of a customer order by its order number")
    String lookupOrder(String orderNumber) {
        String status = ORDERS.get(orderNumber.replaceAll("\\D", ""));
        if (status == null) {
            throw new IllegalArgumentException("Unknown order " + orderNumber);
        }
        return status;
    }

    @Tool(description = "Returns the current status of a service: checkout, login, payments, search or website")
    String serviceStatus(String service) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(50, 401));
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String name = service.toLowerCase(Locale.ROOT);
        return name.equals("payments") ? "payments: degraded since 10:40, a fix is rolling out."
                : name + ": operational, no incident in the last 24 hours.";
    }

    @Tool(description = "Opens a support ticket for a human agent and returns its reference")
    String openTicket(String summary) {
        return "Ticket TCK-" + ThreadLocalRandom.current().nextInt(1000, 10000)
                + " opened; an agent will reply by email within one business day.";
    }
}
