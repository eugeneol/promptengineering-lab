package ua.knu.promptlab.lab4;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ua.knu.promptlab.ApiException;
import ua.knu.promptlab.HttpJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Лабораторна 4. Ембединги gemini-embedding-2 через REST, без SDK (ai.google.dev/gemini-api/docs/embeddings, звірено 12.11.2026):
 * <pre>
 * POST {base}/v1beta/models/gemini-embedding-2:batchEmbedContents      (ключ — у заголовку x-goog-api-key)
 * {"requests": [ {"model": "models/gemini-embedding-2",
 *                 "content": {"parts": [{"text": "title: none | text: …"}]},
 *                 "outputDimensionality": 768}, … ]}
 * → {"embeddings": [ {"values": [768 чисел]}, … ]}   — у тому самому порядку, що й запити
 * </pre>
 * Кожен текст — окремий request зі своїм Content (а не кілька parts одного Content: тоді вийшов би ОДИН ембединг на всі).
 * task_type для gemini-embedding-2 не надсилаємо: роль тексту задає префікс ({@link Prefixes}).
 * Розмір пакета — {@value #DEFAULT_BATCH} текстів (ліміт у документації не знайдено; 100 — поширене обмеження для batchEmbedContents).
 * Ключ Gemini потрібен навіть тим, хто генерує відповіді через Claude.
 *
 * <p><b>Звідки вектор</b>: ваш кеш на диску ({@link EmbeddingCache}) → кеш викладача з ресурсів ({@link EmbeddingBundle}) → запит до API.
 * <p><b>Пауза за токенами</b>: пакети формуються так, щоб за будь-які 60 секунд відправити не більше LAB_EMBED_TPM токенів (оцінка — 3 знаки
 * на токен, як у лабі 1); за замовчуванням {@value #DEFAULT_TPM} — консервативне припущення для безплатного тарифу (точного ліміту для
 * gemini-embedding-2 у документації не знайдено). Якщо API все одно відповів 429 — чекаємо стільки, скільки він пише в retryDelay;
 * добовий ліміт — зрозуміла помилка (кеш збережено, продовжити можна завтра).
 */
public final class Embedder {

    public static final String DEFAULT_MODEL = "gemini-embedding-2";
    public static final int DIM = 768;
    public static final int DEFAULT_BATCH = 100;
    /**
     * Токенів на хвилину за замовчуванням (LAB_EMBED_TPM): обережно для безплатного тарифу. Окремо від нього безплатний тариф має добовий
     * ліміт ≈1000 текстів (12.11.2026: кожен текст у пакеті рахується окремо) — тоді код зупиняється з «продовжіть завтра».
     */
    public static final int DEFAULT_TPM = 30_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int dim;
    private final int batch;
    private final long delayMs;
    private final EmbeddingCache cache;
    private final EmbeddingBundle bundle;
    private final int tpm;
    private final HttpJson http = new HttpJson(5, 5000);
    private final java.util.ArrayDeque<long[]> window = new java.util.ArrayDeque<>(); // {час, токени} відправлених пакетів
    private int requests;
    private int embedded;
    private int cached;
    private int fromBundle;
    private long waitedMs;
    private boolean anyRequest;

    public Embedder(String baseUrl, String apiKey, String model, int dim, int batch, long delayMs, EmbeddingCache cache) {
        this(baseUrl, apiKey, model, dim, batch, delayMs, cache, null, 0);
    }

    /**
     * @param bundle кеш викладача (або null)
     * @param tpm    скільки токенів можна відправити за хвилину (0 — без обмеження)
     */
    public Embedder(String baseUrl, String apiKey, String model, int dim, int batch, long delayMs, EmbeddingCache cache,
                    EmbeddingBundle bundle, int tpm) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.dim = dim;
        this.batch = batch;
        this.delayMs = delayMs;
        this.cache = cache;
        this.bundle = bundle;
        this.tpm = tpm;
    }

    /** Скільки векторів узято з кешу викладача. */
    public int fromBundle() {
        return fromBundle;
    }

    public EmbeddingBundle bundle() {
        return bundle;
    }

    public int tpm() {
        return tpm;
    }

    /** Скільки мілісекунд чекали через ліміт токенів на хвилину. */
    public long waitedMs() {
        return waitedMs;
    }

    /** Оцінка кількості токенів тексту: ≈ 3 знаки на токен (лаба 1). */
    public static int estimateTokens(String text) {
        return Math.max(1, (text.length() + 2) / 3);
    }

    public String model() {
        return model;
    }

    public int dim() {
        return dim;
    }

    public int batchSize() {
        return batch;
    }

    /** Скільки HTTP-запитів batchEmbedContents зроблено. */
    public int requests() {
        return requests;
    }

    /** Скільки текстів отримали ембединг від API (нові). */
    public int embedded() {
        return embedded;
    }

    /** Скільки текстів узято з кешу. */
    public int cached() {
        return cached;
    }

    public EmbeddingCache cache() {
        return cache;
    }

    public String endpoint() {
        return baseUrl + "/v1beta/models/" + model + ":batchEmbedContents";
    }

    /** Тіло одного запиту batchEmbedContents (публічне — щоб показати на екрані й перевірити тестом). */
    public ObjectNode buildRequest(List<String> texts) {
        ObjectNode body = JSON.createObjectNode();
        ArrayNode reqs = body.putArray("requests");
        for (String t : texts) {
            ObjectNode r = reqs.addObject();
            r.put("model", "models/" + model);
            r.putObject("content").putArray("parts").addObject().put("text", t);
            r.put("outputDimensionality", dim);
        }
        return body;
    }

    /** Розбір відповіді batchEmbedContents з перевіркою, що кожен вектор має рівно dim чисел. */
    public static List<float[]> parse(JsonNode resp, int expected, int dim) {
        List<float[]> out = parse(resp, expected);
        for (float[] v : out) {
            if (v.length != dim) throw new ApiException(-1, "ембединг має " + v.length + " чисел замість " + dim
                    + " — перевірте модель і outputDimensionality (LAB_EMBED_MODEL)");
        }
        return out;
    }

    /** Розбір відповіді batchEmbedContents: стільки векторів, скільки текстів, у тому самому порядку. */
    public static List<float[]> parse(JsonNode resp, int expected) {
        JsonNode arr = resp.path("embeddings");
        if (!arr.isArray() || arr.size() != expected) {
            throw new ApiException(-1, "batchEmbedContents повернув " + (arr.isArray() ? arr.size() : 0) + " ембедингів замість " + expected);
        }
        List<float[]> out = new ArrayList<>();
        for (JsonNode e : arr) {
            JsonNode vals = e.path("values");
            float[] v = new float[vals.size()];
            for (int i = 0; i < v.length; i++) v[i] = (float) vals.get(i).asDouble();
            out.add(v);
        }
        return out;
    }

    /**
     * Ембединги для текстів (уже з префіксами): що є в кеші — з кешу, решта — пакетами по {@link #batchSize()} текстів.
     * Пауза LAB_DELAY_MS — перед кожним новим запитом, крім першого.
     */
    public List<float[]> embed(List<String> texts) {
        float[][] out = new float[texts.size()][];
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            String key = EmbeddingCache.key(model, dim, texts.get(i));
            float[] v = cache.get(key);
            if (v != null) {
                out[i] = v;
                cached++;
                continue;
            }
            v = bundle == null ? null : bundle.get(key);
            if (v != null) {
                out[i] = v;
                fromBundle++;
            } else {
                missing.add(i);
            }
        }
        int from = 0;
        while (from < missing.size()) {
            // пакет: до batch текстів і (якщо задано ліміт) до tpm оцінених токенів
            int to = from, tokens = 0;
            while (to < missing.size() && to - from < batch) {
                int t = estimateTokens(texts.get(missing.get(to)));
                if (tpm > 0 && to > from && tokens + t > tpm) break;
                tokens += t;
                to++;
            }
            List<Integer> idx = missing.subList(from, to);
            List<String> part = idx.stream().map(texts::get).toList();
            if (anyRequest) pause();
            anyRequest = true;
            waitForTokens(tokens);
            JsonNode resp = http.post(endpoint(), Map.of("x-goog-api-key", apiKey), buildRequest(part));
            window.addLast(new long[]{System.currentTimeMillis(), tokens});
            requests++;
            List<float[]> vs = parse(resp, part.size(), dim);
            List<String> keys = new ArrayList<>();
            for (int j = 0; j < idx.size(); j++) {
                out[idx.get(j)] = vs.get(j);
                keys.add(EmbeddingCache.key(model, dim, part.get(j)));
            }
            embedded += part.size();
            cache.putAll(keys, vs);
            from = to;
        }
        return List.of(out);
    }

    /** Чекає, поки за останні 60 секунд відправлено достатньо мало токенів, щоб додати ще tokens (ліміт tpm). */
    private void waitForTokens(int tokens) {
        if (tpm <= 0) return;
        while (true) {
            long now = System.currentTimeMillis();
            while (!window.isEmpty() && now - window.peekFirst()[0] >= 60_000) window.removeFirst();
            long used = window.stream().mapToLong(x -> x[1]).sum();
            if (window.isEmpty() || used + tokens <= tpm) return;
            long sleep = 60_000 - (now - window.peekFirst()[0]) + 50;
            System.out.printf("    (пауза %.0f с: ліміт LAB_EMBED_TPM = %d токенів на хвилину)%n", sleep / 1000.0, tpm);
            waitedMs += sleep;
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void pause() {
        if (delayMs <= 0) return;
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
