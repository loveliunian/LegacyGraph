package io.github.legacygraph;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableRetry
@MapperScan("io.github.legacygraph.repository")
@ConfigurationPropertiesScan("io.github.legacygraph")
public class LegacyGraphApplication {

    public static void main(String[] args) {
        SpringApplication.run(LegacyGraphApplication.class, args);
    }
}
