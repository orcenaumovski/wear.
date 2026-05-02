package com.vicevice.app.config;

import org.sqlite.SQLiteDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class DataSourceConfig {
    @Bean
    public DataSource dataSource(@Value("${spring.datasource.url}") String url) throws Exception {
        ensureParentDirExists(url);

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl(url);
        return ds;
    }

    private static void ensureParentDirExists(String url) throws Exception {
        // For our SQLite local-first setup, we typically use: jdbc:sqlite:./data/vicevice.db
        final String prefix = "jdbc:sqlite:";
        if (url == null || !url.startsWith(prefix)) {
            return;
        }

        String pathPart = url.substring(prefix.length());
        if (pathPart.startsWith("file:")) {
            pathPart = pathPart.substring("file:".length());
        }

        Path p = Path.of(pathPart).normalize();
        Path parent = p.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}

