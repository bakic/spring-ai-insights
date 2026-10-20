package io.github.bakic.springaiinsights.autoconfigure;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.bakic.springaiinsights.identity.InsightsContextKeys;
import io.github.bakic.springaiinsights.identity.InsightsIdentity;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class SpringSecurityIdentityResolverTest {

    private final SpringSecurityIdentityResolver resolver = new SpringSecurityIdentityResolver("tenant_id");

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void noAuthenticationIsAnonymous() {
        assertThat(resolver.resolve(request(Map.of()))).isEqualTo(InsightsIdentity.ANONYMOUS);
    }

    @Test
    void anonymousTokenIsAnonymous() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThat(resolver.resolve(request(Map.of()))).isEqualTo(InsightsIdentity.ANONYMOUS);
    }

    @Test
    void authenticatedUserIsTheName() {
        authenticate("alice");

        assertThat(resolver.resolve(request(Map.of()))).isEqualTo(new InsightsIdentity("alice", null));
    }

    @Test
    void contextValuesWinOverSecurity() {
        authenticate("alice");

        InsightsIdentity identity = resolver.resolve(
                request(Map.of(InsightsContextKeys.USER_ID, "bob", InsightsContextKeys.TENANT_ID, "acme")));

        assertThat(identity).isEqualTo(new InsightsIdentity("bob", "acme"));
    }

    @Test
    void tenantComesFromJwtClaim() {
        Jwt jwt = Jwt.withTokenValue("t")
            .header("alg", "none")
            .subject("alice")
            .claim("tenant_id", "acme")
            .issuedAt(Instant.now())
            .build();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(jwt, null, List.of()));

        assertThat(resolver.resolve(request(Map.of())).tenantId()).isEqualTo("acme");
    }

    @Test
    void contextResolverReadsOnlyTheContext() {
        authenticate("alice");
        ContextIdentityResolver context = new ContextIdentityResolver();

        assertThat(context.resolve(request(Map.of()))).isEqualTo(InsightsIdentity.ANONYMOUS);
        assertThat(context.resolve(request(Map.of(InsightsContextKeys.USER_ID, "bob", InsightsContextKeys.TENANT_ID, " "))))
            .isEqualTo(new InsightsIdentity("bob", null));
    }

    private static void authenticate(String name) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(name, null, List.of()));
    }

    private static ChatClientRequest request(Map<String, Object> context) {
        return ChatClientRequest.builder().prompt(new Prompt("hi")).context(context).build();
    }
}
