package ua.knu.promptlab.lab4;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ua.knu.promptlab.CallOptions;
import ua.knu.promptlab.LlmClient;
import ua.knu.promptlab.LlmResponse;
import ua.knu.promptlab.lab3.AnswerCache;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Лабораторна 4. Запит до моделі з кешем відповідей (як у лабі 3: target/lab4-cache.json) і воротами з повтором (як JsonGate з лаб 2–3):
 * виклик → {@link RagAnswer#validate} → якщо помилка, той самий запит + речення-причина, не більше {@value #MAX_RETRIES} повторів.
 * temperature не надсилається; рівень міркування (effort) — minimal або high.
 */
public final class Answerer {

    public static final int MAX_RETRIES = 2;
    public static final int MAX_TOKENS = 2048;
    public static final int MAX_TOKENS_THINKING = 8192;

    /**
     * Що вийшло: відповідь (або null, якщо ворота здалися), скільки спроб, причини відхилених спроб і лічильники разом на всі спроби.
     * cachedAll — усі спроби взято з кешу.
     */
    public record Outcome(RagAnswer answer, String lastText, int attempts, List<String> errors, int inputTokens, int outputTokens,
                          int thinkingTokens, long latencyMs, boolean truncated) {
        public boolean ok() {
            return answer != null && errors.size() < attempts;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmClient client;
    private final AnswerCache cache;
    private int newCalls;
    private int cachedCalls;
    private boolean anyNewCall;

    public Answerer(LlmClient client, AnswerCache cache) {
        this.client = client;
        this.cache = cache;
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

    /** Крок 1: питання без документів (мінімальне міркування), лише розбір JSON — без повторів. */
    public Outcome noRag(String question) {
        return run(RagPrompts.SYSTEM_NO_RAG, RagPrompts.userNoRag(question), RagPrompts.schemaNoRag(), CallOptions.of(CallOptions.Effort.MINIMAL), null);
    }

    /** Кроки 5–6: відповідь з цитатами з переданих шматків, валідатор і повтор. */
    public Outcome rag(String question, List<Chunk> chunks, CallOptions.Effort effort) {
        List<String> ids = chunks.stream().map(Chunk::id).toList();
        return run(RagPrompts.SYSTEM_RAG, RagPrompts.userRag(question, chunks), RagPrompts.schemaRag(), CallOptions.of(effort), ids);
    }

    /**
     * Крок 4б: переписати питання студента мовою положень за ВАШИМ промптом (MyRetrievalTweak). Схема {query}; мінімальне міркування;
     * у кеші. Якщо модель нічого не повернула — лишаємо питання як є.
     */
    public String rewrite(String prompt, String question) {
        String user = "Питання студента: " + question;
        AnswerCache.Entry e = call(prompt, user, RagPrompts.schemaRewrite(), MAX_TOKENS, CallOptions.of(CallOptions.Effort.MINIMAL));
        try {
            JsonNode n = JSON.readTree(e.text() == null ? "" : e.text().strip());
            String q = n == null ? "" : n.path("query").asText("").strip();
            return q.isEmpty() ? question : q;
        } catch (Exception ex) {
            return question;
        }
    }

    private Outcome run(String system, String user0, JsonNode schema, CallOptions options, Collection<String> ids) {
        int maxTokens = options.effort().thinks() ? MAX_TOKENS_THINKING : MAX_TOKENS;
        List<String> errors = new ArrayList<>();
        String user = user0;
        int in = 0, out = 0, th = 0;
        long lat = 0;
        String lastText = null;
        RagAnswer last = null;
        int attempts = ids == null ? 1 : 1 + MAX_RETRIES;
        for (int a = 1; a <= attempts; a++) {
            AnswerCache.Entry e = call(system, user, schema, maxTokens, options);
            in += e.inputTokens();
            out += e.outputTokens();
            th += e.thinkingTokens();
            lat += e.latencyMs();
            lastText = e.text();
            if ("max_tokens".equals(e.stopReason())) {
                errors.add("відповідь обірвано лімітом вихідних токенів (" + maxTokens + ")");
                return new Outcome(last, lastText, a, errors, in, out, th, lat, true);
            }
            RagAnswer.Check c = ids == null ? RagAnswer.parseNoRag(e.text()) : RagAnswer.validate(e.text(), ids);
            if (c.answer() != null) last = c.answer();
            if (c.ok()) return new Outcome(c.answer(), lastText, a, errors, in, out, th, lat, false);
            errors.add(c.error());
            if (ids == null) break;
            user = user + "\n\nПопередня відповідь відхилена: " + c.error() + ". Виправ і поверни відповідь знову.";
        }
        return new Outcome(last, lastText, errors.size(), errors, in, out, th, lat, false);
    }

    private AnswerCache.Entry call(String system, String user, JsonNode schema, int maxTokens, CallOptions options) {
        String key = AnswerCache.key(client, keyMaterial(system, user, schema, maxTokens, options), "s1");
        var hit = cache.get(key);
        if (hit.isPresent()) {
            cachedCalls++;
            return hit.get();
        }
        if (anyNewCall) pause();
        anyNewCall = true;
        LlmResponse r = client.askJson(system, user, schema, maxTokens, options);
        newCalls++;
        AnswerCache.Entry e = new AnswerCache.Entry(r.text(), r.inputTokens(), r.outputTokens(), r.thinkingTokens(), r.latencyMs(),
                r.stopReason(), r.rawStopReason());
        cache.put(key, e);
        return e;
    }

    static ObjectNode keyMaterial(String system, String user, JsonNode schema, int maxTokens, CallOptions options) {
        ObjectNode k = JSON.createObjectNode();
        k.put("lab", 4).put("system", system).put("user", user).put("maxTokens", maxTokens).put("effort", options.effort().name());
        k.set("schema", schema);
        return k;
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
