package com.exe.morphia3d.config;

import com.exe.morphia3d.security.JwtAuthFilter;
import com.exe.morphia3d.security.UserDetailsServiceImpl;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserDetailsServiceImpl userDetailsService;
    private final JwtAuthFilter jwtAuthFilter;
    private final Environment environment;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Cho phép các port Frontend của bạn (Vite thường dùng 5173, Create React App dùng 3000)
        List<String> allowedOrigins = new ArrayList<>(List.of(
                "http://localhost:3000",
                "http://localhost:5173",
                "https://morphia-3-d-fe.vercel.app"
        ));
        if (environment.acceptsProfiles(Profiles.of("dev", "test", "local"))) {
            allowedOrigins.add("http://127.0.0.1:3000");
        }
        if (environment.acceptsProfiles(Profiles.of("qa"))) {
            allowedOrigins.add("http://127.0.0.1:3001");
        }
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        configuration.setAllowCredentials(true); // Cho phép gửi Token/Cookie

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration); // Áp dụng cho mọi endpoint
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/auth/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/payments/vnpay/return",
                                "/api/payments/momo/return").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/payments/momo/ipn").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/materials/**",
                                "/api/colors/**",
                                "/api/products",
                                "/api/products/*",
                                "/api/designer-models",
                                "/api/designer-models/*").permitAll()
                        .requestMatchers("/api/admin/staff/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/me/staff-profile")
                                .hasAuthority("internal.employee.access")
                        .requestMatchers("/api/accounts/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/admin/designer-models/*/publish").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/colors/**", "/api/materials/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/materials/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/materials/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/designer-models").hasAnyRole("ADMIN", "DESIGNER")
                        .requestMatchers(HttpMethod.POST, "/api/ai-models").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.GET, "/api/ai-models/**").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.PATCH, "/api/orders/*/status").hasRole("ADMIN")
                        .requestMatchers("/api/orders/**", "/api/payments").hasAnyRole("ADMIN", "USER")
                        .anyRequest().authenticated())
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
