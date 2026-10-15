package dev.doc2tex;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class Doc2TexApplication {
    public static void main(String[] args) {
        SpringApplication.run(Doc2TexApplication.class, args);
    }
}
