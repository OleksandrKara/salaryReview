package com.salonreview.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;

import static org.mockito.Mockito.mock;

/** Test-scope only: satisfy explicit timer injection without registering or executing timers. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "false")
public class DisabledTaskSchedulerTestConfig {
    @Bean
    TaskScheduler disabledTaskScheduler() { return mock(TaskScheduler.class); }
}
