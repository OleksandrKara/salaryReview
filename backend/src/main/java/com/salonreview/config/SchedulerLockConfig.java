package com.salonreview.config;

import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Distributed lock backing (V64's {@code shedlock} table) so a {@code @Scheduled} job only
 * actually runs on one backend replica at a time — running two live replicas (blue/green)
 * otherwise lets both grab the same due row, e.g. sending the same SMS to a customer twice. See
 * {@code @SchedulerLock} on the affected methods in the sms package, and
 * {@link com.salonreview.square.RevenueSnapshotScheduler} for the one scheduler that locks
 * manually (it uses {@code SchedulingConfigurer}, not {@code @Scheduled}, so the annotation-based
 * AOP interception below doesn't apply to it).
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(dataSource);
    }

    @Bean
    public LockingTaskExecutor lockingTaskExecutor(LockProvider lockProvider) {
        return new DefaultLockingTaskExecutor(lockProvider);
    }
}
