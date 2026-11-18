package ua.knu.promptlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ua.knu.promptlab.mock.FakeLlmServer;

import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Клієнт Gemini API (models.generateContent / models.countTokens) без SDK.
 * Ключ з Google AI Studio іде в заголовку x-goog-api-key.
 *
 * Відмінності від Claude, які варто показати студентам:
 *  - ліміт вихідних токенів (maxOutputTokens) необов'язковий і лежить у generationConfig;
 *  - system prompt називається systemInstruction, повідомлення — contents[].parts[];
 *  - модель «міркує» перед відповіддю, і ці токени (thoughtsTokenCount) входять у вихідні й у ліміт.
 */
public final class GeminiClient implements LlmClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Config config;
    private final String baseUrl;
    // безкоштовний рівень має ліміти на хвилину → довші паузи між повторами
    private final HttpJson http = new HttpJson(4, 4000);
    // деякі моделі (напр. 3.8) відповідають 400 «Developer instruction is not enabled» на systemInstruction;
    // тоді system prompt вставляємо на початок повідомлення користувача
    private volatile boolean systemInstructionSupported = true;
    // якщо модель/версія API не приймає responseJsonSchema — переходимо на старіший responseSchema
    private volatile boolean jsonSchemaFieldSupported = true;
    // Gemini 3 temperature приймає (Google радить лишати 1.0), але якщо модель відповість 400 про temperature —
    // повторюємо без неї і до кінця прогону не надсилаємо (як у ClaudeClient)
    private volatile boolean temperatureSupported = true;

    public GeminiClient(Config config, String baseUrl) {
        this.config = config;
        this.baseUrl = baseUrl;
    }

    @Override
    public String provider() {
        return "Gemini";
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
    public boolean hasFreeTier() {
        return true;
    }

    @Override
    public long requestDelayMs() {
        return config.delayMs();
    }

    @Override
    public String endpointDescription() {
        return "POST " + baseUrl + "/v1beta/models/" + modelId() + ":generateContent"
                + "  (ключ іде в заголовку x-goog-api-key, не в тілі)";
    }

    @Override
    public void close() {
        if (config.mock()) FakeLlmServer.stop();
    }

    @Override
    public ObjectNode buildRequest(String system, String user, Double temperature, int maxTokens) {
        return buildRequest(system, user, temperature, maxTokens, CallOptions.Effort.DEFAULT);
    }

    private ObjectNode buildRequest(String system, String user, Double temperature, int maxTokens, CallOptions.Effort effort) {
        ObjectNode body = JSON.createObjectNode();
        boolean hasSystem = system != null && !system.isBlank();
        if (hasSystem && systemInstructionSupported) {
            body.putObject("systemInstruction").putArray("parts").addObject().put("text", system);
        }
        ObjectNode content = body.putArray("contents").addObject();
        content.put("role", "user");
        String userText = hasSystem && !systemInstructionSupported ? system + "\n\n" + user : user;
        content.putArray("parts").addObject().put("text", userText);

        ObjectNode gen = body.putObject("generationConfig");
        gen.put("maxOutputTokens", maxTokens);
        if (temperature != null && temperatureSupported) gen.put("temperature", temperature);
        ObjectNode tc = thinkingConfig(effort);
        if (tc != null) gen.set("thinkingConfig", tc);
        return body;
    }

    // ------------------------------------------------------------------ лабораторна 3: рівень міркування (effort)

    /**
     * thinkingConfig для рівня міркування. Параметр залежить від ПОКОЛІННЯ моделі (станом на 11.2026 — звіряйте документацію):
     * <ul>
     *   <li>Gemini 3+ — {@code thinkingLevel}: "minimal" | "low" | "medium" | "high" (не всі моделі приймають усі рівні:
     *       3.1 Pro — без minimal; minimal — не гарантовано 0 токенів міркування);</li>
     *   <li>Gemini 2.x — {@code thinkingBudget}: токени на міркування (MINIMAL → 0, LOW → 1024, MEDIUM → 2048, HIGH → 4096
     *       або LAB_THINKING_BUDGET).</li>
     * </ul>
     * Явний LAB_THINKING_BUDGET у .env примушує використовувати thinkingBudget для будь-якої моделі.
     * DEFAULT — поведінка лабораторних 1–2: лише LAB_THINKING_LEVEL, якщо задано.
     */
    ObjectNode thinkingConfig(CallOptions.Effort effort) {
        if (effort == CallOptions.Effort.DEFAULT || effort == null) {
            if (config.thinkingLevel() == null || config.thinkingLevel().isBlank()) return null;
            ObjectNode tc = JSON.createObjectNode();
            tc.put("thinkingLevel", config.thinkingLevel());
            return tc;
        }
        if (effort == CallOptions.Effort.MINIMAL && !thinkingOffSupported) return null;
        ObjectNode tc = JSON.createObjectNode();
        if (usesThinkingBudget()) {
            tc.put("thinkingBudget", thinkingBudget(effort));
        } else {
            tc.put("thinkingLevel", effort.apiName());
        }
        return tc;
    }

    /** true → thinkingBudget (Gemini 1.x/2.x або явний LAB_THINKING_BUDGET); false → thinkingLevel (Gemini 3+). */
    boolean usesThinkingBudget() {
        return config.thinkingBudget() != null || modelId().matches("gemini-[12](\\..*)?(-.*)?");
    }

    /** Бюджет у токенах для Gemini 2.x: MINIMAL 0, LOW 1024, MEDIUM 2048, HIGH 4096 (або LAB_THINKING_BUDGET). */
    int thinkingBudget(CallOptions.Effort effort) {
        return switch (effort) {
            case MINIMAL -> 0;
            case LOW -> 1024;
            case MEDIUM -> 2048;
            default -> config.thinkingBudget() != null && config.thinkingBudget() > 0 ? config.thinkingBudget() : 4096;
        };
    }

    @Override
    public String effortParameter(CallOptions.Effort effort) {
        ObjectNode tc = thinkingConfig(effort);
        String sent = tc == null ? "thinkingConfig не надсилається (міркування — як у моделі за замовчуванням)"
                : "generationConfig.thinkingConfig = " + tc;
        String why = usesThinkingBudget()
                ? (config.thinkingBudget() != null ? "  [thinkingBudget, бо задано LAB_THINKING_BUDGET]" : "  [thinkingBudget — так у Gemini 2.x]")
                : "  [thinkingLevel — так у Gemini 3+]";
        return sent + (tc == null ? "" : why);
    }

    @Override
    public boolean temperatureSupported() {
        return temperatureSupported;
    }

    // Gemini 3.1 Pro не приймає "minimal", Gemini 2.5 Pro — thinkingBudget = 0. Тоді «мінімальне» = без thinkingConfig.
    private volatile boolean thinkingOffSupported = true;

    /** Чи прийняла модель мінімальне міркування (false → після 400 надсилаємо без thinkingConfig). */
    public boolean thinkingOffSupported() {
        return thinkingOffSupported;
    }

    @Override
    public LlmResponse ask(String system, String user, Double temperature, int maxTokens) {
        long t0 = System.nanoTime();
        JsonNode resp = generate(system, temperature, () -> buildRequest(system, user, temperature, maxTokens));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return parse(resp, config.model(), ms);
    }

    // ------------------------------------------------------------------ лабораторна 2: структурований вивід

    @Override
    public ObjectNode buildJsonRequest(String system, String user, JsonNode jsonSchema, int maxTokens) {
        return buildJsonRequest(system, user, jsonSchema, maxTokens, CallOptions.DEFAULT);
    }

    @Override
    public ObjectNode buildJsonRequest(String system, String user, JsonNode jsonSchema, int maxTokens, CallOptions options) {
        ObjectNode body = buildRequest(system, user, options.temperature(), maxTokens, options.effort());
        ObjectNode gen = (ObjectNode) body.get("generationConfig");
        gen.put("responseMimeType", "application/json");
        if (jsonSchemaFieldSupported) {
            gen.set("responseJsonSchema", jsonSchema);
        } else {
            gen.set("responseSchema", toLegacySchema(jsonSchema));
        }
        return body;
    }

    @Override
    public LlmResponse askJson(String system, String user, JsonNode jsonSchema, int maxTokens) {
        return askJson(system, user, jsonSchema, maxTokens, CallOptions.DEFAULT);
    }

    @Override
    public LlmResponse askJson(String system, String user, JsonNode jsonSchema, int maxTokens, CallOptions options) {
        long t0 = System.nanoTime();
        JsonNode resp;
        try {
            resp = postJsonWithThinkingFallback(system, user, jsonSchema, maxTokens, options);
        } catch (ApiException e) {
            if (!(jsonSchemaFieldSupported && isSchemaFieldRejected(e))) throw e;
            System.err.println("  [API не прийняв responseJsonSchema (HTTP " + e.status() + ") — повторюємо зі старішим полем responseSchema]");
            jsonSchemaFieldSupported = false;
            resp = postJsonWithThinkingFallback(system, user, jsonSchema, maxTokens, options);
            System.err.println("  [схему відправлено в полі " + schemaFieldInUse() + "; далі використовується лише воно]");
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return parse(resp, config.model(), ms);
    }

    /**
     * Якщо модель відхилила МІНІМАЛЬНЕ міркування (400 з thinking у тексті) — повторюємо без thinkingConfig і пишемо про це.
     * Для low / medium / high фолбеку НЕМАЄ: інакше крок 4 мовчки виміряв би те саме, що базова лінія. Помилку побачить крок лаби.
     */
    private JsonNode postJsonWithThinkingFallback(String system, String user, JsonNode jsonSchema, int maxTokens, CallOptions options) {
        Supplier<JsonNode> call = () -> generate(system, options.temperature(),
                () -> buildJsonRequest(system, user, jsonSchema, maxTokens, options));
        try {
            return call.get();
        } catch (ApiException e) {
            boolean offRejected = options.effort() == CallOptions.Effort.MINIMAL && thinkingOffSupported && e.status() == 400
                    && e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT).contains("thinking");
            if (!offRejected) throw e;
            System.err.println("  [" + config.model() + " не приймає мінімальне міркування (" + thinkingConfig(CallOptions.Effort.MINIMAL)
                    + ") — далі «мінімальне» = без thinkingConfig, тобто як у моделі за замовчуванням; дивіться токени міркування]");
            thinkingOffSupported = false;
            return call.get();
        }
    }

    /**
     * POST generateContent з двома фолбеками, кожен — один раз за прогін і з повідомленням у консолі:
     * модель не приймає systemInstruction → system prompt іде в повідомлення користувача;
     * модель не приймає temperature → запит без неї (значення моделі за замовчуванням).
     */
    private JsonNode generate(String system, Double temperature, Supplier<ObjectNode> body) {
        for (int attempt = 0; ; attempt++) {
            try {
                return http.post(baseUrl + "/v1beta/models/" + modelId() + ":generateContent", headers(), body.get());
            } catch (ApiException e) {
                if (attempt < 2 && isSystemRejected(e, system)) {
                    noSystemInstruction();
                    continue;
                }
                if (attempt < 2 && isTemperatureRejected(e, temperature)) {
                    System.err.println("  [" + config.model() + " не приймає temperature — далі запити без неї "
                            + "(значення моделі за замовчуванням)]");
                    temperatureSupported = false;
                    continue;
                }
                throw e;
            }
        }
    }

    private boolean isTemperatureRejected(ApiException e, Double temperature) {
        return e.status() == 400 && temperature != null && temperatureSupported
                && e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT).contains("temperature");
    }

    private boolean isSystemRejected(ApiException e, String system) {
        return e.status() == 400 && systemInstructionSupported && system != null && !system.isBlank()
                && e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT).contains("instruction is not enabled");
    }

    private void noSystemInstruction() {
        System.err.println("  [" + config.model() + " не підтримує systemInstruction — "
                + "system prompt додається до повідомлення користувача]");
        systemInstructionSupported = false;
    }

    /** Яке поле реально несе схему в запитах askJson: responseJsonSchema або (після фолбеку) responseSchema. */
    public String schemaFieldInUse() {
        return jsonSchemaFieldSupported ? "responseJsonSchema" : "responseSchema";
    }

    /**
     * Чи схоже, що API відхилив саме поле responseJsonSchema (а не, скажімо, ключ чи модель).
     * Формулювання помилок у різних версій API різні, тому перевірка широка й нечутлива до регістру.
     */
    static boolean isSchemaFieldRejected(ApiException e) {
        if (e.status() != 400 || e.getMessage() == null) return false;
        String m = e.getMessage().toLowerCase(Locale.ROOT);
        return m.contains("responsejsonschema") || m.contains("unknown name") || m.contains("json_schema")
                || m.contains("invalid json payload");
    }

    /**
     * Старіше поле responseSchema приймає OpenAPI-підмножину: без additionalProperties, $ref і $defs.
     * І ще одна відмінність: у responseSchema порядок полів НЕ береться з порядку properties — його задає окремий
     * масив propertyOrdering. Без нього модель може згенерувати мітки раніше за reasoning (лабораторна 3, крок 4),
     * і тоді «міркування перед міткою» вже ні на що не впливає. Тому для кожного об'єкта додаємо propertyOrdering
     * у тому самому порядку, що й properties.
     */
    static JsonNode toLegacySchema(JsonNode schema) {
        JsonNode copy = schema.deepCopy();
        stripKeys(copy, "additionalProperties", "$ref", "$defs", "$schema");
        addPropertyOrdering(copy);
        return copy;
    }

    private static void addPropertyOrdering(JsonNode node) {
        if (node.isObject()) {
            ObjectNode o = (ObjectNode) node;
            JsonNode props = o.get("properties");
            if (props != null && props.isObject() && !o.has("propertyOrdering")) {
                var order = o.putArray("propertyOrdering");
                props.fieldNames().forEachRemaining(order::add);
            }
            o.elements().forEachRemaining(GeminiClient::addPropertyOrdering);
        } else if (node.isArray()) {
            node.forEach(GeminiClient::addPropertyOrdering);
        }
    }

    private static void stripKeys(JsonNode node, String... keys) {
        if (node.isObject()) {
            ObjectNode o = (ObjectNode) node;
            for (String k : keys) o.remove(k);
            o.fields().forEachRemaining(e -> stripKeys(e.getValue(), keys));
        } else if (node.isArray()) {
            node.forEach(n -> stripKeys(n, keys));
        }
    }

    /** Виконує запит; якщо модель не приймає systemInstruction — вимикає його й повторює один раз. */
    private JsonNode withSystemFallback(String system, Supplier<JsonNode> call) {
        try {
            return call.get();
        } catch (ApiException e) {
            if (!isSystemRejected(e, system)) throw e;
            noSystemInstruction();
            return call.get();
        }
    }

    /** Розбір відповіді generateContent. Окремо й без мережі, щоб це можна було протестувати. */
    static LlmResponse parse(JsonNode resp, String requestedModel, long latencyMs) {
        JsonNode candidate = resp.path("candidates").path(0);

        StringBuilder text = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts")) {
            boolean thought = part.path("thought").asBoolean(false);
            if (!thought && part.has("text")) text.append(part.path("text").asText());
        }

        String raw = candidate.path("finishReason").asText("");
        if (raw.isEmpty() && resp.path("promptFeedback").has("blockReason")) {
            raw = "BLOCKED:" + resp.path("promptFeedback").path("blockReason").asText();
        }
        String stop = switch (raw) {
            case "STOP" -> "end_turn";
            case "MAX_TOKENS" -> "max_tokens";
            default -> raw.isEmpty() ? "unknown" : raw.toLowerCase();
        };

        JsonNode usage = resp.path("usageMetadata");
        int prompt = usage.path("promptTokenCount").asInt();
        int cached = usage.path("cachedContentTokenCount").asInt();
        int candidates = usage.path("candidatesTokenCount").asInt();
        int thoughts = usage.path("thoughtsTokenCount").asInt();

        return new LlmResponse(
                resp.path("responseId").asText(""),
                resp.path("modelVersion").asText(requestedModel),
                text.toString(),
                stop,
                raw,
                Math.max(0, prompt - cached),
                candidates + thoughts,
                thoughts,
                cached,
                0,
                latencyMs,
                resp);
    }

    /**
     * POST models/{model}:countTokens. Без system prompt — просто contents;
     * із system prompt — через generateContentRequest (так він теж потрапляє в підрахунок).
     */
    @Override
    public int countTokens(String system, String user) {
        return withSystemFallback(system, () -> {
            ObjectNode body = JSON.createObjectNode();
            ObjectNode request = buildRequest(system, user, null, 1);
            request.remove("generationConfig");
            if (!request.has("systemInstruction")) {
                body.set("contents", request.get("contents"));
            } else {
                request.put("model", "models/" + modelId());
                body.set("generateContentRequest", request);
            }
            return http.post(baseUrl + "/v1beta/models/" + modelId() + ":countTokens", headers(), body);
        }).path("totalTokens").asInt();
    }

    private String modelId() {
        String m = config.model();
        return m.startsWith("models/") ? m.substring("models/".length()) : m;
    }

    private Map<String, String> headers() {
        return Map.of("x-goog-api-key", config.apiKey());
    }
}
