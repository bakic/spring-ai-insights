package io.github.springaiinsights.web;

import java.security.Principal;
import java.util.Locale;
import java.util.Optional;

import io.github.springaiinsights.feedback.InsightsFeedback;
import io.github.springaiinsights.feedback.Rating;
import io.github.springaiinsights.metrics.InsightsMetrics;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * {@code POST {path}/feedback}: the authenticated user rates one of their own turns. The user is the request
 * principal's name, which must be the same raw ID the identity resolver recorded.
 */
public final class FeedbackRoutes {

    static final int MAX_TURN_ID_LENGTH = 200;

    private static final Log log = LogFactory.getLog(FeedbackRoutes.class);

    private FeedbackRoutes() {
    }

    public static RouterFunction<ServerResponse> create(String path, InsightsFeedback feedback,
            InsightsMetrics metrics) {
        return RouterFunctions.route().POST(path + "/feedback", request -> handle(request, feedback, metrics)).build();
    }

    private static ServerResponse handle(ServerRequest request, InsightsFeedback feedback, InsightsMetrics metrics) {
        Optional<String> user = request.principal().map(Principal::getName);
        if (user.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "authentication required");
        }
        FeedbackRequest body;
        try {
            body = request.body(FeedbackRequest.class);
        }
        catch (Exception e) {
            return error(HttpStatus.BAD_REQUEST, "malformed request body");
        }
        if (body == null || body.turnId() == null || body.turnId().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "turnId is required");
        }
        if (body.turnId().length() > MAX_TURN_ID_LENGTH) {
            return error(HttpStatus.BAD_REQUEST, "turnId is too long");
        }
        Rating rating = rating(body.rating());
        if (rating == null) {
            return error(HttpStatus.BAD_REQUEST, "rating must be up or down");
        }
        String comment = body.comment() == null ? null : body.comment().strip();
        if (comment != null && comment.length() > InsightsFeedback.MAX_COMMENT_LENGTH) {
            return error(HttpStatus.BAD_REQUEST,
                    "comment must not exceed " + InsightsFeedback.MAX_COMMENT_LENGTH + " characters");
        }
        boolean saved;
        try {
            saved = feedback.submit(body.turnId(), user.get(), rating, comment);
        }
        catch (RuntimeException e) {
            metrics.storeError();
            log.warn("ai-insights: feedback could not be saved: " + e);
            return error(HttpStatus.SERVICE_UNAVAILABLE, "feedback could not be saved");
        }
        return saved ? ServerResponse.noContent().build() : error(HttpStatus.NOT_FOUND, "turn not found");
    }

    private static @Nullable Rating rating(@Nullable String value) {
        if (value == null) {
            return null;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "up" -> Rating.UP;
            case "down" -> Rating.DOWN;
            default -> null;
        };
    }

    static ServerResponse error(HttpStatus status, String message) {
        return ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON).body(new ErrorBody(message));
    }

    record FeedbackRequest(@Nullable String turnId, @Nullable String rating, @Nullable String comment) {
    }
}
