package ua.knu.promptlab.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Лабораторна 4: ІМІТАЦІЯ ембедингів і відповідей моделі (лише для MOCK і тестів; до справжніх моделей стосунку не має).
 *
 * <ul>
 *   <li><b>Ембединги</b> (batchEmbedContents): детермінований «мішок слів» — кожна основа слова (перші 5 літер; кілька синонімів
 *       зведено до однієї основи, щоб імітувати «розуміння змісту») хешується в 2 з 768 вимірів; вектор нормалізується до довжини 1.
 *       Префікси «task: … | query:» і «title: … | text:» теж стають словами — тож запит без префікса дає інші числа.</li>
 *   <li><b>Без документів</b> (схема {answer}): упевнена, але вигадана відповідь (на спільне питання — приклад зі слайда 2).</li>
 *   <li><b>З цитатами</b> (схема {answer, cited_ids, answerable}): шматок із найбільшим збігом основ слів з питанням; збіг малий —
 *       answerable = false. Помилки детерміновані: на першій спробі іноді вигаданий id (валідатор ловить, повтор виправляє) або
 *       порожній cited_ids; з мінімальним міркуванням частіше «вигадує» відповідь на питання без відповіді; effort high —
 *       менше помилок, цитує до двох шматків і витрачає 700–1500 токенів міркування.</li>
 * </ul>
 */
final class FakeLab4 {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}']+");
    private static final Set<String> STOP = Set.of("який", "яка", "які", "якщо", "можна", "треба", "мені", "мене", "буде", "чи", "що", "для",
            "або", "та", "при", "після", "цього", "цієї", "тому", "щоб", "його", "її", "вони", "мати", "коли", "скільки", "разів", "як",
            "положен", "універ", "студе", "здобу", "освіт", "task", "query", "title", "text", "none", "search", "result");
    /** Синоніми імітації: основа → спільна основа. */
    private static final Map<String, String> SYN = Map.ofEntries(
            Map.entry("іспит", "екзам"), Map.entry("перес", "склад"), Map.entry("перез", "склад"), Map.entry("гурто", "посел"),
            Map.entry("житл", "посел"), Map.entry("житло", "посел"), Map.entry("грошо", "випла"), Map.entry("стипу", "стипе"),
            Map.entry("заочк", "заочн"), Map.entry("відра", "відра"), Map.entry("скарж", "оскар"), Map.entry("апеля", "оскар"));

    private FakeLab4() {
    }

    static boolean isLab4(JsonNode schema) {
        return schema.path("properties").has("answer") || schema.path("properties").has("query");
    }

    static String stem(String w) {
        String s = w.length() > 5 ? w.substring(0, 5) : w;
        return SYN.getOrDefault(s, s);
    }

    static List<String> stems(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String w = m.group();
            if (w.length() < 3 && !w.matches("\\d+")) continue;
            String s = stem(w);
            if (!STOP.contains(w) && !STOP.contains(s)) out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------------ ембединги

    static float[] vector(String text, int dim) {
        float[] v = new float[dim];
        for (String s : stems(text)) {
            int a = (int) (FakeLlmServer.unit("e1|" + s) * dim), b = (int) (FakeLlmServer.unit("e2|" + s) * dim);
            float sign = FakeLlmServer.unit("es|" + s) < 0.5 ? -1f : 1f;
            v[a] += sign;
            v[b] += 0.5f * sign;
        }
        // префікс — теж «слова»: окремий спільний напрямок для запитів і для документів
        if (text.startsWith("task: search result | query:")) v[(int) (FakeLlmServer.unit("pq") * dim)] += 1.2f;
        if (text.startsWith("title: ")) v[(int) (FakeLlmServer.unit("pd") * dim)] += 1.2f;
        double n = 0;
        for (float x : v) n += x * x;
        if (n == 0) {
            v[0] = 1;
            return v;
        }
        float k = (float) (1 / Math.sqrt(n));
        for (int i = 0; i < dim; i++) v[i] *= k;
        return v;
    }

    /** Відповідь batchEmbedContents (або embedContent — один ембединг). */
    static ObjectNode embed(JsonNode req, boolean batch) {
        ObjectNode out = JSON.createObjectNode();
        if (batch) {
            ArrayNode arr = out.putArray("embeddings");
            for (JsonNode r : req.path("requests")) arr.add(one(r));
        } else {
            out.set("embedding", one(req));
        }
        return out;
    }

    private static ObjectNode one(JsonNode r) {
        int dim = r.path("outputDimensionality").asInt(r.path("output_dimensionality").asInt(3072));
        String text = r.path("content").path("parts").path(0).path("text").asText("");
        ObjectNode e = JSON.createObjectNode();
        ArrayNode vals = e.putArray("values");
        for (float x : vector(text, dim)) vals.add(Math.round(x * 1e6) / 1e6);
        return e;
    }

    // ------------------------------------------------------------------ відповіді

    record Reply(String json, int thinkingTokens) {
    }

    static Reply reply(String user, JsonNode schema, int level) {
        if (schema.path("properties").has("query")) return rewrite(user);
        boolean rag = schema.path("properties").has("cited_ids");
        return rag ? rag(user, level) : noRag(user);
    }

    /** Розмовні слова → слова положень (лише для імітації кроку 4б). */
    private static final Map<String, String> OFFICIAL = Map.ofEntries(
            Map.entry("іспит", "екзамен"), Map.entry("іспиту", "екзамену"), Map.entry("перескладати", "повторне складання"),
            Map.entry("перездати", "повторно скласти"), Map.entry("перескладав", "повторно складав"), Map.entry("общагу", "гуртожиток"),
            Map.entry("общага", "гуртожиток"), Map.entry("общаги", "гуртожитку"), Map.entry("заселяєшся", "поселення"),
            Map.entry("заселятися", "поселення"), Map.entry("академвідпустку", "академічну відпустку"), Map.entry("стипуху", "стипендію"),
            Map.entry("академрізницю", "академічну різницю"), Map.entry("академрізниця", "академічна різниця"), Map.entry("пари", "заняття"));

    /** Імітація кроку 4б: питання «мовою положень» — розмовні слова замінено офіційними. */
    private static Reply rewrite(String user) {
        String q = user.replace("Питання студента:", "").strip();
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("[\\p{L}']+|[^\\p{L}']+").matcher(q);
        while (m.find()) {
            String w = m.group();
            String rep = OFFICIAL.get(w.toLowerCase(Locale.ROOT));
            sb.append(rep != null ? rep : w);
        }
        return new Reply("{\"query\": \"" + sb.toString().replace("\"", "'") + "\"}", 0);
    }

    private static final List<String> INVENTED = List.of(
            "Так, це можна зробити протягом двох тижнів після початку семестру — достатньо написати заяву на ім'я викладача.",
            "За правилами університету на це дається не більше трьох спроб, а остаточне рішення ухвалює куратор групи.",
            "Ні, такої можливості немає: це дозволено лише студентам, які навчаються на контракті.",
            "Так, за умови, що середній бал не нижчий за 75, а заява подана до кінця місяця в студентський відділ кадрів.");

    private static Reply noRag(String user) {
        String q = user.replace("Питання студента:", "").strip();
        String a = q.toLowerCase(Locale.ROOT).contains("перескладати іспит")
                ? "Перескладати можна тричі викладачеві, до кінця наступного семестру."
                : INVENTED.get((int) (FakeLlmServer.unit("nr|" + q) * INVENTED.size()));
        return new Reply("{\"answer\": \"" + a.replace("\"", "'") + "\"}", 0);
    }

    private static final Pattern CHUNK = Pattern.compile("^\\[([^\\]\\n]+)\\][^\\n]*\\n(.*?)(?=\\n\\n\\[|\\n\\nПитання студента:)", Pattern.DOTALL | Pattern.MULTILINE);

    private static Reply rag(String user, int level) {
        int retries = 0;
        for (int i = user.indexOf("Попередня відповідь відхилена"); i >= 0; i = user.indexOf("Попередня відповідь відхилена", i + 1)) retries++;
        int qi = user.indexOf("Питання студента:");
        String question = qi < 0 ? "" : user.substring(qi + "Питання студента:".length()).split("\n\nПопередня")[0].strip();
        Map<String, String> chunks = new LinkedHashMap<>();
        Matcher m = CHUNK.matcher(user);
        while (m.find()) chunks.put(m.group(1), m.group(2));
        Set<String> qs = new HashSet<>(stems(question));
        String best = null, second = null;
        double bs = 0, ss = 0;
        for (var e : chunks.entrySet()) {
            Set<String> cs = new HashSet<>(stems(e.getValue()));
            int hit = 0;
            for (String s : qs) if (cs.contains(s)) hit++;
            double score = qs.isEmpty() ? 0 : (double) hit / qs.size();
            if (score > bs) {
                second = best;
                ss = bs;
                best = e.getKey();
                bs = score;
            } else if (score > ss) {
                second = e.getKey();
                ss = score;
            }
        }
        boolean high = level >= FakeLlmServer.HIGH;
        double threshold = high ? 0.3 : 0.4; // high «уважніше читає» — помічає відповідь і при меншому збігу слів
        boolean answerable = best != null && bs >= threshold;
        String key = question + "|" + level;
        if (!answerable && best != null && FakeLlmServer.unit("hall|" + key) < (high ? 0.15 : 0.5)) answerable = true; // «вигадала»
        List<String> cited = new ArrayList<>();
        String answer;
        if (answerable) {
            cited.add(best);
            if (high && second != null && ss >= bs - 0.1 && ss >= threshold) cited.add(second);
            answer = sentence(chunks.get(best), qs);
            if (retries == 0 && FakeLlmServer.unit("cite|" + key) < (high ? 0.1 : 0.25)) cited = List.of("osv-8.3-07"); // вигаданий id
            else if (retries == 0 && !high && FakeLlmServer.unit("empty|" + key) < 0.1) cited = List.of();
        } else {
            answer = "Не знайшов цього в положеннях — зверніться до деканату.";
            if (retries == 0 && !high && FakeLlmServer.unit("nodek|" + key) < 0.15) answer = "У наданих уривках цього немає.";
        }
        StringBuilder ids = new StringBuilder();
        for (String id : cited) ids.append(ids.isEmpty() ? "" : ", ").append('"').append(id).append('"');
        String json = "{\"answer\": \"" + answer.replace("\\", " ").replace("\"", "'").replace("\n", " ") + "\", \"cited_ids\": [" + ids
                + "], \"answerable\": " + answerable + "}";
        int thinking = high ? 700 + (int) (FakeLlmServer.unit("th|" + key + "|" + retries) * 800) : 0;
        return new Reply(json, thinking);
    }

    /** Речення шматка з найбільшим збігом основ із питанням + наступне речення (до 350 знаків). */
    private static String sentence(String text, Set<String> qs) {
        String[] sents = text.replace('\n', ' ').split("(?<=[.;!?])\\s+");
        int bi = 0, bh = -1;
        for (int i = 0; i < sents.length; i++) {
            int h = 0;
            for (String s : stems(sents[i])) if (qs.contains(s)) h++;
            if (h > bh) {
                bh = h;
                bi = i;
            }
        }
        String out = sents[bi] + (bi + 1 < sents.length ? " " + sents[bi + 1] : "");
        out = out.strip();
        return out.length() > 350 ? out.substring(0, 349) + "…" : out;
    }
}
