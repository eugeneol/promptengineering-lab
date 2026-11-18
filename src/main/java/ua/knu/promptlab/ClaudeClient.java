package ua.knu.promptlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ua.knu.promptlab.mock.FakeLlmServer;

import java.util.List;
import java.util.Map;

/**
 * Клієнт Messages API (Anthropic Claude) без SDK — звичайний POST із JSON і трьома заголовками.
 */
public final class ClaudeClient implements LlmClient {

    public static final String API_VERSION = "2023-06-01";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Config config;
    private final String baseUrl;
    private final HttpJson http = new HttpJson(2, 1000);
    // новіші моделі (напр. Sonnet 5.5) відповідають 400 «temperature is deprecated for this model»;
    // тоді параметр просто не надсилаємо
    private volatile boolean temperatureSupported = true;

    public ClaudeClient(Config config, String baseUrl) {
        this.config = config;
        this.baseUrl = baseUrl;
    }

    @Override
    public String provider() {
        return "Claude";
    }

    @Override
    public String model() {
        return config.model();
    }

    @Override
    public boolean isMock() {
        return config.mock();
    }

    @Override
    public long requestDelayMs() {
        return config.delayMs();
    }

    @Override
    public String endpointDescription() {
        return "POST " + baseUrl + "/v1/messages  (ключ іде в заголовку x-api-key, не в тілі)";
    }

    @Override
    public void close() {
        if (config.mock()) FakeLlmServer.stop();
    }

    @Override
    public ObjectNode buildRequest(String system, String user, Double temperature, int maxTokens) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", config.model());
        body.put("max_tokens", maxTokens);
        if (temperature != null && temperatureSupported) body.put("temperature", temperature);
        if (system != null && !system.isBlank()) body.put("system", system);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "user").put("content", user);
        return body;
    }

    @Override
    public LlmResponse ask(String system, String user, Double temperature, int maxTokens) {
        long t0 = System.nanoTime();
        JsonNode resp = post(temperature, false, () -> buildRequest(system, user, temperature, maxTokens));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return parseMessage(resp, ms);
    }

    // ------------------------------------------------------------------ лабораторна 3: рівень міркування (effort)

    /**
     * Механізм міркування Claude — залежить від покоління моделі (станом на 11.2026 — звіряйте документацію):
     * <ul>
     *   <li>{@link #EFFORT} — Claude 5.x: {@code thinking: {type: "adaptive"}} + {@code output_config.effort};
     *       найнижче — {@code thinking: {type: "between_tools"}} + effort low ("disabled" і budget_tokens → 400);</li>
     *   <li>{@link #BUDGET} — Claude 4.x / Haiku 4.5 і старіші: {@code thinking: {type: "enabled", budget_tokens: N}};
     *       effort і adaptive → 400; мінімальне = без thinking.</li>
     * </ul>
     */
    public enum Mechanism {
        EFFORT("output_config.effort + thinking adaptive (Claude 5.x)"),
        BUDGET("thinking.budget_tokens (Claude 4.x: модель не приймає effort)");

        public final String title;

        Mechanism(String title) {
            this.title = title;
        }
    }

    // Починаємо з новішого механізму (effort). Перша 400-помилка про effort / adaptive / between_tools → переходимо на budget_tokens
    // (Claude 4.x). Після першої успішної відповіді з параметром міркування (або після переходу) механізм запам'ятовується до кінця прогону.
    private volatile Mechanism mechanism = Mechanism.EFFORT;
    private volatile boolean mechanismConfirmed = false;

    /** Механізм, яким клієнт зараз передає рівень міркування (після першого запиту — той, що реально спрацював). */
    public Mechanism mechanism() {
        return mechanism;
    }

    /** Бюджет міркування в токенах для Claude 4.x: LOW 1024, MEDIUM 2048, HIGH 4096 (або LAB_THINKING_BUDGET, не менше 1024). */
    int thinkingBudget(CallOptions.Effort effort) {
        return switch (effort) {
            case LOW -> 1024;
            case MEDIUM -> 2048;
            case HIGH -> config.thinkingBudget() != null ? Math.max(1024, config.thinkingBudget()) : 4096;
            default -> 0;
        };
    }

    /**
     * Запас max_tokens на міркування: max_tokens у Claude покриває і міркування, і текст. У 4.x це точний бюджет,
     * у 5.x (adaptive) бюджету немає — даємо той самий запас, щоб міркування не «з'їло» місце для JSON.
     */
    int thinkingReserve(CallOptions.Effort effort) {
        return effort.thinks() ? thinkingBudget(effort) : 0;
    }

    @Override
    public String effortParameter(CallOptions.Effort effort) {
        if (effort == null || effort == CallOptions.Effort.DEFAULT) return "thinking не надсилається (як у моделі за замовчуванням)";
        String how = mechanismConfirmed ? "" : "  [механізм ще не перевірено запитом]";
        if (mechanism == Mechanism.EFFORT) {
            return (effort == CallOptions.Effort.MINIMAL
                    ? "thinking = {\"type\":\"between_tools\"}, output_config.effort = \"low\"  (найнижче в Claude 5.x; вимкнути повністю не можна)"
                    : "thinking = {\"type\":\"adaptive\"}, output_config.effort = \"" + effort.apiName() + "\"  (Claude 5.x; max_tokens + "
                    + thinkingReserve(effort) + " на міркування)") + how;
        }
        return (effort == CallOptions.Effort.MINIMAL
                ? "thinking не надсилається (Claude 4.x: без міркування)"
                : "thinking = {\"type\":\"enabled\",\"budget_tokens\":" + thinkingBudget(effort) + "}  (Claude 4.x — effort модель не прийняла; "
                + "max_tokens збільшено на бюджет)") + how;
    }

    @Override
    public boolean temperatureSupported() {
        return temperatureSupported;
    }

    @Override
    public boolean reportsThinkingTokens() {
        return false; // usage.output_tokens уже включає міркування, окремого лічильника немає
    }

    // ------------------------------------------------------------------ лабораторна 2: структурований вивід

    @Override
    public ObjectNode buildJsonRequest(String system, String user, JsonNode jsonSchema, int maxTokens) {
        return buildJsonRequest(system, user, jsonSchema, maxTokens, CallOptions.DEFAULT);
    }

    /**
     * Лабораторна 3: зі схемою і рівнем міркування (див. {@link Mechanism}). max_tokens = maxTokens + запас на міркування.
     * Схема і effort лежать в одному об'єкті output_config. Блоки thinking у відповіді не потрібні: беремо лише text.
     */
    @Override
    public ObjectNode buildJsonRequest(String system, String user, JsonNode jsonSchema, int maxTokens, CallOptions options) {
        CallOptions.Effort effort = options.effort();
        ObjectNode body = buildRequest(system, user, options.temperature(), maxTokens + thinkingReserve(effort));
        ObjectNode outputConfig = JSON.createObjectNode();
        if (effort != CallOptions.Effort.DEFAULT) {
            if (mechanism == Mechanism.EFFORT) {
                body.putObject("thinking").put("type", effort == CallOptions.Effort.MINIMAL ? "between_tools" : "adaptive");
                outputConfig.put("effort", effort == CallOptions.Effort.MINIMAL ? "low" : effort.apiName());
            } else if (effort.thinks()) {
                body.putObject("thinking").put("type", "enabled").put("budget_tokens", thinkingBudget(effort));
            }
        }
        // Claude: output_config.format = {type: json_schema, schema}; об'єкти мусять мати additionalProperties:false
        outputConfig.putObject("format").put("type", "json_schema").set("schema", toClaudeSchema(jsonSchema));
        body.set("output_config", outputConfig);
        return body;
    }

    /** Ключі JSON Schema, на які structured outputs Claude відповідає 400 (станом на 11.2026 — звіряйте документацію). */
    static final List<String> UNSUPPORTED_KEYS = List.of(
            "maxItems", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "minLength", "maxLength");

    /**
     * Копія схеми без ключів, яких Claude не приймає: maxItems, minimum/maximum, minLength/maxLength,
     * а також minItems, якщо він більший за 1 (0 і 1 Claude приймає). Рекурсивно — у вкладених properties та items.
     * Самі правила не зникають: їх перевіряє TicketValidator.validate() (рівень 2).
     */
    public static JsonNode toClaudeSchema(JsonNode schema) {
        JsonNode copy = schema.deepCopy();
        strip(copy);
        return copy;
    }

    private static void strip(JsonNode node) {
        if (node.isObject()) {
            ObjectNode o = (ObjectNode) node;
            UNSUPPORTED_KEYS.forEach(o::remove);
            if (o.path("minItems").isNumber() && o.path("minItems").asInt() > 1) o.remove("minItems");
            o.fields().forEachRemaining(e -> {
                if (e.getKey().equals("properties") && e.getValue().isObject()) {
                    // тут ключі — назви полів (поле може зватися, наприклад, "maximum"), тож їх не чіпаємо, лише заходимо всередину
                    e.getValue().forEach(ClaudeClient::strip);
                } else {
                    strip(e.getValue());
                }
            });
        } else if (node.isArray()) {
            node.forEach(ClaudeClient::strip);
        }
    }

    @Override
    public LlmResponse askJson(String system, String user, JsonNode jsonSchema, int maxTokens) {
        return askJson(system, user, jsonSchema, maxTokens, CallOptions.DEFAULT);
    }

    /** Як askJson, але з options: фолбеки для temperature і для механізму міркування — див. {@link #post}. */
    @Override
    public LlmResponse askJson(String system, String user, JsonNode jsonSchema, int maxTokens, CallOptions options) {
        long t0 = System.nanoTime();
        boolean thinkingParam = options.effort() != CallOptions.Effort.DEFAULT;
        JsonNode resp = post(options.temperature(), thinkingParam, () -> buildJsonRequest(system, user, jsonSchema, maxTokens, options));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return parseMessage(resp, ms);
    }

    /**
     * POST /v1/messages з фолбеками, кожен — один раз за прогін і з повідомленням у консолі:
     * <ul>
     *   <li>400 про temperature (новіші моделі її не приймають) → далі без temperature;</li>
     *   <li>400 про effort / adaptive / between_tools (Claude 4.x) → механізм BUDGET до кінця прогону.
     *       Перша успішна відповідь з параметром міркування фіксує механізм EFFORT.</li>
     * </ul>
     * Інші помилки не ховаємо — їх побачить крок лаби.
     */
    private JsonNode post(Double temperature, boolean thinkingParam, java.util.function.Supplier<ObjectNode> body) {
        for (int attempt = 0; ; attempt++) {
            try {
                JsonNode resp = http.post(baseUrl + "/v1/messages", headers(), body.get());
                if (thinkingParam) mechanismConfirmed = true;
                return resp;
            } catch (ApiException e) {
                if (attempt >= 3 || e.status() != 400 || e.getMessage() == null) throw e;
                String m = e.getMessage().toLowerCase(java.util.Locale.ROOT);
                if (temperature != null && temperatureSupported && m.contains("temperature")) {
                    System.err.println("  [" + config.model() + " не приймає temperature — параметр не надсилається, "
                            + "використовується значення моделі за замовчуванням]");
                    temperatureSupported = false;
                    continue;
                }
                boolean effortRejected = m.contains("effort") || m.contains("adaptive") || m.contains("between_tools");
                if (thinkingParam && !mechanismConfirmed && mechanism == Mechanism.EFFORT && effortRejected) {
                    System.err.println("  [" + config.model() + " не приймає effort (HTTP 400: " + clip(e.getMessage()) + ") — це покоління Claude 4.x; "
                            + "далі рівень міркування передається як thinking.budget_tokens]");
                    mechanism = Mechanism.BUDGET;
                    mechanismConfirmed = true; // запам'ятали; якщо й budget_tokens не підійде — помилку побачить крок лаби
                    continue;
                }
                throw e;
            }
        }
    }

    private static String clip(String s) {
        return s.length() <= 90 ? s : s.substring(0, 89) + "…";
    }

    private static LlmResponse parseMessage(JsonNode resp, long ms) {
        StringBuilder text = new StringBuilder();
        for (JsonNode block : resp.path("content")) {
            if ("text".equals(block.path("type").asText())) text.append(block.path("text").asText());
        }
        JsonNode usage = resp.path("usage");
        String stop = resp.path("stop_reason").asText();
        return new LlmResponse(
                resp.path("id").asText(),
                resp.path("model").asText(),
                text.toString(),
                stop,
                stop,
                usage.path("input_tokens").asInt(),
                usage.path("output_tokens").asInt(),
                0,
                usage.path("cache_read_input_tokens").asInt(),
                usage.path("cache_creation_input_tokens").asInt(),
                ms,
                resp);
    }

    /** POST /v1/messages/count_tokens: у число входить кілька службових токенів обгортки повідомлення. */
    @Override
    public int countTokens(String system, String user) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", config.model());
        if (system != null && !system.isBlank()) body.put("system", system);
        body.putArray("messages").addObject().put("role", "user").put("content", user);
        return http.post(baseUrl + "/v1/messages/count_tokens", headers(), body).path("input_tokens").asInt();
    }

    private Map<String, String> headers() {
        return Map.of("x-api-key", config.apiKey(), "anthropic-version", API_VERSION);
    }
}
