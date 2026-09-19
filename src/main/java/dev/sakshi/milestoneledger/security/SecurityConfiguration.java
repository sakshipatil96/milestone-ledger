package dev.sakshi.milestoneledger.security;

import tools.jackson.databind.ObjectMapper;
import dev.sakshi.milestoneledger.shared.web.ApiErrorResponse;
import dev.sakshi.milestoneledger.shared.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppSecurityProperties.class)
@EnableMethodSecurity
public class SecurityConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(AppSecurityProperties properties) {
        return new InMemoryUserDetailsManager(
                User.withUsername("certifier")
                        .password(properties.certifierPasswordHash())
                        .roles("CERTIFIER")
                        .build(),
                User.withUsername("accounts")
                        .password(properties.accountsPasswordHash())
                        .roles("ACCOUNTS")
                        .build(),
                User.withUsername("manager")
                        .password(properties.managerPasswordHash())
                        .roles("MANAGER")
                        .build());
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        return http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**", "/livez", "/readyz", "/api/v1/health/**").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/webhooks/bank").permitAll()
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens)
                        .ignoringRequestMatchers(PathPatternRequestMatcher.pathPattern(
                                org.springframework.http.HttpMethod.POST, "/api/v1/webhooks/bank")))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(new JsonAccessDeniedHandler(objectMapper)))
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    private BasicAuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        return new BasicAuthenticationEntryPoint() {
            @Override
            public void commence(jakarta.servlet.http.HttpServletRequest request, HttpServletResponse response,
                    org.springframework.security.core.AuthenticationException exception) throws java.io.IOException {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setHeader("WWW-Authenticate", "Basic realm=\"milestone-ledger\"");
                response.setContentType("application/json");
                objectMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(new ApiErrorResponse.Error(
                        "UNAUTHENTICATED", "Authentication is required.", CorrelationIdFilter.requestId(request))));
            }
        };
    }

}
