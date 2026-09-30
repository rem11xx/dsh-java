package com.dsh;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * dsh-java 启动入口。
 * DeepSeek Harness 的最小 Java 复刻版, 核心概念对照见项目根目录 CLAUDE.md。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DshApplication {

    public static void main(String[] args) {
        SpringApplication.run(DshApplication.class, args);
    }
}
