package com.praful.filehandler.common;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes PostgreSQL connection strings from cloud providers (Render, Heroku, Railway...)
 * into the JDBC form Spring Boot requires.
 *
 * Render's "Internal Database URL" looks like:
 *     postgresql://user:pass@host:5432/dbname
 * but the PostgreSQL JDBC driver requires:
 *     jdbc:postgresql://host:5432/dbname
 *
 * Credentials embedded in the URL are split out into spring.datasource.username/password,
 * unless they are already supplied separately. Values that already start with "jdbc:" are
 * left untouched.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Pattern URL_PATTERN = Pattern.compile(
            "^(postgres|postgresql)://(?:([^:@/]+)(?::([^@/]*))?@)?([^:/?]+)(?::(\\d+))?(/.*)?$");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String rawUrl = firstNonBlank(
                environment.getProperty("SPRING_DATASOURCE_URL"),
                environment.getProperty("spring.datasource.url"),
                environment.getProperty("JDBC_DATABASE_URL"),
                environment.getProperty("DATABASE_URL"));

        if (rawUrl == null || rawUrl.trim().startsWith("jdbc:")) {
            return;
        }

        Matcher matcher = URL_PATTERN.matcher(rawUrl.trim());
        if (!matcher.matches()) {
            return;
        }

        StringBuilder jdbcUrl = new StringBuilder("jdbc:postgresql://").append(matcher.group(4));
        if (matcher.group(5) != null) {
            jdbcUrl.append(':').append(matcher.group(5));
        }
        if (matcher.group(6) != null) {
            jdbcUrl.append(matcher.group(6));
        }

        Map<String, Object> props = new HashMap<>();
        props.put("spring.datasource.url", jdbcUrl.toString());

        String embeddedUser = matcher.group(2);
        String embeddedPassword = matcher.group(3);
        if (embeddedUser != null && !embeddedUser.isBlank()
                && environment.getProperty("spring.datasource.username") == null) {
            props.put("spring.datasource.username", embeddedUser);
        }
        if (embeddedPassword != null && !embeddedPassword.isBlank()
                && environment.getProperty("spring.datasource.password") == null) {
            props.put("spring.datasource.password", embeddedPassword);
        }

        // Highest precedence so it wins over the raw SPRING_DATASOURCE_URL / DATABASE_URL env vars.
        environment.getPropertySources().addFirst(new MapPropertySource("normalizedDatabaseUrl", props));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
