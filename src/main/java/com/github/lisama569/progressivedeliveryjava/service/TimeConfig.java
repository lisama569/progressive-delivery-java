package com.github.lisama569.progressivedeliveryjava.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时间源：默认 UTC 系统时钟；测试中可用固定时钟替换以复现观测窗口判定。
 */
@Configuration
public class TimeConfig {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
