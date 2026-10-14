package io.github.springaiinsights.autoconfigure;

import org.jspecify.annotations.Nullable;

import io.github.springaiinsights.identity.InsightsContextKeys;
import io.github.springaiinsights.identity.InsightsIdentity;
import io.github.springaiinsights.identity.InsightsIdentityResolver;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.ClassUtils;

/**
 * Resolves the user from the Spring Security context of the calling thread, and the tenant from a JWT claim when
 * the principal is a {@code Jwt}. Values in the request context ({@link InsightsContextKeys#USER_ID},
 * {@link InsightsContextKeys#TENANT_ID}) take precedence.
 */
public class SpringSecurityIdentityResolver implements InsightsIdentityResolver {

    private static final boolean JWT_PRESENT = ClassUtils.isPresent(
            "org.springframework.security.oauth2.jwt.Jwt", SpringSecurityIdentityResolver.class.getClassLoader());

    private final String tenantClaim;

    public SpringSecurityIdentityResolver(String tenantClaim) {
        this.tenantClaim = tenantClaim;
    }

    @Override
    public InsightsIdentity resolve(ChatClientRequest request) {
        String userId = ContextIdentityResolver.contextValue(request, InsightsContextKeys.USER_ID);
        String tenantId = ContextIdentityResolver.contextValue(request, InsightsContextKeys.TENANT_ID);
        Authentication authentication = authentication();
        if (authentication != null) {
            if (userId == null) {
                userId = authentication.getName();
            }
            if (tenantId == null && JWT_PRESENT) {
                tenantId = JwtTenant.claim(authentication.getPrincipal(), tenantClaim);
            }
        }
        return new InsightsIdentity(userId, tenantId);
    }

    private static @Nullable Authentication authentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication;
    }

    /** Isolates the reference to {@code Jwt} so this class loads without spring-security-oauth2-jose. */
    private static final class JwtTenant {

        static @Nullable String claim(@Nullable Object principal, String claim) {
            if (principal instanceof org.springframework.security.oauth2.jwt.Jwt jwt) {
                Object value = jwt.getClaims().get(claim);
                return value == null ? null : value.toString();
            }
            return null;
        }
    }
}
