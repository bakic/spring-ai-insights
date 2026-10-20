package io.github.bakic.springaiinsights.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/** Demo users: {@code admin} may open the dashboard (role AI_INSIGHTS), {@code alice} may only chat. */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
            .authorizeHttpRequests(requests -> requests.requestMatchers("/actuator/health", "/actuator/prometheus")
                .permitAll()
                .anyRequest()
                .authenticated())
            .formLogin(Customizer.withDefaults())
            .httpBasic(Customizer.withDefaults())
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/chat"))
            .build();
    }

    @Bean
    InMemoryUserDetailsManager users(@Value("${demo.users.admin-password:admin}") String adminPassword,
            @Value("${demo.users.alice-password:alice}") String alicePassword) {
        return new InMemoryUserDetailsManager(
                User.withUsername("admin").password("{noop}" + adminPassword).roles("AI_INSIGHTS").build(),
                User.withUsername("alice").password("{noop}" + alicePassword).roles("USER").build());
    }
}
