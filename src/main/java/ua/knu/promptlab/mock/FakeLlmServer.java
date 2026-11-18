package ua.knu.promptlab.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ua.knu.promptlab.lab2.Lab2Variant;
import ua.knu.promptlab.lab3.Lab3Prompts;
import ua.knu.promptlab.lab3.Variant;
import ua.knu.promptlab.steps.Lab1Variant;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Локальна ІМІТАЦІЯ API Claude і Gemini — лише для демонстрації без ключа і для тестів.
 *
 * УВАГА: тексти відповідей — шаблонні, а кількість токенів — груба евристика
 * (кирилиця ≈ 2.2 символа на токен, решта ≈ 4). Це НЕ справжній токенізатор.
 * Реальні числа студенти бачать тільки під час запуску з справжнім ключем.
 */
public final class FakeLlmServer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static FakeLlmServer instance;

    private final HttpServer server;
    private final AtomicInteger counter = new AtomicInteger();
    private final AtomicInteger coffeeAtZero = new AtomicInteger();
    private final Random random = new Random();

    /**
     * Лише для тестів: імітувати версію Gemini API, яка не знає поля responseJsonSchema
     * (400 «Invalid JSON payload… Unknown name») — щоб перевірити перехід клієнта на responseSchema.
     */
    public static volatile boolean rejectResponseJsonSchema = false;

    /**
     * Покоління імітованого Claude: 5 — Claude 5.x (thinking adaptive / between_tools + output_config.effort; enabled + budget_tokens
     * і disabled → 400), 4 — Claude 4.x / Haiku 4.5 (effort, adaptive і between_tools → 400; enabled + budget_tokens).
     * temperature імітація відхиляє лише з {@link #noTemperature} (справжній Sonnet 5.5 відхиляє її завжди) — щоб еталон MOCK
     * лабораторних 1–2 не змінився.
     * null → LAB_MOCK_CLAUDE_GEN=4|5 з .env / середовища, а якщо не задано — за назвою моделі (claude-…-4-…, claude-3-… → 4, інакше 5).
     * Поле — для тестів.
     */
    public static volatile Integer claudeGeneration = null;

    /** true → імітація обох провайдерів відповідає 400 на будь-яку temperature. null → LAB_MOCK_NO_TEMPERATURE=1. Поле — для тестів. */
    public static volatile Boolean noTemperature = null;

    /** Покоління Claude для цієї моделі (див. {@link #claudeGeneration}). */
    public static int claudeGeneration(String model) {
        Integer forced = claudeGeneration;
        if (forced != null) return forced;
        String setting = ua.knu.promptlab.Config.setting("LAB_MOCK_CLAUDE_GEN");
        if (setting != null) {
            if (setting.strip().equals("4")) return 4;
            if (setting.strip().equals("5")) return 5;
        }
        return model != null && model.matches("claude-(\\w+-)?[1-4]([-.].*)?") ? 4 : 5;
    }

    private static boolean noTemperature() {
        Boolean forced = noTemperature;
        return forced != null ? forced : ua.knu.promptlab.Config.isTrueSetting("LAB_MOCK_NO_TEMPERATURE");
    }

    /** Рівень міркування, який «побачила» імітація: 0 — мінімальне, 1 — low, 2 — medium, 3 — high. */
    static final int MINIMAL = 0, LOW = 1, MEDIUM = 2, HIGH = 3;

    /** Ключі схеми, на які справжній Claude structured outputs відповідає 400 (див. ClaudeClient.toClaudeSchema). */
    private static final List<String> CLAUDE_UNSUPPORTED = List.of(
            "maxItems", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "minLength", "maxLength");

    /**
     * Лабораторна 1, крок 5: при temperature = 0 модель теж НЕ завжди дає одне й те саме — частіше ті самі слова
     * в іншому оформленні (лапки, крапка, **жирний**), іноді інша назва. Імітуємо це детерміновано по колу з 10.
     */
    private static final List<String> COFFEE_AT_ZERO = List.of(
            "Кава на паузі", "Кава на паузі", "«Кава на паузі»", "Кава на паузі", "Кава на паузі.",
            "Кава на паузі", "Кава на паузі", "Читальна зала", "Кава на паузі", "**Кава на паузі**");

    private static final List<String> COFFEE_NAMES = List.of(
            "Кава на паузі", "Читальна зала", "Лекторій", "Еспресо-сесія", "Залікова книжка",
            "Перерва", "Кофеїнова кафедра", "Другий семестр", "Конспект", "Бібліотечна кава");

    private FakeLlmServer() throws IOException {
        // без TCP_NODELAY кожна відповідь чекає ~40 мс (затримка ACK) — імітація лабораторної 3 (≈75 запитів) була б удвічі повільнішою
        if (System.getProperty("sun.net.httpserver.nodelay") == null) System.setProperty("sun.net.httpserver.nodelay", "true");
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/messages/count_tokens", ex -> claude(ex, true));
        server.createContext("/v1/messages", ex -> claude(ex, false));
        server.createContext("/v1beta/models/", this::gemini);
        server.setExecutor(null);
        server.start();
    }

    public static synchronized FakeLlmServer startOnce() {
        if (instance == null) {
            try {
                instance = new FakeLlmServer();
            } catch (IOException e) {
                throw new IllegalStateException("Не вдалося запустити mock-сервер", e);
            }
        }
        return instance;
    }

    /** Зупиняє сервер (потік HTTP-Dispatcher не daemon, інакше JVM не завершиться). */
    public static synchronized void stop() {
        if (instance != null) {
            instance.server.stop(0);
            instance = null;
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // ------------------------------------------------------------------ Claude

    private void claude(HttpExchange ex, boolean countOnly) throws IOException {
        try (ex) {
            if (!"POST".equals(ex.getRequestMethod())) {
                send(ex, 405, claudeError("invalid_request_error", "method not allowed"));
                return;
            }
            String key = ex.getRequestHeaders().getFirst("x-api-key");
            if (key == null || key.equals("invalid")) {
                send(ex, 401, claudeError("authentication_error", "invalid x-api-key"));
                return;
            }
            JsonNode req = JSON.readTree(ex.getRequestBody());
            String model = req.path("model").asText("claude-sonnet-5-5");
            String system = req.path("system").asText("");
            String user = req.path("messages").path(0).path("content").asText("");
            int inputTokens = estimate(system) + estimate(user) + 8; // +8 «службових» токенів

            if (countOnly) {
                ObjectNode out = JSON.createObjectNode();
                out.put("input_tokens", inputTokens);
                send(ex, 200, out);
                return;
            }

            int maxTokens = req.path("max_tokens").asInt(1024);
            Double temperature = req.has("temperature") ? req.get("temperature").asDouble() : null;
            int gen = claudeGeneration(model);
            if (temperature != null && noTemperature()) {
                send(ex, 400, claudeError("invalid_request_error", "temperature is deprecated for this model and is not supported. Remove it from the request."));
                return;
            }
            String thinkingType = req.path("thinking").path("type").asText("");
            String effort = req.path("output_config").path("effort").asText("");
            String bad = claudeThinkingError(gen, thinkingType, effort, req.path("thinking").path("budget_tokens").asInt(0), maxTokens);
            if (bad != null) {
                send(ex, 400, claudeError("invalid_request_error", bad));
                return;
            }
            int level = gen >= 5
                    ? (thinkingType.equals("adaptive") ? levelOf(effort.isEmpty() ? "high" : effort) : MINIMAL) // between_tools — найнижче
                    : (thinkingType.equals("enabled") ? budgetLevel(req.path("thinking").path("budget_tokens").asInt(0)) : MINIMAL);
            boolean structured = req.path("output_config").path("format").has("schema");
            if (structured) {
                String badKey = findUnsupported(req.path("output_config").path("format").path("schema"));
                if (badKey != null) {
                    send(ex, 400, claudeError("invalid_request_error",
                            "output_config.format.schema: keyword '" + badKey + "' is not supported"));
                    return;
                }
            }
            JsonNode schema = req.path("output_config").path("format").path("schema");
            Lab3Reply lab3 = structured && isLab3(schema)
                    ? lab3(model, "Claude", system, user, schema.path("properties").has("reasoning"), level, req.toString())
                    : structured && FakeLab4.isLab4(schema) ? lab4(user, schema, level) : null;
            String text = lab3 != null ? lab3.json() : structured ? structuredJson(schema, user) : reply(system, user, temperature);

            String stop = "end_turn";
            int outTokens = estimate(text);
            if (outTokens > maxTokens) {
                text = truncate(text, maxTokens);
                outTokens = maxTokens;
                stop = "max_tokens";
            }
            int thinkingTokens = lab3 == null ? 0 : lab3.thinkingTokens();
            if (lab3 != null) simulateLatency(outTokens + thinkingTokens);

            ObjectNode out = JSON.createObjectNode();
            out.put("id", "msg_mock_" + counter.incrementAndGet());
            out.put("type", "message");
            out.put("role", "assistant");
            out.put("model", model);
            var contentArr = out.putArray("content");
            if (thinkingTokens > 0) {
                // Claude повертає міркування окремим блоком (4.x — скорочений текст; 5.x за замовчуванням без тексту);
                // токени міркування входять в output_tokens. Клієнт бере лише блоки text.
                contentArr.addObject().put("type", "thinking").put("thinking", gen >= 5 ? "" : lab3.thinkingText()).put("signature", "mock-signature");
            }
            contentArr.addObject().put("type", "text").put("text", text);
            out.put("stop_reason", stop);
            out.putNull("stop_sequence");
            ObjectNode usage = out.putObject("usage");
            usage.put("input_tokens", inputTokens);
            usage.put("output_tokens", outTokens + thinkingTokens);
            usage.put("cache_creation_input_tokens", 0);
            usage.put("cache_read_input_tokens", 0);
            send(ex, 200, out);
        }
    }

    // ------------------------------------------------------------------ Gemini

    private void gemini(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath(); // /v1beta/models/<model>:generateContent
            boolean countOnly = path.endsWith(":countTokens");
            boolean generate = path.endsWith(":generateContent");
            boolean embed = path.endsWith(":batchEmbedContents") || path.endsWith(":embedContent");
            if ("POST".equals(ex.getRequestMethod()) && embed) { // лабораторна 4: ембединги (імітація — «мішок слів»)
                String k = ex.getRequestHeaders().getFirst("x-goog-api-key");
                if (k == null || k.equals("invalid")) {
                    send(ex, 400, geminiError(400, "INVALID_ARGUMENT", "API key not valid. Please pass a valid API key."));
                    return;
                }
                embedCalls.incrementAndGet();
                if (embedDailyLimit) {
                    send(ex, 429, geminiQuota("You exceeded your current quota. quota_id: EmbedContentRequestsPerDayPerUserPerProjectPerModel-FreeTier", null));
                    return;
                }
                if (embed429 > 0) {
                    embed429--;
                    send(ex, 429, geminiQuota("Resource has been exhausted (e.g. check quota). quota_id: EmbedContentInputTokensPerMinutePerProjectPerModel-FreeTier", "1s"));
                    return;
                }
                send(ex, 200, FakeLab4.embed(JSON.readTree(ex.getRequestBody()), path.endsWith(":batchEmbedContents")));
                return;
            }
            if (!"POST".equals(ex.getRequestMethod()) || !(countOnly || generate)) {
                send(ex, 404, geminiError(404, "NOT_FOUND", "unknown method"));
                return;
            }
            String key = ex.getRequestHeaders().getFirst("x-goog-api-key");
            if (key == null || key.equals("invalid")) {
                send(ex, 400, geminiError(400, "INVALID_ARGUMENT", "API key not valid. Please pass a valid API key."));
                return;
            }
            String model = path.substring("/v1beta/models/".length(), path.lastIndexOf(':'));
            JsonNode req = JSON.readTree(ex.getRequestBody());
            JsonNode inner = req.has("generateContentRequest") ? req.get("generateContentRequest") : req;

            String system = inner.path("systemInstruction").path("parts").path(0).path("text").asText("");
            String user = inner.path("contents").path(0).path("parts").path(0).path("text").asText("");
            int inputTokens = estimate(system) + estimate(user);

            if (countOnly) {
                ObjectNode out = JSON.createObjectNode();
                out.put("totalTokens", inputTokens);
                send(ex, 200, out);
                return;
            }

            JsonNode gen = req.path("generationConfig");
            int maxTokens = gen.path("maxOutputTokens").asInt(8192);
            Double temperature = gen.has("temperature") ? gen.get("temperature").asDouble() : null;
            if (temperature != null && noTemperature()) {
                send(ex, 400, geminiError(400, "INVALID_ARGUMENT", "temperature is not supported for this model."));
                return;
            }
            boolean structured = "application/json".equals(gen.path("responseMimeType").asText(""));
            if (structured && rejectResponseJsonSchema && gen.has("responseJsonSchema")) {
                send(ex, 400, geminiError(400, "INVALID_ARGUMENT",
                        "Invalid JSON payload received. Unknown name \"responseJsonSchema\" at 'generation_config': Cannot find field."));
                return;
            }
            JsonNode schema = gen.has("responseJsonSchema") ? gen.path("responseJsonSchema") : gen.path("responseSchema");
            JsonNode tc = gen.path("thinkingConfig");
            // без thinkingConfig — як у Flash-Lite за замовчуванням: мінімальне міркування
            int level = tc.has("thinkingLevel") ? levelOf(tc.path("thinkingLevel").asText("")) : budgetLevel(tc.path("thinkingBudget").asInt(0));
            Lab3Reply lab3 = structured && isLab3(schema)
                    ? lab3(model, "Gemini", system, user, schema.path("properties").has("reasoning"), level, model + req)
                    : structured && FakeLab4.isLab4(schema) ? lab4(user, schema, level) : null;
            String text = lab3 != null ? lab3.json() : structured ? structuredJson(schema, user) : reply(system, user, temperature);

            String finish = "STOP";
            int thoughts = lab3 == null ? 0 : lab3.thinkingTokens();
            int outTokens = estimate(text);
            if (outTokens + thoughts > maxTokens) { // у Gemini міркування входить у той самий ліміт
                text = truncate(text, Math.max(0, maxTokens - thoughts));
                outTokens = Math.max(0, maxTokens - thoughts);
                finish = "MAX_TOKENS";
            }
            if (lab3 != null) simulateLatency(outTokens + thoughts);

            ObjectNode out = JSON.createObjectNode();
            ObjectNode candidate = out.putArray("candidates").addObject();
            ObjectNode content = candidate.putObject("content");
            content.put("role", "model");
            content.putArray("parts").addObject().put("text", text);
            candidate.put("finishReason", finish);
            candidate.put("index", 0);
            ObjectNode usage = out.putObject("usageMetadata");
            usage.put("promptTokenCount", inputTokens);
            usage.put("candidatesTokenCount", outTokens);
            if (thoughts > 0) usage.put("thoughtsTokenCount", thoughts);
            usage.put("totalTokenCount", inputTokens + outTokens + thoughts);
            out.put("modelVersion", model);
            out.put("responseId", "resp_mock_" + counter.incrementAndGet());
            send(ex, 200, out);
        }
    }

    // ------------------------------------------------------------------ лабораторна 4: пошук по документах

    /** Лише для тестів: скільки наступних запитів ембедингів відповісти 429 з retryDelay «1s» (хвилинний ліміт). */
    public static volatile int embed429 = 0;
    /** Лише для тестів: відповідати на ембединги 429 «добовий ліміт». */
    public static volatile boolean embedDailyLimit = false;

    /** Тіло 429 у форматі Gemini: повідомлення, QuotaFailure і (якщо задано) RetryInfo.retryDelay. */
    private static ObjectNode geminiQuota(String message, String retryDelay) {
        ObjectNode out = geminiError(429, "RESOURCE_EXHAUSTED", message);
        var details = ((ObjectNode) out.get("error")).putArray("details");
        details.addObject().put("@type", "type.googleapis.com/google.rpc.QuotaFailure").putArray("violations").addObject()
                .put("quotaId", message.substring(message.indexOf("quota_id: ") + 10));
        if (retryDelay != null) details.addObject().put("@type", "type.googleapis.com/google.rpc.RetryInfo").put("retryDelay", retryDelay);
        return out;
    }

    /** Скільки запитів ембедингів отримала імітація (для тестів). */
    public final AtomicInteger embedCalls = new AtomicInteger();

    /** Відповідь лабораторної 4 (див. {@link FakeLab4}) у форматі Lab3Reply: JSON і токени міркування. */
    private static Lab3Reply lab4(String user, JsonNode schema, int level) {
        FakeLab4.Reply r = FakeLab4.reply(user, schema, level);
        return new Lab3Reply(r.json(), r.thinkingTokens(), "Шукаю в уривках пункт, що відповідає на питання.");
    }

    // ------------------------------------------------------------------ лабораторна 3: класифікатор звернень

    /** Відповідь імітації для лабораторної 3: JSON міток і (якщо thinking увімкнено) «міркування». */
    record Lab3Reply(String json, int thinkingTokens, String thinkingText) {
    }

    /** Скільки разів уже приходив ТОЧНО такий самий запит — номер вибірки для self-consistency. */
    private final Map<String, Integer> seenRequests = new ConcurrentHashMap<>();
    private static volatile List<Variant> variants;

    /** Схема тікета (лабораторна 2, TicketSchema) — шаблонний тікет; будь-яка інша схема (MySchema) — приклад за схемою. */
    private String structuredJson(JsonNode schema, String user) {
        JsonNode props = schema.path("properties");
        if (props.isMissingNode() || (props.has("priority") && props.has("steps"))) return ticketJson(user, true);
        return exampleFor(schema, user).toString();
    }

    /**
     * Найпростіший екземпляр за схемою (для MySchema у режимі MOCK): enum — перше значення, масив — один елемент,
     * рядок — коротке резюме входу, число — 1, boolean — false. Тип читається без урахування регістру (responseSchema Gemini — STRING, OBJECT…).
     */
    static JsonNode exampleFor(JsonNode schema, String user) {
        if (schema.has("enum") && schema.path("enum").size() > 0) return schema.path("enum").get(0);
        String type = schema.path("type").asText("string").toLowerCase(Locale.ROOT);
        switch (type) {
            case "object" -> {
                ObjectNode o = JSON.createObjectNode();
                schema.path("properties").fields().forEachRemaining(e -> o.set(e.getKey(), exampleFor(e.getValue(), user)));
                return o;
            }
            case "array" -> {
                com.fasterxml.jackson.databind.node.ArrayNode a = JSON.createArrayNode();
                a.add(exampleFor(schema.path("items"), user));
                return a;
            }
            case "integer", "number" -> {
                return JSON.getNodeFactory().numberNode(1);
            }
            case "boolean" -> {
                return JSON.getNodeFactory().booleanNode(false);
            }
            default -> {
                String u = user == null ? "" : user.strip().replaceAll("\\s+", " ");
                return JSON.getNodeFactory().textNode("Імітація: " + (u.length() > 60 ? u.substring(0, 60) + "…" : u));
            }
        }
    }

    private static boolean isLab3(JsonNode schema) {
        return schema.path("properties").has("needs_human");
    }

    private static List<Variant> variants() {
        if (variants == null) variants = Variant.loadAll();
        return variants;
    }

    /** Приклад few-shot, розібраний із системного промпта: текст і мітки з рядка «Відповідь: {…}». */
    record ParsedExample(String text, String category, String urgency, boolean needsHuman) {
    }

    private static final Pattern EXAMPLE = Pattern.compile("Звернення: \"\"\"(.*?)\"\"\"\\nВідповідь: (\\{[^\\n]*\\})", Pattern.DOTALL);

    static List<ParsedExample> parseExamples(String system) {
        List<ParsedExample> out = new ArrayList<>();
        Matcher m = EXAMPLE.matcher(system);
        while (m.find()) {
            try {
                JsonNode a = JSON.readTree(m.group(2));
                out.add(new ParsedExample(m.group(1), a.path("category").asText(""), a.path("urgency").asText(""), a.path("needs_human").asBoolean()));
            } catch (IOException e) {
                // зіпсований приклад імітація просто пропускає
            }
        }
        return out;
    }

    /** Текст додаткового правила (крок 3) або "" — рядок, що починається з Lab3Prompts.RULE_PREFIX. */
    static String parseRule(String system) {
        for (String line : system.split("\n")) if (line.startsWith(Lab3Prompts.RULE_PREFIX)) return line.substring(Lab3Prompts.RULE_PREFIX.length());
        return "";
    }

    /**
     * Імітація класифікатора звернень (лабораторна 3). Звернення знаходимо за текстом у даних варіантів і «помиляємось»
     * детерміновано, але правдоподібно. Імовірність помилки поля p:
     * <ul>
     *   <li>база: пастка «атакує» своє поле (trap_field) — 0.62, інші поля пастки — 0.14; категорія звернення на межі пари — 0.42,
     *       звичайного — 0.06; urgency / needs_human звичайного — 0.08;</li>
     *   <li>few-shot: × max(0.45, 1 − 0.16·k), де k — усі приклади в промпті; приклад варіанта саме на цій межі категорій — ще × 0.5;</li>
     *   <li>ВАШ приклад (крок 3), схожий на звернення (Жаккар ≥ 0.12): з правильною категорією — × 0.55 для категорії
     *       (і × 0.7 / × 1.3 для urgency та needs_human, якщо ці мітки збігаються / ні), з категорією-«дистрактором» — × 1.6 (приклад шкодить);</li>
     *   <li>ВАШЕ правило, що має спільні слова зі зверненням: називає правильне значення поля — × 0.5, лише неправильне — × 1.4;</li>
     *   <li>поле reasoning — × 0.75; effort low / medium / high — × 0.7 / 0.6 / 0.5 (і більше токенів міркування та затримка);
 *       з міркуванням шум свій, тож приріст буває не завжди, а зрідка відповідь навіть псується.</li>
     * </ul>
     * Помилка = u + шум &lt; p, де u — стала для звернення й поля (одна на всі техніки), а шум (±0.17) залежить від номера
     * однакового запиту, провайдера і ТЕКСТУ системного промпта. Тому два однакові прогони розходяться (шум), будь-яка зміна
     * промпта (приклади, правило, «кар'єрна» фраза) дає нову «жеребкувальну» серію, а приклади можуть і зіпсувати відповідь.
     * Помилкова категорія — distractor звернення. Числа різні для кожного варіанта й провайдера; до справжніх моделей стосунку не мають.
     */
    private Lab3Reply lab3(String model, String provider, String system, String user, boolean reasoning, int level, String requestKey) {
        int sample = seenRequests.merge(requestKey, 1, Integer::sum) - 1;
        String text = between(user, "\"\"\"");
        Variant variant = null;
        Variant.Inquiry q = null;
        for (Variant v : variants()) {
            Optional<Variant.Inquiry> hit = v.findByText(text);
            if (hit.isPresent()) {
                variant = v;
                q = hit.get();
                break;
            }
        }
        if (q == null) { // невідоме звернення (наприклад, ваше власне) — нейтральна відповідь
            return unknownInquiry(reasoning, level);
        }
        return classify(model, provider, system, q, variant, reasoning, level, sample);
    }

    private static Lab3Reply unknownInquiry(boolean reasoning, int level) {
        return new Lab3Reply(labelsJson(reasoning, "Звернення не з набору варіантів.", "other", "normal", false), level == MINIMAL ? 0 : 200 * level,
                "Звернення невідоме; обираю обережну відповідь.");
    }

    /** thinkingLevel / effort → рівень імітації (невідоме значення — мінімальне). */
    static int levelOf(String name) {
        return switch (name) {
            case "low" -> LOW;
            case "medium" -> MEDIUM;
            case "high", "xhigh", "max" -> HIGH;
            default -> MINIMAL;
        };
    }

    /** thinkingBudget / budget_tokens → рівень імітації: 0 — мінімальне, ≤ 1024 — low, ≤ 2048 — medium, більше — high. */
    static int budgetLevel(int budget) {
        return budget <= 0 ? MINIMAL : budget <= 1024 ? LOW : budget <= 2048 ? MEDIUM : HIGH;
    }

    /**
     * Що відповів би на параметри міркування справжній Claude цього покоління (текст 400-помилки) або null — усе гаразд.
     * Тексти близькі до справжніх (станом на 11.2026), але не дослівні.
     */
    static String claudeThinkingError(int gen, String type, String effort, int budget, int maxTokens) {
        if (gen >= 5) {
            if (type.equals("enabled") || type.equals("disabled"))
                return "thinking.type '" + type + "' is not supported for this model. Use thinking.type 'adaptive' with output_config.effort instead.";
            if (!effort.isEmpty() && !List.of("low", "medium", "high", "xhigh", "max").contains(effort))
                return "output_config.effort: Input should be 'low', 'medium', 'high', 'xhigh' or 'max'";
            if (type.equals("between_tools") && !effort.isEmpty() && !List.of("low", "medium", "high").contains(effort))
                return "thinking.type 'between_tools' supports only output_config.effort 'low', 'medium' or 'high'";
            return null;
        }
        if (!effort.isEmpty()) return "output_config.effort: Extra inputs are not permitted (effort is not supported for this model)";
        if (!type.isEmpty() && !type.equals("enabled") && !type.equals("disabled"))
            return "thinking.type: Input tag '" + type + "' found using 'type' does not match any of the expected tags: 'disabled', 'enabled'";
        if (type.equals("enabled") && budget < 1024) return "thinking.budget_tokens: Input should be greater than or equal to 1024";
        if (type.equals("enabled") && budget >= maxTokens) return "max_tokens must be greater than thinking.budget_tokens";
        return null;
    }

    private Lab3Reply classify(String model, String provider, String system, Variant.Inquiry q, Variant variant, boolean reasoning,
                               int level, int sample) {
        List<ParsedExample> examples = parseExamples(system);
        String rule = parseRule(system).toLowerCase(Locale.ROOT);
        String[] fields = {"category", "urgency", "needs_human"};
        double[] p = new double[3];
        for (int f = 0; f < 3; f++) p[f] = baseErrorRate(q, variant, fields[f]) * Math.max(0.45, 1 - 0.16 * examples.size());
        for (ParsedExample e : examples) {
            Optional<Variant.Example> own = variant.examples().stream().filter(x -> x.text().equals(e.text())).findFirst();
            if (own.isPresent()) { // приклад варіанта: допомагає, якщо показує саме цю межу
                List<String> pair = own.get().pair();
                if (pair != null && pair.contains(q.category()) && pair.contains(q.distractor())) p[0] *= 0.5;
                continue;
            }
            if (Variant.jaccard(e.text(), q.text()) < 0.12) continue; // ваш приклад «про інше» — на це звернення не впливає
            if (e.category().equals(q.category())) {
                p[0] *= 0.55;
                p[1] *= e.urgency().equals(q.urgency()) ? 0.7 : 1.3;
                p[2] *= e.needsHuman() == q.needsHuman() ? 0.7 : 1.3;
            } else if (e.category().equals(q.distractor())) {
                p[0] *= 1.6;
            }
        }
        if (!rule.isEmpty() && sharesStem(rule, q.text())) {
            p[0] *= ruleFactor(rule, List.of(q.category()), List.of(q.distractor()));
            p[1] *= ruleFactor(rule, List.of(q.urgency()), Variant.URGENCIES.stream().filter(x -> !x.equals(q.urgency())).toList());
            boolean humanWord = rule.contains("needs_human") || rule.contains("людин") || rule.contains("працівник");
            if (humanWord) p[2] *= ruleFactor(rule, List.of(String.valueOf(q.needsHuman())), List.of(String.valueOf(!q.needsHuman())));
        }
        if (reasoning) for (int f = 0; f < 3; f++) p[f] *= 0.75;
        double effortFactor = new double[]{1.0, 0.7, 0.6, 0.5}[level]; // вищий effort — менше помилок, але не всюди
        for (int f = 0; f < 3; f++) p[f] *= effortFactor;
        // міркування (поле reasoning чи effort) — інший «хід думки», тож і своя жеребкувальна серія; база — як раніше
        String promptKey = Integer.toHexString(system.hashCode()) + (reasoning || level > MINIMAL ? "|R" + reasoning + "|L" + level : "");
        boolean[] wrong = new boolean[3];
        for (int f = 0; f < 3; f++) {
            String id = q.id() + "|" + fields[f];
            double u = unit("u|" + id);
            double jitter = (unit("j|" + id + "|" + sample + "|" + provider + "|" + promptKey) - 0.5) * 0.34;
            wrong[f] = u + jitter < p[f];
        }
        String cat = wrong[0] ? q.distractor() : q.category();
        String urg = wrong[1] ? wrongUrgency(q) : q.urgency();
        boolean human = wrong[2] != q.needsHuman();
        String why = "Студент пише: «" + clipText(q.text(), 60) + "». Тема — " + cat + "; терміновість " + urg
                + (urg.equals("high") ? " (є строк або загроза)" : "") + "; " + (human ? "потрібен працівник." : "бот може відповісти сам.");
        // токени міркування ростуть з рівнем: low ≈ 150–400, medium ≈ 450–850, high ≈ 900–1600
        double t = unit("t|" + q.id() + "|" + sample + "|" + model + "|" + level);
        int thinkingTokens = switch (level) {
            case LOW -> 150 + (int) (t * 250);
            case MEDIUM -> 450 + (int) (t * 400);
            case HIGH -> 900 + (int) (t * 700);
            default -> 0;
        };
        return new Lab3Reply(labelsJson(reasoning, why, cat, urg, human), thinkingTokens,
                "Розбираю звернення: що просить студент, чи є строк, чи потрібна людина. " + why);
    }

    /** Правило згадує правильне значення → допомагає (× 0.5); лише неправильне → шкодить (× 1.4); нічого → без змін. */
    private static double ruleFactor(String rule, List<String> right, List<String> wrongValues) {
        boolean r = right.stream().anyMatch(rule::contains);
        boolean w = wrongValues.stream().anyMatch(rule::contains);
        return r ? 0.5 : w ? 1.4 : 1.0;
    }

    /** Основи слів (перші 5 літер слів із ≥ 5 літер), без надто загальних. */
    private static final List<String> STOP_STEMS = List.of("студе", "зверн", "можна", "потрі", "цього", "тільк", "навіт", "якщо", "праці");

    private static boolean sharesStem(String a, String b) {
        java.util.Set<String> sa = new java.util.HashSet<>(), sb = new java.util.HashSet<>();
        for (String w : Variant.words(a)) if (w.length() >= 5) sa.add(w.substring(0, 5));
        for (String w : Variant.words(b)) if (w.length() >= 5) sb.add(w.substring(0, 5));
        sa.removeAll(STOP_STEMS);
        sa.retainAll(sb);
        return !sa.isEmpty();
    }

    private static double baseErrorRate(Variant.Inquiry q, Variant v, String field) {
        if (q.isTrap()) return field.equals(q.trapField()) ? 0.62 : 0.14;
        boolean boundary = v.pair().contains(q.category()) && v.pair().contains(q.distractor());
        if (field.equals("category")) return boundary ? 0.42 : 0.06;
        return 0.08;
    }

    private static String wrongUrgency(Variant.Inquiry q) {
        if (q.urgency().equals("high")) return "normal";                // не помітила строк
        if ("urgent_no_substance".equals(q.trap())) return "high";      // повелась на «терміново»
        if (q.urgency().equals("low")) return "normal";
        return unit("w|" + q.id()) < 0.5 ? "high" : "low";
    }

    private static String labelsJson(boolean reasoning, String why, String cat, String urg, boolean human) {
        String labels = "\"category\": \"" + cat + "\", \"urgency\": \"" + urg + "\", \"needs_human\": " + human;
        return reasoning ? "{\"reasoning\": \"" + why.replace("\"", "'") + "\", " + labels + "}" : "{" + labels + "}";
    }

    private static String between(String s, String quote) {
        int a = s.indexOf(quote), b = s.lastIndexOf(quote);
        return a >= 0 && b > a ? s.substring(a + quote.length(), b) : s;
    }

    private static String clipText(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Детерміноване «випадкове» число з [0, 1) для рядка (SplitMix-подібне перемішування). */
    static double unit(String s) {
        long h = 1125899906842597L;
        for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Імітація затримки: справжня модель тим повільніша, чим більше токенів (включно з міркуванням) генерує. */
    private static void simulateLatency(int generatedTokens) {
        try {
            Thread.sleep(2 + generatedTokens / 10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ спільне

    private String reply(String system, String user, Double temperature) {
        String u = user.toLowerCase();
        String sys = system.toLowerCase();
        if (sys.contains("тікет") && sys.contains("json")) { // лабораторна 2, крок 7.1: JSON лише промптом
            return ticketJson(user, false);
        }
        if (u.contains("кав'ярні")) {
            if (temperature != null && temperature == 0.0) {
                return COFFEE_AT_ZERO.get(Math.floorMod(coffeeAtZero.getAndIncrement(), COFFEE_AT_ZERO.size()));
            }
            String name = COFFEE_NAMES.get(random.nextInt(COFFEE_NAMES.size()));
            int style = random.nextInt(5); // іноді те саме слово в іншому оформленні
            return style == 0 ? "«" + name + "»" : style == 1 ? name + "." : name;
        }
        if (u.contains("льв")) { // Львів / Львова / Львові (лабораторна 1, варіант 1)
            if (u.contains("рівно 3 пункти")) {
                return """
                        - Місто кав'ярень і старого центру: усе потрібне поруч з університетом.
                        - Бруківка, трамваї та парки допоможуть перепочити між парами.
                        - Площа Ринок і Високий Замок — маршрути для перших вихідних.""";
            }
            return """
                    Львів — одне з найстаріших і найкрасивіших міст України, розташоване на заході країни. \
                    Його засновано в XIII столітті королем Данилом Галицьким на честь сина Лева. \
                    Історичний центр внесено до списку Всесвітньої спадщини ЮНЕСКО. Тут багато музеїв, \
                    церков, кав'ярень і ресторанів, а також відомі університети. Місто славиться \
                    архітектурою різних епох — від готики й ренесансу до бароко та модерну. \
                    Щороку Львів приймає безліч туристів, фестивалів і виставок.""";
        }
        String lab1 = lab1Variant(system, user, temperature);
        if (lab1 != null) return lab1;
        String base = "Токен — це мінімальна одиниця тексту, яку обробляє модель: слово, його частина або навіть окремий символ. "
                + "Модель не бачить літер, вона працює з числами-ідентифікаторами токенів, і саме за ними рахується вартість. "
                + "Тому довжину запиту вимірюють у токенах, а не в словах чи символах.";
        if (system.toLowerCase().contains("пірат")) {
            return "Йо-хо-хо! " + base + " Тримай курс, юнго!";
        }
        return base;
    }

    // ------------------------------------------------------------------ лабораторна 2: тікет підтримки

    /**
     * Імітація відповіді для лабораторної 2. Категорію й пріоритет вгадуємо за ключовими словами повідомлення клієнта.
     *
     * <p>{@code strict=false} — «JSON лише промптом» (крок 7.1): кожна третя відповідь приходить з текстом навколо
     * і markdown-обгорткою, кожна п'ята — з «дрейфом» мітки пріоритету (critical замість p0), кожна четверта — з 7 кроками,
     * а одна з п'яти — з поясненням ПІСЛЯ JSON (текст після об'єкта — помилка рівня 3).
     *
     * <p>{@code strict=true} — схема в запиті (кроки 7.2–7.5): форма ЗАВЖДИ відповідає схемі (enum, 1–5 кроків),
     * як у справжнього constrained decoding. Псується лише ЗМІСТ у межах схеми, і не завжди виправляється повтором:
     * <ul>
     *   <li>«темна тема» (K4): з першої спроби feature_request + p0, після причини — виправляється;</li>
     *   <li>«звіт за місяць» (K2): дві спроби поспіль summary &gt; 200 символів, виправляється з третьої;</li>
     *   <li>«змінити email» (K9): summary &gt; 200 символів на КОЖНІЙ спробі — ворота мають чесно здатися.</li>
     * </ul>
     */
    private String ticketJson(String user, boolean strict) {
        Lab2Hit hit = findLab2(user);
        if (hit != null) return ticketJsonVariant(hit.variant(), hit.item(), user, strict);
        String u = user.toLowerCase();
        String category = "other";
        if (u.contains("увійти") || u.contains("пароль") || u.contains("логін") || u.contains("вхід") || u.contains("реєстрац")) category = "login";
        else if (u.contains("списал") || u.contains("рахунок") || u.contains("оплат") || u.contains("гроші")) category = "billing";
        else if (u.contains("падає") || u.contains("помилк") || u.contains("не працює") || u.contains("зависає")) category = "bug";
        else if (u.contains("додайте") || u.contains("хотілося б") || u.contains("було б добре") || u.contains("пропоную")) category = "feature_request";
        boolean blocker = u.contains("не можу") || u.contains("не працює") || u.contains("зараз") || u.contains("негайно");
        boolean soon = u.contains("демо") || u.contains("через годину") || u.contains("сьогодні") || u.contains("терміново");
        String priority = category.equals("feature_request") ? "p3" : (blocker && soon) ? "p0" : blocker ? "p1" : "p2";
        String sentiment = (u.contains("смішно") || u.contains("жах") || u.contains("!!")) ? "angry"
                : (blocker || soon) ? "frustrated" : "calm";
        int n = counter.get();
        int retries = count(u, "попередня відповідь відхилена"); // скільки разів ворота вже повертали причину
        String summary = summaryOf(user);
        String steps = "[\"відтворити проблему\", \"перевірити логи\", \"відповісти клієнту\"]";

        if (strict) {
            if (u.contains("темну тему") && retries == 0) priority = "p0";                       // зміст: побажання з p0
            if (u.contains("звіту за місяць") && retries < 2) summary = longSummary(summary);     // зміст: задовгий summary
            if (u.contains("змінити email")) summary = longSummary(summary);                      // не виправляється ніколи
        } else {
            if (n % 5 == 0) priority = priority.equals("p0") ? "critical" : priority.equals("p1") ? "urgent" : priority;
            if (retries == 0 && n % 4 == 0) // 7 кроків — без схеми maxItems нікому не завадив; ловить validate()
                steps = "[\"відтворити\", \"перевірити логи\", \"перевірити конфіг\", \"перевірити базу\", \"ескалювати\", \"відповісти\", \"закрити\"]";
        }
        String json = "{\"category\": \"" + category + "\", \"priority\": \"" + priority + "\", \"sentiment\": \"" + sentiment
                + "\", \"summary\": \"" + summary + "\", \"steps\": " + steps + "}";
        if (!strict && n % 3 == 0) return "Ось ваш JSON:\n```json\n" + json + "\n```";
        if (!strict && n % 5 == 2) return json + "\n\nЯкщо потрібно, можу додати ще кроки для інженера.";
        return json;
    }

    private static String longSummary(String summary) {
        return summary + " — клієнт докладно описує, що саме сталося, коли це почалося, що він уже пробував зробити самостійно, "
                + "чому це для нього важливо саме зараз і як це впливає на роботу всієї його команди протягом тижня";
    }

    private static int count(String text, String what) {
        int c = 0;
        for (int i = text.indexOf(what); i >= 0; i = text.indexOf(what, i + 1)) c++;
        return c;
    }

    // ------------------------------------------------------------------ лабораторна 1: варіанти 2–8

    private static volatile List<Lab1Variant> lab1Variants;
    private static volatile JsonNode lab1Mock;
    private final Map<Integer, AtomicInteger> namesAtZero = new ConcurrentHashMap<>();

    private static List<Lab1Variant> lab1Variants() {
        if (lab1Variants == null) lab1Variants = Lab1Variant.loadAll();
        return lab1Variants;
    }

    private static JsonNode lab1Mock() {
        if (lab1Mock == null) {
            try (InputStream in = FakeLlmServer.class.getResourceAsStream("/mock/lab1-mock.json")) {
                if (in == null) throw new IllegalStateException("Не знайдено ресурс /mock/lab1-mock.json");
                lab1Mock = JSON.readTree(in);
            } catch (IOException e) {
                throw new IllegalStateException("Не вдалося прочитати /mock/lab1-mock.json: " + e.getMessage(), e);
            }
        }
        return lab1Mock;
    }

    /**
     * Лабораторна 1, варіанти 2–8: запит розпізнаємо за ТОЧНИМ текстом із даних варіанта (lab1/variants/V0N.json),
     * відповідь — шаблон із mock/lab1-mock.json. Варіант 1 обробляють старі гілки («кав'ярні», «льв») — без змін.
     * Крок 6: A — довгий текст (FAIL), C — формат варіанта (PASS); B у частині варіантів навмисно порушує вимогу формату,
     * щоб було видно, що дає приклад. null — запит не з лабораторної 1 (або ваш власний).
     */
    private String lab1Variant(String system, String user, Double temperature) {
        for (Lab1Variant v : lab1Variants()) {
            if (v.variant() == 1) continue;
            JsonNode m = lab1Mock().path(String.valueOf(v.variant()));
            if (user.equals(v.step5().user())) {
                List<String> names = new ArrayList<>();
                m.path("names").forEach(x -> names.add(x.asText()));
                if (temperature != null && temperature == 0.0) {
                    int i = Math.floorMod(namesAtZero.computeIfAbsent(v.variant(), k -> new AtomicInteger()).getAndIncrement(), 10);
                    String a = names.get(0), b = names.get(1);
                    return List.of(a, a, "«" + a + "»", a, a + ".", a, a, b, a, "**" + a + "**").get(i);
                }
                String name = names.get(random.nextInt(names.size()));
                int style = random.nextInt(5);
                return style == 0 ? "«" + name + "»" : style == 1 ? name + "." : name;
            }
            if (user.equals(v.step6().promptA())) return m.path("A").asText();
            if (user.equals(v.step6().task())) return m.path("B").asText();
            if (user.equals(v.step6().task() + v.step6().example())) return m.path("C").asText();
            if (user.equals(v.step1().user()) || user.equals(v.step2().user()) || user.equals(v.step3().user())) {
                String base = "Токен — це мінімальна одиниця тексту, яку обробляє модель: слово, його частина або навіть окремий символ. "
                        + "Назва «" + v.city() + "» для моделі — не одне слово, а кілька токенів-шматочків, і за кожен ми платимо. "
                        + "Тому довжину запиту вимірюють у токенах, а не в словах чи символах.";
                return system != null && system.toLowerCase().contains("пірат") ? "Йо-хо-хо! " + base + " Тримай курс, юнго!" : base;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ лабораторна 2: варіанти 2–8

    private static volatile List<Lab2Variant> lab2Variants;

    record Lab2Hit(Lab2Variant variant, Lab2Variant.Item item) {
    }

    private static List<Lab2Variant> lab2Variants() {
        if (lab2Variants == null) lab2Variants = Lab2Variant.loadAll();
        return lab2Variants;
    }

    /** Повідомлення між першою парою потрійних лапок, якщо воно з варіантів 2–8 лабораторної 2 (варіант 1 — стара логіка). */
    private static Lab2Hit findLab2(String user) {
        int a = user.indexOf("\"\"\"");
        int b = a < 0 ? -1 : user.indexOf("\"\"\"", a + 3);
        if (b < 0) return null;
        String message = user.substring(a + 3, b);
        for (Lab2Variant v : lab2Variants()) {
            if (v.variant() == 1) continue;
            Optional<Lab2Variant.Item> it = v.findByMessage(message);
            if (it.isPresent()) return new Lab2Hit(v, it.get());
        }
        return null;
    }

    /** Номер картки чи телефону: 7+ цифр, можна з пробілами, дужками, дефісами, «+» на початку. */
    private static final Pattern LONG_NUMBER = Pattern.compile("\\+?\\d(?:[ ()\\-]*\\d){6,}");

    private static final String DEFAULT_STEPS = "[\"відтворити проблему\", \"перевірити логи\", \"відповісти клієнту\"]";

    /**
     * Лабораторна 2, варіанти 2–8. Детерміновано (від варіанта і номера кейсу), числа різні між варіантами:
     * <ul>
     *   <li>категорія/пріоритет — очікувані, але частина кейсів (≈ 12 % / 20 %) «помиляється» — це бачить eval 7.6;</li>
     *   <li>зі схемою (strict) — ті самі типи збоїв, що й у варіанті 1: перше побажання з p0 на першій спробі; один кейс зі
     *       summary &gt; 200 на двох спробах (у половині варіантів ще один — на одній); один кейс зі summary &gt; 200 завжди;</li>
     *   <li>обов'язкове правило варіанта: перша спроба частини кейсів його порушує (summary з номером, латиницею, задовге,
     *       без назви продукту; кроки «Перевірте…», з однаковим першим словом, 5 кроків для p3), повтор — виправляє.
     *       Порушення видно лише тоді, коли ви дописали це правило у validate();</li>
     *   <li>без схеми (крок 7.1) — та сама обгортка і дрейф міток, що й у варіанті 1.</li>
     * </ul>
     */
    private String ticketJsonVariant(Lab2Variant v, Lab2Variant.Item c, String user, boolean strict) {
        String u = user.toLowerCase();
        String key = "V" + v.variant() + "-" + c.id();
        int n = counter.get();
        int retries = count(u, "попередня відповідь відхилена");

        List<Lab2Variant.Item> nonFeature = new ArrayList<>(v.cases().stream().filter(x -> !x.category().equals("feature_request")).toList());
        nonFeature.sort(Comparator.comparingDouble(x -> unit("l2q|V" + v.variant() + "|" + x.id())));
        int longTwice = nonFeature.get(0).id(), neverFixed = nonFeature.get(1).id();
        int longOnce = unit("l2x|V" + v.variant()) < 0.5 ? nonFeature.get(2).id() : -1;
        int featureP0 = v.cases().stream().filter(x -> x.category().equals("feature_request")).findFirst().map(Lab2Variant.Item::id).orElse(-1);

        String category = c.category();
        String priority = c.priority();
        if (c.id() != featureP0 && unit("l2c|" + key) < 0.12) category = switch (category) {
            case "login" -> "other";
            case "billing" -> "bug";
            case "bug" -> "other";
            case "other" -> "billing";
            default -> "other";
        };
        if (unit("l2p|" + key) < 0.2) priority = switch (priority) {
            case "p0" -> "p1";
            case "p1" -> "p2";
            case "p2" -> "p1";
            default -> "p2";
        };
        boolean blocker = u.contains("не можу") || u.contains("не працює") || u.contains("зараз") || u.contains("не дає");
        String sentiment = (u.contains("!!") || u.contains("смішно")) ? "angry" : (blocker || u.contains("терміново") || u.contains("!")) ? "frustrated" : "calm";

        // обов'язкове правило варіанта: перша спроба порушує (природно або «за планом»), повтор виправляє
        String summary = summaryOf(user);
        String steps = DEFAULT_STEPS;
        boolean planned = retries == 0 && unit("l2r|" + key) < 0.4;
        Lab2Variant.Rule rule = v.rule();
        int max = rule.max() == null ? 0 : rule.max();
        switch (rule.code()) {
            case "summary_max_len" -> {
                if (retries > 0 && summary.length() > max) summary = summary.substring(0, max - 3).stripTrailing() + "...";
            }
            case "summary_no_numbers" -> {
                Matcher num = LONG_NUMBER.matcher(c.message());
                if (retries == 0 && num.find()) summary = clipText(summary, 60) + " (" + num.group() + ")"; // модель переносить номер
                summary = retries > 0 ? LONG_NUMBER.matcher(summary).replaceAll("(номер приховано)") : summary;
            }
            case "summary_no_latin" -> {
                if (retries > 0) summary = summary.replaceAll("[A-Za-z][A-Za-z0-9_.\\-]*", "").replaceAll(" {2,}", " ").replace(" :", ":").strip();
            }
            case "summary_mentions_product" -> {
                if (!planned && !summary.toLowerCase().contains(v.product().toLowerCase())) summary = v.product() + ": " + summary;
            }
            case "steps_infinitive" -> {
                if (planned) steps = "[\"Перевірте статус лінії клієнта\", \"Зателефонуйте клієнту\", \"Закрийте тікет після відповіді\"]";
            }
            case "steps_distinct_first_word" -> {
                if (planned) steps = "[\"перевірити баланс клієнта\", \"перевірити налаштування\", \"відповісти клієнту\"]";
            }
            case "p3_max_steps" -> {
                if (retries == 0 && priority.equals("p3")) // кожне p3 з першої спроби — з розлогим планом
                    steps = "[\"подякувати клієнту\", \"оцінити ідею\", \"передати менеджеру продукту\", \"додати в план\", \"повідомити клієнта\"]";
            }
            default -> {
                // невідоме правило — без особливостей
            }
        }
        if (strict) {
            if (c.id() == featureP0 && retries == 0) priority = "p0";                 // зміст: побажання з p0
            if (c.id() == longTwice && retries < 2) summary = longSummary(summary);    // виправляється з третьої спроби
            if (c.id() == longOnce && retries < 1) summary = longSummary(summary);     // виправляється з другої
            if (c.id() == neverFixed) summary = longSummary(summary);                  // не виправляється ніколи
        } else {
            if (n % 5 == 0) priority = priority.equals("p0") ? "critical" : priority.equals("p1") ? "urgent" : priority;
            if (retries == 0 && n % 4 == 0)
                steps = "[\"відтворити\", \"перевірити логи\", \"перевірити конфіг\", \"перевірити базу\", \"ескалювати\", \"відповісти\", \"закрити\"]";
        }
        String json = "{\"category\": \"" + category + "\", \"priority\": \"" + priority + "\", \"sentiment\": \"" + sentiment
                + "\", \"summary\": \"" + summary.replace("\"", "'") + "\", \"steps\": " + steps + "}";
        if (!strict && n % 3 == 0) return "Ось ваш JSON:\n```json\n" + json + "\n```";
        if (!strict && n % 5 == 2) return json + "\n\nЯкщо потрібно, можу додати ще кроки для інженера.";
        return json;
    }

    /** Перший ключ, якого Claude structured outputs не приймає (null — усе гаразд). Назви полів у properties не перевіряємо. */
    private static String findUnsupported(JsonNode node) {
        if (node.isObject()) {
            for (String k : CLAUDE_UNSUPPORTED) if (node.has(k)) return k;
            if (node.path("minItems").asInt(0) > 1) return "minItems";
            var it = node.fields();
            while (it.hasNext()) {
                var e = it.next();
                JsonNode v = e.getValue();
                if (e.getKey().equals("properties") && v.isObject()) {
                    for (JsonNode child : v) {
                        String bad = findUnsupported(child);
                        if (bad != null) return bad;
                    }
                } else {
                    String bad = findUnsupported(v);
                    if (bad != null) return bad;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String bad = findUnsupported(child);
                if (bad != null) return bad;
            }
        }
        return null;
    }

    private static String summaryOf(String user) {
        String body = user.replace("Повідомлення клієнта:", "").replace("\"\"\"", "").strip();
        String first = body.split("[.!?\n]")[0].replace("\"", "'").strip();
        return first.length() > 80 ? first.substring(0, 77) + "..." : first;
    }

    private static String truncate(String text, int maxTokens) {
        int chars = (int) (maxTokens * 2.2);
        return text.substring(0, Math.min(text.length(), chars)).stripTrailing();
    }

    /** Груба оцінка кількості токенів — лише для імітації. */
    public static int estimate(String s) {
        if (s == null || s.isEmpty()) return 0;
        int cyrillic = 0;
        int other = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0400 && c <= 0x04FF) cyrillic++;
            else other++;
        }
        return (int) Math.ceil(cyrillic / 2.2 + other / 4.0);
    }

    private static ObjectNode claudeError(String type, String message) {
        ObjectNode out = JSON.createObjectNode();
        out.put("type", "error");
        out.putObject("error").put("type", type).put("message", message);
        return out;
    }

    private static ObjectNode geminiError(int code, String status, String message) {
        ObjectNode out = JSON.createObjectNode();
        ObjectNode err = out.putObject("error");
        err.put("code", code);
        err.put("message", message);
        err.put("status", status);
        return out;
    }

    private static void send(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().add("content-type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
