package co.nine.billing.auth;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.function.Supplier;

/**
 * Wraps whatever DataSource Spring builds so every connection carries the
 * tenant GUC, and gives operator work a pool of its own under a second
 * database role.
 *
 * <p>The operator pool is built here rather than declared as a bean, and that
 * is deliberate: a DataSource bean would be picked up by the post processor
 * below and wrapped in turn, so the operator connection would arrive with the
 * tenant GUC written on it and the routing would be circular. Keeping it out of
 * the context is simpler than teaching the post processor to skip one bean by
 * name.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    static BeanPostProcessor tenantAwareDataSourceWrapper(
            @Value("${spring.datasource.url}") String url,
            @Value("${nine.billing.operator.username:nine_operator}") String username,
            @Value("${nine.billing.operator.password}") String password) {

        // Lazy, and lazily memoised. The post processor runs before Flyway, and
        // V9 is what creates nine_operator, so opening a connection at
        // construction would fail on a fresh database.
        Supplier<DataSource> operator = new Supplier<>() {
            private volatile DataSource pool;

            @Override
            public DataSource get() {
                DataSource local = pool;
                if (local == null) {
                    synchronized (this) {
                        local = pool;
                        if (local == null) {
                            pool = local = build(url, username, password);
                        }
                    }
                }
                return local;
            }
        };

        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof DataSource ds && !(bean instanceof TenantAwareDataSource)) {
                    return new TenantAwareDataSource(ds, operator);
                }
                return bean;
            }
        };
    }

    /**
     * Two connections, because operator work is a job every fifteen minutes and
     * the occasional first key for a new tenant. A pool rather than a bare
     * DriverManager call so that a future operator path on a warmer route does
     * not pay a TCP handshake and an authentication round trip per request.
     */
    private static DataSource build(String url, String username, String password) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(username);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(2);
        cfg.setPoolName("nine-operator");
        return new HikariDataSource(cfg);
    }
}
