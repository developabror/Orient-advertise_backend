package uz.orientadvertise.services.infra.telegram;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import uz.orientadvertise.services.domain.notification.TelegramNotifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the conditional wiring contract — the security-critical part of this feature.
 * The flag {@code telegram.bot.enabled} must drive whether {@link OrientTelegramBot} and
 * {@link TelegramBotsApi} get constructed at all (no Telegram HTTP connection opens when
 * disabled).
 *
 * <p>Uses {@link ApplicationContextRunner} rather than {@code @SpringBootTest} so the
 * test starts a fresh slim context per case, no DB / Redis required. The bot itself
 * is constructed but not registered (registration is async via
 * {@link TelegramBotInitializer}), so the tests don't make any network calls.
 */
class TelegramBotConfigConditionalsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(TelegramBotConfig.class);

    @Test
    void disabled_byDefault_wiresNoOpAndOmitsBot() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(TelegramNotifier.class);
            assertThat(ctx.getBean(TelegramNotifier.class)).isInstanceOf(NoOpTelegramNotifier.class);
            // The hard guarantee: no Telegram HTTP connection opens when disabled.
            assertThat(ctx).doesNotHaveBean(TelegramBotsApi.class);
            assertThat(ctx).doesNotHaveBean(OrientTelegramBot.class);
            assertThat(ctx).doesNotHaveBean(EnabledTelegramNotifier.class);
        });
    }

    @Test
    void enabledFalse_explicit_stillWiresNoOp() {
        runner.withPropertyValues("telegram.bot.enabled=false")
                .run(ctx -> {
                    assertThat(ctx.getBean(TelegramNotifier.class)).isInstanceOf(NoOpTelegramNotifier.class);
                    assertThat(ctx).doesNotHaveBean(OrientTelegramBot.class);
                });
    }

    @Test
    void enabledTrue_blankToken_failsFastAtStartup() {
        runner.withPropertyValues(
                        "telegram.bot.enabled=true",
                        "telegram.bot.token=",
                        "telegram.bot.username=somebot")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .isInstanceOf(BeanCreationException.class)
                            .hasMessageContaining("TELEGRAM_BOT_TOKEN");
                });
    }

    @Test
    void enabledTrue_blankUsername_failsFastAtStartup() {
        runner.withPropertyValues(
                        "telegram.bot.enabled=true",
                        "telegram.bot.token=fake-token-for-test",
                        "telegram.bot.username=")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .hasMessageContaining("username");
                });
    }

    @Test
    void enabledTrue_validConfig_constructsBotButDoesNotRegister() {
        // Bot is constructed but registration is deferred to TelegramBotInitializer.
        // No network call happens during context startup.
        runner.withPropertyValues(
                        "telegram.bot.enabled=true",
                        "telegram.bot.token=fake-token-for-test",
                        "telegram.bot.username=somebot",
                        "telegram.bot.authorized-chat-ids=100,200")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(OrientTelegramBot.class);
                    assertThat(ctx).hasSingleBean(EnabledTelegramNotifier.class);
                    assertThat(ctx.getBean(TelegramNotifier.class))
                            .isInstanceOf(EnabledTelegramNotifier.class);
                    // Allow-list correctly bound from comma-separated string to Set<Long>.
                    var props = ctx.getBean(TelegramBotProperties.class);
                    assertThat(props.getAuthorizedChatIds()).containsExactlyInAnyOrder(100L, 200L);
                    // The notifier reports not-yet-registered until the initializer runs.
                    assertThat(ctx.getBean(EnabledTelegramNotifier.class).isRegistered()).isFalse();
                });
    }

    @Test
    void enabledTrue_componentScannedInitializerIsConstructable() {
        // Regression guard (v1.0.119). TelegramBotInitializer is component-scanned (a
        // @Component, NOT a @Bean here) and declares THREE constructors — one production
        // + two package-private test helpers. With none annotated @Autowired, Spring can't
        // pick a constructor and falls back to a non-existent no-arg one, failing bean
        // creation with "No default constructor found" the instant the bot is enabled.
        // This took prod down on the first-ever enable. Every other case in this class only
        // registers TelegramBotConfig (its @Bean methods), so the @Component initializer was
        // never instantiated and the gap went unnoticed. Register it explicitly so the
        // enabled construction path is actually exercised.
        runner.withUserConfiguration(TelegramBotInitializer.class)
                .withPropertyValues(
                        "telegram.bot.enabled=true",
                        "telegram.bot.token=fake-token-for-test",
                        "telegram.bot.username=somebot",
                        "telegram.bot.authorized-chat-ids=100,200")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(TelegramBotInitializer.class);
                });
    }

    @Test
    void enabledTrue_emptyAuthorizedChats_doesNotCrash() {
        // Edge case: empty allow-list must NOT throw at construction. The bot still
        // boots; sends become no-ops. A warning is logged at construction (not asserted
        // here — covered by EnabledTelegramNotifierTest.emptyAllowList_*).
        runner.withPropertyValues(
                        "telegram.bot.enabled=true",
                        "telegram.bot.token=fake-token-for-test",
                        "telegram.bot.username=somebot",
                        "telegram.bot.authorized-chat-ids=")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(OrientTelegramBot.class);
                    var props = ctx.getBean(TelegramBotProperties.class);
                    assertThat(props.getAuthorizedChatIds()).isEmpty();
                });
    }

    @Test
    void enabledTrue_authorizedChatsWithWhitespace_trimmedCorrectly() {
        // Spring's StringToCollectionConverter trims whitespace around comma separators.
        runner.withPropertyValues(
                        "telegram.bot.enabled=true",
                        "telegram.bot.token=fake-token-for-test",
                        "telegram.bot.username=somebot",
                        "telegram.bot.authorized-chat-ids=  100, 200 , -300  ")
                .run(ctx -> {
                    var props = ctx.getBean(TelegramBotProperties.class);
                    assertThat(props.getAuthorizedChatIds()).containsExactlyInAnyOrder(100L, 200L, -300L);
                });
    }

    @Test
    void enabledTrue_wiresDispatcherAndStateHandlerIntoBot() {
        // The /state command flow at the wiring level: when the bot is enabled, a
        // StateCommandHandler is created, the dispatcher collects every handler bean,
        // and the dispatcher is set on the bot. Verifies the post-construction setter
        // pattern doesn't get silently skipped.
        runner.withPropertyValues(
                        "telegram.bot.enabled=true",
                        "telegram.bot.token=fake-token-for-test",
                        "telegram.bot.username=somebot",
                        "telegram.bot.authorized-chat-ids=100,200")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(StateCommandHandler.class);
                    assertThat(ctx).hasSingleBean(TelegramCommandDispatcher.class);
                    var dispatcher = ctx.getBean(TelegramCommandDispatcher.class);
                    assertThat(dispatcher.handlersForTest()).containsKey("/state");
                });
    }

    @Test
    void isAuthorized_helper_handlesNullAndMembership() {
        var props = new TelegramBotProperties();
        props.setAuthorizedChatIds(java.util.Set.of(1L, 2L));
        assertThat(props.isAuthorized(1L)).isTrue();
        assertThat(props.isAuthorized(2L)).isTrue();
        assertThat(props.isAuthorized(3L)).isFalse();
        assertThat(props.isAuthorized(null)).isFalse();
    }
}
