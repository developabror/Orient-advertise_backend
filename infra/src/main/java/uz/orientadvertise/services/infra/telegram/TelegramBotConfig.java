package uz.orientadvertise.services.infra.telegram;

import java.util.List;

import javax.sql.DataSource;

import io.micrometer.core.instrument.MeterRegistry;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import uz.orientadvertise.services.domain.notification.TelegramNotifier;
import uz.orientadvertise.services.domain.repository.DeviceStatusViewRepository;
import uz.orientadvertise.services.domain.repository.IncidentRepository;
import uz.orientadvertise.services.domain.repository.DisabledTelegramChatRepository;

/**
 * Conditional wiring for the Telegram bot.
 *
 * <p><b>When {@code telegram.bot.enabled=true}:</b>
 * <ul>
 *   <li>{@link TelegramBotsApi} bean is created (one per JVM)</li>
 *   <li>{@link OrientTelegramBot} is constructed but <b>not</b> registered. Registration
 *       with Telegram is performed asynchronously by {@link TelegramBotInitializer} on
 *       {@link org.springframework.boot.context.event.ApplicationReadyEvent} with retry,
 *       so a Telegram outage at boot doesn't block the application from starting.</li>
 *   <li>{@link EnabledTelegramNotifier} is published as the active
 *       {@link TelegramNotifier}; sends issued before registration completes are
 *       silently dropped (logged at debug) until the initializer flips the ready flag.</li>
 * </ul>
 *
 * <p><b>When {@code telegram.bot.enabled=false} (default) or the property is missing:</b>
 * none of those beans are created. {@link #noOpTelegramNotifier()} provides a
 * {@link NoOpTelegramNotifier} fallback so injection sites still resolve. No Telegram
 * HTTP connection opens; no token is read; no Telegram API call leaves the JVM.
 *
 * <p><b>Fail-fast on enabled-but-blank-token.</b> The token MUST come from the
 * {@code TELEGRAM_BOT_TOKEN} environment variable — never committed. If the operator
 * sets {@code telegram.bot.enabled=true} but forgets to provide the token, the bean
 * factory throws at startup with a clear message.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(TelegramBotProperties.class)
public class TelegramBotConfig {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotConfig.class);

    @Bean
    @ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true")
    public TelegramBotsApi telegramBotsApi() throws TelegramApiException {
        return new TelegramBotsApi(DefaultBotSession.class);
    }

    @Bean
    @ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true")
    public OrientTelegramBot telegramBot(TelegramBotProperties props) {
        if (props.getToken() == null || props.getToken().isBlank()) {
            // Fail-fast: refuse to start with an enabled-but-unconfigured bot. Better
            // than logging a warning and pretending — operators will assume Telegram
            // works if startup succeeded.
            throw new IllegalStateException(
                    "telegram.bot.enabled=true but TELEGRAM_BOT_TOKEN is empty. "
                            + "Provide the token via env var, or set telegram.bot.enabled=false.");
        }
        if (props.getUsername() == null || props.getUsername().isBlank()) {
            throw new IllegalStateException(
                    "telegram.bot.enabled=true but telegram.bot.username is empty.");
        }
        // Construct only — registration happens later in TelegramBotInitializer so
        // a Telegram outage at boot doesn't block startup.
        return new OrientTelegramBot(props);
    }

    @Bean
    @ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true")
    public TelegramRateLimiter telegramRateLimiter(
            org.springframework.beans.factory.ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> redisProvider) {
        // Redis is optional in some test slices — when absent the limiter falls back
        // to its in-memory path on every call. Production wires a real
        // StringRedisTemplate via the existing RedisConfig in the infra module.
        return new TelegramRateLimiter(redisProvider.getIfAvailable());
    }

    /**
     * Telegram outcome metrics under {@code telegram.*} on {@code /actuator/metrics}.
     * Wired with the application's {@link MeterRegistry} (provided by Spring Boot
     * Actuator); when actuator is absent in some slim test slices the bean is simply
     * not created and the bot's setter remains null — recording becomes a no-op.
     */
    @Bean
    @ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true")
    @ConditionalOnBean(MeterRegistry.class)
    public TelegramMetrics telegramMetrics(MeterRegistry registry) {
        return new TelegramMetrics(registry);
    }

    @Bean
    @ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true")
    public TelegramFailureRateMonitor telegramFailureRateMonitor() {
        return new TelegramFailureRateMonitor();
    }

    @Bean
    @ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true")
    @ConditionalOnBean(DisabledTelegramChatRepository.class)
    public DisabledChatRegistry disabledChatRegistry(DisabledTelegramChatRepository repo) {
        return new DisabledChatRegistry(repo);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true")
    public TelegramOutboundQueue telegramOutboundQueue(OrientTelegramBot bot,
                                                         TelegramBotProperties props,
                                                         ObjectProvider<DisabledChatRegistry> disabledRegistryProvider,
                                                         ObjectProvider<TelegramMetrics> metricsProvider,
                                                         ObjectProvider<TelegramFailureRateMonitor> monitorProvider) {
        // Setter-wire the optional collaborators so the bot/queue constructors stay
        // small. When a dependency is missing in a slim test slice (e.g. no MeterRegistry,
        // no DataSource for the disabled-chat repo), the corresponding feature degrades
        // to a no-op — the bot still works.
        TelegramMetrics metrics = metricsProvider.getIfAvailable();
        TelegramFailureRateMonitor monitor = monitorProvider.getIfAvailable();
        DisabledChatRegistry disabledRegistry = disabledRegistryProvider.getIfAvailable();
        if (metrics != null) bot.setMetrics(metrics);
        if (monitor != null) bot.setFailureRateMonitor(monitor);
        if (disabledRegistry != null) bot.setDisabledChatRegistry(disabledRegistry);
        var queue = new TelegramOutboundQueue(bot, props);
        if (disabledRegistry != null) queue.setDisabledChatRegistry(disabledRegistry);
        return queue;
    }

    @Bean
    @ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true")
    public EnabledTelegramNotifier enabledTelegramNotifier(TelegramBotProperties props,
                                                             TelegramRateLimiter rateLimiter,
                                                             TelegramOutboundQueue outboundQueue) {
        // No longer takes the bot — sends are handed off to the outbound queue, which
        // owns the bot reference. The notifier is purely a producer.
        return new EnabledTelegramNotifier(props, rateLimiter, outboundQueue);
    }

    @Bean
    @ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true")
    public StateCommandHandler stateCommandHandler(OrientTelegramBot bot,
                                                     ObjectProvider<BuildProperties> buildProvider) {
        return new StateCommandHandler(bot, buildProvider);
    }

    /**
     * Reads from the static {@code RetainedErrorBuffer} that {@link TelegramAppender}
     * mirrors WARN/ERROR events into — no Spring-managed dependency, just the bot for
     * sending. Wiring is therefore trivially safe in slim test slices.
     */
    @Bean
    @ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true")
    public LogsCommandHandler logsCommandHandler(OrientTelegramBot bot) {
        return new LogsCommandHandler(bot);
    }

    /**
     * Health-check handler with optional dependencies — every backing service is
     * resolved via {@link ObjectProvider} so a missing bean (uncommon in production,
     * common in slim test slices) collapses to a per-row {@code DOWN — no … bean}
     * rather than a startup failure. The two executor providers are name-qualified
     * because the application has multiple {@link ThreadPoolTaskExecutor} beans
     * (urgent transcode, audit, report) and we only want the two transcode pools.
     */
    @Bean
    @ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true")
    public HealthCommandHandler healthCommandHandler(
            OrientTelegramBot bot,
            ObjectProvider<DataSource> dataSourceProvider,
            ObjectProvider<StringRedisTemplate> redisProvider,
            ObjectProvider<MinioClient> minioProvider,
            ObjectProvider<DeviceStatusViewRepository> statusViewRepoProvider,
            ObjectProvider<IncidentRepository> incidentRepoProvider,
            @Qualifier("urgentTranscodeExecutor") ObjectProvider<ThreadPoolTaskExecutor> urgentExec,
            @Qualifier("auditExecutor") ObjectProvider<ThreadPoolTaskExecutor> auditExec,
            @Value("${app.health.data-volume-path:.}") String dataVolumePath) {
        return new HealthCommandHandler(bot, dataSourceProvider, redisProvider,
                minioProvider, statusViewRepoProvider, incidentRepoProvider,
                urgentExec, auditExec, dataVolumePath);
    }

    /**
     * Aggregates every {@link TelegramCommandHandler} bean and wires the dispatcher into
     * the bot. The setter-based wiring (rather than constructor injection on
     * {@link OrientTelegramBot}) keeps the bot's constructor dependency-free — handlers
     * have their own deps (BuildProperties, etc.) which would otherwise drag the bot
     * graph wider and complicate the test slices.
     */
    @Bean
    @ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true")
    public TelegramCommandDispatcher telegramCommandDispatcher(OrientTelegramBot bot,
                                                                  List<TelegramCommandHandler> handlers) {
        var dispatcher = new TelegramCommandDispatcher(handlers);
        bot.setCommandDispatcher(dispatcher);
        log.info("Telegram command dispatcher wired with {} handler(s): {}",
                handlers.size(), handlers.stream().map(TelegramCommandHandler::name).toList());
        return dispatcher;
    }

    /**
     * Fallback wired only when no real {@link TelegramNotifier} was created above —
     * i.e. when the bot is disabled. Guarantees every {@code @Autowired
     * TelegramNotifier} site resolves regardless of the feature flag.
     */
    @Bean
    @ConditionalOnMissingBean(TelegramNotifier.class)
    public TelegramNotifier noOpTelegramNotifier() {
        log.info("Telegram bot disabled — wiring no-op notifier");
        return new NoOpTelegramNotifier();
    }
}
