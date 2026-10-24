package fr.patria;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
    @Bean
    UserDetailsService users(@Value("${app.password:}") String password) {
        if (password.isBlank()) {
            throw new IllegalStateException("APP_PASSWORD doit etre defini.");
        }

        String storedPassword = password.startsWith("{") ? password : "{noop}" + password;
        return new InMemoryUserDetailsManager(User.withUsername("patria")
                .password(storedPassword)
                .roles("USER")
                .build());
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/connexion", "/css/**").permitAll()
                        .anyRequest().authenticated())
                .formLogin(login -> login
                        .loginPage("/connexion")
                        .loginProcessingUrl("/connexion")
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/connexion?error")
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/deconnexion")
                        .logoutSuccessUrl("/connexion?logout"))
                .build();
    }
}
