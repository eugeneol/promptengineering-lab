package ua.knu.promptlab.lab3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ua.knu.promptlab.CallOptions;
import ua.knu.promptlab.LlmClient;
import ua.knu.promptlab.LlmResponse;

/**
 * Лабораторна 3. Один виклик класифікатора звернення: схема → (кеш або запит до моделі) → {@link Labels}.
 * Лічить нові запити й відповіді з кешу; пауза LAB_DELAY_MS — лише перед новими запитами.
 */
public final class Classifier {

    /** Ліміт вихідних токенів без міркування: три мітки — це ~30 токенів, reasoning — ~100; запас як у лабораторній 2. */
    public static final int MAX_TOKENS = 2048;
    /** З міркуванням (effort low / medium / high): у Gemini токени thinking входять у той самий ліміт, тож даємо більше. */
    public static final int MAX_TOKENS_THINKING = 8192;

    /** Одна відповідь: що спитали, що отримали, лічильники і чи взято з кешу. */
    public record Answer(Variant.Inquiry inquiry, Labels labels, AnswerCache.Entry entry, boolean cached) {
        public boolean correct() {
            return inquiry.accepts(labels);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmClient client;
    private final AnswerCache cache;
    private int newCalls;
    private int cachedCalls;
    private boolean anyNewCall;

    public Classifier(LlmClient client, AnswerCache cache) {
        this.client = client;
        this.cache = cache;
    }

    /** Те саме, що {@link #ask(String, Variant.Inquiry, CallOptions, String)}, з явним прапорцем поля reasoning. */
    public Answer ask(String system, Variant.Inquiry q, boolean reasoning, CallOptions options, String tag) {
        return ask(system, q, options.withReasoningField(reasoning), tag);
    }

    /**
     * @param system  системний промпт (з прикладами чи без)
     * @param options рівень міркування (effort) і поле reasoning (true — схема з reasoning перед мітками); temperature у лабі 3 — null
     * @param tag     мітка вибірки для кешу: "s1" — перший запит, "s2"/"s3" — повтори того самого запиту (шум, голосування)
     */
    public Answer ask(String system, Variant.Inquiry q, CallOptions options, String tag) {
        JsonNode schema = Lab3Prompts.schema(options.reasoningField());
        String user = Lab3Prompts.user(q.text());
        int maxTokens = options.effort().thinks() ? MAX_TOKENS_THINKING : MAX_TOKENS;
        String key = AnswerCache.key(client, keyMaterial(system, user, schema, maxTokens, options), tag);
        var hit = cache.get(key);
        if (hit.isPresent()) {
            cachedCalls++;
            return new Answer(q, toLabels(hit.get()), hit.get(), true);
        }
        if (anyNewCall) pause();
        anyNewCall = true;
        LlmResponse r = client.askJson(system, user, schema, maxTokens, options);
        newCalls++;
        AnswerCache.Entry e = new AnswerCache.Entry(r.text(), r.inputTokens(), r.outputTokens(), r.thinkingTokens(), r.latencyMs(),
                r.stopReason(), r.rawStopReason());
        cache.put(key, e);
        return new Answer(q, toLabels(e), e, false);
    }

    /**
     * Що саме входить у ключ кешу: промпт, схема, ліміт, temperature і рівень міркування (effort) — логічні параметри запиту,
     * а не готове HTTP-тіло. Так фолбеки клієнта (модель не прийняла temperature; Claude 4.x не прийняв effort) не ламають кеш.
     */
    static ObjectNode keyMaterial(String system, String user, JsonNode schema, int maxTokens, CallOptions options) {
        ObjectNode k = JSON.createObjectNode();
        k.put("system", system).put("user", user).put("maxTokens", maxTokens);
        if (options.temperature() == null) k.putNull("temperature");
        else k.put("temperature", options.temperature());
        k.put("effort", options.effort().name());
        k.set("schema", schema);
        return k;
    }

    private static Labels toLabels(AnswerCache.Entry e) {
        if ("max_tokens".equals(e.stopReason())) return Labels.unparsed("обрізано лімітом вихідних токенів");
        return Labels.parse(e.text());
    }

    public int newCalls() {
        return newCalls;
    }

    public int cachedCalls() {
        return cachedCalls;
    }

    public LlmClient client() {
        return client;
    }

    private void pause() {
        long ms = client.requestDelayMs();
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
