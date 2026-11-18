package ua.knu.promptlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ua.knu.promptlab.mock.FakeLlmServer;

/**
 * Спільний інтерфейс для провайдерів (Claude, Gemini): кроки лаби не знають, з ким розмовляють.
 * Саме цей інтерфейс студенти розширюватимуть у наступних лабораторних.
 */
public interface LlmClient extends AutoCloseable {

    /** Назва провайдера для виводу: Claude / Gemini. */
    String provider();

    String model();

    boolean isMock();

    /** Чи має провайдер безкоштовний рівень (тоді реальна вартість запитів = $0). */
    default boolean hasFreeTier() {
        return false;
    }

    /** Пауза між запитами в циклах (лабораторна 1, крок 5; лабораторна 2, крок 7), мс — LAB_DELAY_MS. */
    default long requestDelayMs() {
        return 0;
    }

    /** Текстовий опис ендпоінта й способу автентифікації — для показу на екрані. */
    String endpointDescription();

    /** Тіло запиту до моделі. Публічне, щоб його можна було показати на екрані. */
    ObjectNode buildRequest(String system, String user, Double temperature, int maxTokens);

    /** Один запит до моделі. temperature = null → значення за замовчуванням. */
    LlmResponse ask(String system, String user, Double temperature, int maxTokens);

    /** Кількість вхідних токенів без генерації відповіді. */
    int countTokens(String system, String user);

    /**
     * Лабораторна 2. Запит зі схемою відповіді: провайдер обмежує генерацію так, щоб вивід був валідним JSON
     * за {@code jsonSchema} (JSON Schema, підмножина). Форму гарантує провайдер, зміст — ваш validate().
     */
    LlmResponse askJson(String system, String user, JsonNode jsonSchema, int maxTokens);

    /** Тіло запиту зі схемою — щоб показати на екрані, що саме відправляється. */
    ObjectNode buildJsonRequest(String system, String user, JsonNode jsonSchema, int maxTokens);

    /**
     * Лабораторна 3. Те саме, що {@link #askJson(String, String, JsonNode, int)}, але з рівнем міркування (effort) і температурою.
     * Реалізація за замовчуванням ігнорує options — щоб старі (й ваші власні) клієнти компілювались без змін.
     */
    default LlmResponse askJson(String system, String user, JsonNode jsonSchema, int maxTokens, CallOptions options) {
        return askJson(system, user, jsonSchema, maxTokens);
    }

    /** Лабораторна 3. Тіло запиту з options — і для показу на екрані, і як ключ кешу відповідей. */
    default ObjectNode buildJsonRequest(String system, String user, JsonNode jsonSchema, int maxTokens, CallOptions options) {
        return buildJsonRequest(system, user, jsonSchema, maxTokens);
    }

    /**
     * Лабораторна 3. Що саме клієнт надсилає для цього рівня міркування — одним рядком для консолі.
     * Викликайте ПІСЛЯ запитів: клієнт міг перейти на інший механізм (наприклад, Claude 4.x не приймає effort).
     */
    default String effortParameter(CallOptions.Effort effort) {
        return "не підтримується цим клієнтом";
    }

    /**
     * Чи надсилається temperature. false — модель уже відповіла 400 на temperature, і клієнт до кінця прогону
     * надсилає запити без неї (значення моделі за замовчуванням).
     */
    default boolean temperatureSupported() {
        return true;
    }

    /** Чи повідомляє API, скільки з вихідних токенів припало на міркування (Gemini — так, Claude — ні). */
    default boolean reportsThinkingTokens() {
        return true;
    }

    @Override
    void close();

    /** Створює клієнт за .env / змінними середовища; у режимі LAB_MOCK піднімає локальну імітацію. */
    static LlmClient create() {
        Config cfg;
        try {
            cfg = Config.load();
        } catch (IllegalStateException e) {
            // студентам зрозуміліша коротка підказка, ніж стек-трейс
            System.err.println(e.getMessage());
            System.exit(1);
            return null; // недосяжно
        }
        String baseUrl = cfg.baseUrl();
        if (cfg.mock()) {
            baseUrl = FakeLlmServer.startOnce().baseUrl();
            System.out.println("*** РЕЖИМ MOCK: відповіді й лічильники токенів ІМІТОВАНІ, до " + cfg.provider().title
                    + " запити не йдуть ***");
        }
        return switch (cfg.provider()) {
            case CLAUDE -> new ClaudeClient(cfg, baseUrl);
            case GEMINI -> new GeminiClient(cfg, baseUrl);
        };
    }
}
