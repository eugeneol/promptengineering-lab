package ua.knu.promptlab.lab4;

import ua.knu.promptlab.ApiException;
import ua.knu.promptlab.Config;
import ua.knu.promptlab.Console;
import ua.knu.promptlab.mock.FakeLlmServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Лабораторна 4, ДЛЯ ВИКЛАДАЧА (студентам запускати не треба). Запуск: правий клік на цьому класі → Run 'TeacherIndex.main()';
 * ключ — з .env, бажано платний тариф і LAB_EMBED_TPM=300000. Рахує ембединги всіх шматків трьох нарізок і всіх
 * питань усіх варіантів (з префіксом і без) і пише кеш викладача {@code src/main/resources/lab4/embeddings-cache.bin.gz}
 * ({@link EmbeddingBundle}), який лежить у репозиторії курсу: тоді студент не витрачає ліміт ембедингів на індексацію.
 *
 * <p>Спершу бере вектори з {@code target/lab4-embeddings.tsv} (ваш звичайний прогін лаби 4 їх уже порахував), решту — з API
 * (з паузою за LAB_EMBED_TPM). Аргумент (необов'язково) — шлях до вихідного файлу. LAB_MOCK=1 — кеш для імітації
 * ({@code src/main/resources/lab4/mock/embeddings-cache.bin.gz}).
 */
public final class TeacherIndex {

    private TeacherIndex() {
    }

    public static void main(String[] args) throws Exception {
        Console.init();
        Lab4Run.quietLibraries();
        boolean mock = Config.isTrueSetting("LAB_MOCK");
        String key = mock ? "mock-key" : Config.geminiKey();
        if (key == null) {
            System.err.println("Потрібен GEMINI_API_KEY у .env (ембединги рахує gemini-embedding-2).");
            System.exit(1);
            return;
        }
        Path out = Path.of(args.length > 0 ? args[0] : "src/main/resources" + (mock ? EmbeddingBundle.MOCK_RESOURCE : EmbeddingBundle.RESOURCE));
        String base = mock ? FakeLlmServer.startOnce().baseUrl() : Config.geminiBaseUrl();
        String model = Config.setting("LAB_EMBED_MODEL") == null ? Embedder.DEFAULT_MODEL : Config.setting("LAB_EMBED_MODEL");
        try {
            Corpus corpus = Corpus.load(mock);
            List<Corpus.Text> docs = mock ? CorpusLoader.loadMock(corpus) : CorpusLoader.fromSettings().load(corpus);
            List<String> texts = new ArrayList<>(texts(docs, mock));
            Embedder emb = new Embedder(base, key.strip(), model, Embedder.DIM, Embedder.DEFAULT_BATCH,
                    mock ? 0 : Math.max(0, parseLong(Config.setting("LAB_DELAY_MS"))), EmbeddingCache.fromSettings(false, mock), null,
                    mock ? 0 : Lab4Run.tpm());
            Console.banner("Лабораторна 4 — індексація для викладача (" + model + ", " + Embedder.DIM + ")");
            System.out.println("  текстів: " + texts.size() + " (шматки трьох нарізок і питання всіх варіантів)");
            List<float[]> vs = emb.embed(texts);
            Map<String, float[]> map = new LinkedHashMap<>();
            for (int i = 0; i < texts.size(); i++) map.put(EmbeddingCache.key(model, Embedder.DIM, texts.get(i)), vs.get(i));
            EmbeddingBundle.write(out, model, Embedder.DIM, LocalDate.now().toString(), map);
            System.out.println("  з вашого кешу: " + emb.cached() + ", нових з API: " + emb.embedded() + " (" + emb.requests() + " запитів)");
            System.out.printf("  записано: %s — %d векторів, %.1f МБ, SHA-256 %s%n", out, map.size(), Files.size(out) / 1e6,
                    CorpusLoader.sha256(Files.readAllBytes(out)).substring(0, 16));
            System.out.println("  Прогоніть тести, закомітьте файл і зробіть push — студенти отримають новий кеш через Git → Pull.");
        } catch (IllegalStateException e) {
            System.out.println(e.getMessage());
            System.exit(1);
        } catch (ApiException e) {
            System.out.println(Lab4Run.apiErrorText(e));
            System.exit(1);
        } finally {
            if (mock) FakeLlmServer.stop();
        }
    }

    /** Усі тексти з префіксами, які може попросити Lab4Run: шматки (а), (б), (в) і питання всіх варіантів (з префіксом і без). */
    public static Set<String> texts(List<Corpus.Text> docs, boolean mock) {
        Set<String> out = new LinkedHashSet<>();
        for (Chunker.Strategy s : Chunker.Strategy.values()) for (Chunk c : Chunker.chunk(docs, s)) out.add(c.embedText());
        List<Lab4Variant> vs = new ArrayList<>();
        if (mock) vs.add(Lab4Variant.mock(1));
        else vs.addAll(Lab4Variant.loadAll());
        for (Lab4Variant v : vs) {
            for (Lab4Variant.Question q : v.questions()) {
                out.add(Prefixes.query(q.text()));
                if (q.answerable()) out.add(q.text());
            }
        }
        return out;
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
