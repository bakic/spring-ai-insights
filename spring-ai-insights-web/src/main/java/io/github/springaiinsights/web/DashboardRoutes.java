package io.github.springaiinsights.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.function.Function;

import io.github.springaiinsights.analytics.InsightsFilter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * The dashboard JSON API under {@code {path}/api}. Access needs the role (checked with
 * {@code isUserInRole}, so it uses the application's own Spring Security setup) unless anonymous access is allowed.
 */
public final class DashboardRoutes {

    static final String CONTENT_SECURITY_POLICY = "default-src 'none'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    static final int MAX_ID_LENGTH = 200;

    /** Outside {@code static/} and {@code META-INF/resources/}: only these routes, behind the access check, serve it. */
    private static final Resource ASSETS = new ClassPathResource("ai-insights-dashboard/");

    private static final Resource INDEX = new ClassPathResource("ai-insights-dashboard/index.html");

    private DashboardRoutes() {
    }

    public static RouterFunction<ServerResponse> create(String path, String role, boolean allowAnonymous,
            DashboardPages pages, Clock clock) {
        String api = path + "/api";
        RouterFunction<ServerResponse> apiRoutes = RouterFunctions.route()
            .GET(api + "/config", request -> json(pages.config()))
            .GET(api + "/overview", request -> page(request, pages, clock, pages::overview))
            .GET(api + "/usage", request -> page(request, pages, clock, pages::usage))
            .GET(api + "/cost", request -> page(request, pages, clock, pages::cost))
            .GET(api + "/knowledge", request -> page(request, pages, clock, pages::knowledge))
            .GET(api + "/tools", request -> page(request, pages, clock, pages::tools))
            .GET(api + "/quality", request -> page(request, pages, clock, pages::quality))
            .GET(api + "/conversation", request -> conversation(request, pages))
            .filter(headers(true))
            .filter(access(role, allowAnonymous))
            .build();
        String lastSegment = path.substring(path.lastIndexOf('/') + 1);
        RouterFunction<ServerResponse> uiRoutes = RouterFunctions.route()
            .GET(path, request -> ServerResponse.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, lastSegment + "/")
                .build())
            .GET(path + "/", request -> ServerResponse.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(INDEX))
            .resources(path + "/assets/**", ASSETS)
            .filter(headers(false))
            .filter(access(role, allowAnonymous))
            .build();
        return apiRoutes.and(uiRoutes);
    }

    /** Lets the request through only for allowed users: 401 without a principal, 403 without the role. */
    static HandlerFilterFunction<ServerResponse, ServerResponse> access(String role, boolean allowAnonymous) {
        return (request, next) -> {
            if (allowAnonymous || request.servletRequest().isUserInRole(role)) {
                return next.handle(request);
            }
            if (request.principal().isEmpty()) {
                return FeedbackRoutes.error(HttpStatus.UNAUTHORIZED, "authentication required");
            }
            return FeedbackRoutes.error(HttpStatus.FORBIDDEN, "access denied");
        };
    }

    /** Adds the security headers of every dashboard response; API responses are also never cached. */
    static HandlerFilterFunction<ServerResponse, ServerResponse> headers(boolean noStore) {
        return (request, next) -> new SecureResponse(next.handle(request), noStore);
    }

    private static ServerResponse page(ServerRequest request, DashboardPages pages, Clock clock,
            Function<InsightsFilter, Object> page) {
        InsightsFilter filter;
        try {
            filter = DashboardQuery.parse(request, pages.application(), clock);
        }
        catch (IllegalArgumentException e) {
            return FeedbackRoutes.error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return json(page.apply(filter));
    }

    private static ServerResponse conversation(ServerRequest request, DashboardPages pages) {
        String id = DashboardQuery.param(request, "id");
        if (id == null) {
            return FeedbackRoutes.error(HttpStatus.BAD_REQUEST, "id is required");
        }
        if (id.length() > MAX_ID_LENGTH) {
            return FeedbackRoutes.error(HttpStatus.BAD_REQUEST, "id is too long");
        }
        String application = DashboardQuery.param(request, "app");
        if (application != null && application.length() > MAX_ID_LENGTH) {
            return FeedbackRoutes.error(HttpStatus.BAD_REQUEST, "app is too long");
        }
        return json(pages.conversation(application == null ? pages.application() : application, id));
    }

    private static ServerResponse json(Object body) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    /** Writes the security headers to the servlet response, then the wrapped response. */
    private record SecureResponse(ServerResponse delegate, boolean noStore) implements ServerResponse {

        @Override
        public HttpStatusCode statusCode() {
            return delegate.statusCode();
        }

        @Override
        public HttpHeaders headers() {
            return delegate.headers();
        }

        @Override
        public MultiValueMap<String, jakarta.servlet.http.Cookie> cookies() {
            return delegate.cookies();
        }

        @Override
        public ModelAndView writeTo(HttpServletRequest request, HttpServletResponse response, Context context)
                throws ServletException, IOException {
            response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Referrer-Policy", "no-referrer");
            if (noStore) {
                response.setHeader("Cache-Control", "no-store");
            }
            return delegate.writeTo(request, response, context);
        }
    }
}
