package ua.knu.promptlab.lab4;

import ua.knu.promptlab.ApiException;
import ua.knu.promptlab.CallOptions;
import ua.knu.promptlab.Config;
import ua.knu.promptlab.Console;
import ua.knu.promptlab.LlmClient;
import ua.knu.promptlab.lab3.AnswerCache;
import ua.knu.promptlab.lab3.Metrics;
import ua.knu.promptlab.mock.FakeLlmServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Лабораторна 4. «Бот знаходить пункт положення»: пошук по документах (RAG) — від бота без документів до відповіді з цитатами.
 * Кроки — як на слайді «Лабораторна 4» лекції 4:
 * <pre>
 *  Крок 0.  Прогноз — у звіті, ДО запуску (і до MOCK).                                                       0 запитів
 *  Крок 1.  Без RAG: 5 питань (спільне + 4 свого варіанта), модель відповідає з пам'яті; еталон — після.     5 генерацій
 *  Крок 2.  Нарізка: (а) 800 знаків / перекриття 100, (б) за пунктами, (в) за пунктами + заголовок; статистика;
 *           чи цілі правила ВАШОГО варіанта у (а); індексація (кеш викладача → ваш кеш → API).               0, якщо є кеш викладача
 *  Крок 3.  Векторний пошук: recall@5 і MRR@5 на 9 питаннях для трьох нарізок + найкраща без префікса питання. 1–2 пакети
 *  Крок 4.  BM25 (Lucene, k1 = 1,2, b = 0,75) і гібрид RRF (k = 60, топ-20) на найкращій нарізці.               0 запитів
 *  Крок 4б. Ваш важіль пошуку (MyRetrievalTweak): модель переписує питання мовою положень, пошук «до / після».   9 генерацій + 1 пакет
 *  Крок 5.  Відповідь з цитатами на 12 питань: гібридний топ-5, {answer, cited_ids, answerable}, валідатор і повтор;
 *           effort minimal і high.                                                                             24 генерації (+ повтори)
 *  Крок 6.  Ваші 3 питання (MyQuestions.java): пошук і відповідь minimal / high.                               1 пакет + 6 генерацій
 * </pre>
 * Кожен запуск ПЕРЕЗАПИСУЄ target/lab4-results.md (MOCK — lab4-results-mock.md): здавайте останній прогін, у якому вже є
 * ваші питання, ваш важіль пошуку і (за бажанням) бонус.
 * Кеші: target/lab4-corpus/ (тексти), target/lab4-embeddings.tsv (ваші ембединги), target/lab4-cache.json (відповіді моделі);
 * кеш ембедингів викладача — ресурс lab4/embeddings-cache.bin.gz.
 *
 * <p>Налаштування (.env): LAB_VARIANT=1…8, GEMINI_API_KEY (обов'язково — для ембедингів, навіть якщо LAB_PROVIDER=claude),
 * LAB_DELAY_MS=4000, LAB_EMBED_TPM (токенів ембедингів на хвилину, за замовчуванням 30000), LAB_NO_CACHE=1, LAB4_PDF_DIR, LAB4_OFFLINE=1,
 * LAB_BM25_MORFOLOGIK=1 (бонус), LAB_EMBED_MODEL, LAB_EMBED_BATCH. Без ключа — LAB_MOCK=1. temperature не надсилається.
 */
public final class Lab4Run {

    public static final int TOP_K = RetrievalMetrics.K;

    /** Підсумок прогону — для тестів і документів. */
    public record Result(Map<Chunker.Strategy, Chunker.Stats> chunkStats, Map<Chunker.Strategy, List<Integer>> vectorRanks,
                         List<Integer> noPrefixRanks, Chunker.Strategy best, List<Integer> bm25Ranks, List<Integer> hybridRanks,
                         List<Integer> bm25MorfRanks, List<Integer> hybridMorfRanks, Tweak tweak, List<String> excluded,
                         List<AutoCheck> minimal, List<AutoCheck> high, int myQuestions, int newCalls, int cachedCalls,
                         int embedRequests, int embedNew, int embedCached, int embedFromBundle, String markdown) {
    }

    /** Результат кроку 4б: місця до / після переписування питання (null — крок не виконано). */
    public record Tweak(List<String> rewritten, List<Integer> vecBefore, List<Integer> vecAfter, List<Integer> bmBefore, List<Integer> bmAfter,
                        List<Integer> hyBefore, List<Integer> hyAfter) {
    }

    private Lab4Run() {
    }

    // java.util.logging тримає логери слабкими посиланнями — тримаємо самі, щоб рівень не скинувся
    private static final java.util.logging.Logger LUCENE_LOG = java.util.logging.Logger.getLogger("org.apache.lucene");
    private static final java.util.logging.Logger PDFBOX_LOG = java.util.logging.Logger.getLogger("org.apache.pdfbox");

    /** Прибирає службові попередження Lucene («Java vector incubator module…») і PDFBox (кеш шрифтів) з консолі студента. */
    static void quietLibraries() {
        LUCENE_LOG.setLevel(java.util.logging.Level.SEVERE);
        PDFBOX_LOG.setLevel(java.util.logging.Level.SEVERE);
    }

    /** LAB_EMBED_TPM з .env (токенів ембедингів на хвилину), за замовчуванням {@link Embedder#DEFAULT_TPM}; 0 — без обмеження. */
    static int tpm() {
        String s = Config.setting("LAB_EMBED_TPM");
        try {
            return s == null ? Embedder.DEFAULT_TPM : Math.max(0, Integer.parseInt(s.strip()));
        } catch (NumberFormatException e) {
            return Embedder.DEFAULT_TPM;
        }
    }

    /** Пояснення помилки API для студента: добовий ліміт — окремо. */
    static String apiErrorText(ApiException e) {
        if (e.dailyQuota()) {
            return "\n  !!! Добовий ліміт безплатного тарифу на сьогодні вичерпано (HTTP 429: " + Report.clip(e.getMessage(), 160) + ").\n"
                    + "  Продовжіть завтра: усе вже отримане збережено в кешах (target/lab4-embeddings.tsv, target/lab4-cache.json),\n"
                    + "  і наступний запуск почне з того самого місця.";
        }
        return "\n  !!! Запит до API завершився помилкою: " + e.getMessage() + "\n"
                + "  Усе вже отримане збережено в кешах (target/lab4-embeddings.tsv, target/lab4-cache.json). Зачекайте хвилину\n"
                + "  й запустіть ще раз — прогін продовжиться з того самого місця, а готове не витрачатиме квоту.\n"
                + "  Часті 429 → збільште LAB_DELAY_MS (наприклад, до 6000) або зменште LAB_EMBED_TPM у .env.";
    }

    public static void main(String[] args) {
        Console.init();
        quietLibraries();
        boolean mock = Config.isTrueSetting("LAB_MOCK");
        String rawVariant = Config.setting("LAB_VARIANT");
        Lab4Variant variant;
        try {
            int n = Lab4Variant.resolve(rawVariant, mock);
            variant = mock ? Lab4Variant.mock(n) : Lab4Variant.load(n);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }
        String geminiKey = mock ? "mock-key" : Config.geminiKey();
        if (geminiKey == null) {
            System.err.println("""

                    Не знайдено GEMINI_API_KEY — він потрібен для ембедингів (gemini-embedding-2) НАВІТЬ тоді, коли відповіді генерує Claude.
                      1) створіть ключ на https://aistudio.google.com/apikey (безкоштовно);
                      2) впишіть його у файл .env після GEMINI_API_KEY=;
                      3) або перевірте запуск без ключа: конфігурація «Лаба 4 (MOCK)» (LAB_MOCK=1).
                    """);
            System.exit(1);
            return;
        }
        int exit = 0;
        try (LlmClient client = LlmClient.create()) {
            if (mock && (rawVariant == null || rawVariant.isBlank())) {
                System.out.println("  (LAB_VARIANT не задано — у режимі MOCK беремо варіант 1; для справжнього прогону задайте свій у .env)");
            }
            Corpus corpus = Corpus.load(mock);
            List<Corpus.Text> docs;
            String corpusInfo;
            if (mock) {
                docs = CorpusLoader.loadMock(corpus);
                corpusInfo = "3 вигадані положення «Умовного університету» з ресурсів (MOCK)";
            } else {
                CorpusLoader loader = CorpusLoader.fromSettings();
                System.out.println("  Завантажую положення (" + corpus.documents().size() + " шт.): кеш target/lab4-corpus → папка " + loader.pdfDir()
                        + " → efund.cnu.edu.ua …");
                docs = loader.load(corpus);
                CorpusLoader.Stats st = loader.stats();
                corpusInfo = corpus.documents().size() + " положень: з кешу тексту " + st.fromCache() + ", з PDF у папці " + st.fromFolder()
                        + ", завантажено " + st.downloaded() + (st.changed().isEmpty() ? ""
                        : "; УВАГА: PDF змінився з " + corpus.checked() + " — " + String.join(", ", st.changed())
                        + " (питання, чиїх еталонних фрагментів немає в новому тексті, виключено з метрик — див. «Перевірка еталону»)");
            }
            String baseUrl = mock ? FakeLlmServer.startOnce().baseUrl() : Config.geminiBaseUrl();
            String model = Config.setting("LAB_EMBED_MODEL") == null ? Embedder.DEFAULT_MODEL : Config.setting("LAB_EMBED_MODEL");
            int batch = parseInt(Config.setting("LAB_EMBED_BATCH"), Embedder.DEFAULT_BATCH);
            boolean noCache = Config.isTrueSetting("LAB_NO_CACHE");
            Embedder embedder = new Embedder(baseUrl, geminiKey.strip(), model, Embedder.DIM, batch, client.requestDelayMs(),
                    EmbeddingCache.fromSettings(noCache, mock), EmbeddingBundle.fromResources(mock, model, Embedder.DIM), mock ? 0 : tpm());
            AnswerCache cache = new AnswerCache(Path.of("target", mock ? "lab4-cache-mock.json" : "lab4-cache.json"), !noCache);
            Path results = Path.of("target", mock ? "lab4-results-mock.md" : "lab4-results.md");
            run(client, embedder, cache, variant, Lab4Variant.common(mock), docs, corpusInfo, MyQuestions.current(), MyRetrievalTweak.current(),
                    Config.isTrueSetting("LAB_BM25_MORFOLOGIK"), results);
        } catch (IllegalStateException e) {
            System.out.println(e.getMessage());
            exit = 1;
        } catch (ApiException e) {
            System.out.println(apiErrorText(e));
            exit = 1;
        }
        if (exit != 0) System.exit(exit);
    }

    private static int parseInt(String s, int dflt) {
        try {
            return s == null ? dflt : Math.max(1, Integer.parseInt(s.strip()));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    // =====================================================================================================================

    /** Увесь прогін (без main — для тестів). rewritePrompt — промпт кроку 4б (порожньо — крок не виконується). */
    public static Result run(LlmClient client, Embedder emb, AnswerCache cache, Lab4Variant v, Lab4Variant.Question common,
                             List<Corpus.Text> docs, String corpusInfo, List<MyQuestions.Mine> mine, String rewritePrompt, boolean morfologik,
                             Path resultsFile) {
        Answerer ans = new Answerer(client, cache);
        Report r = new Report();
        Map<String, Corpus.Text> byDoc = new LinkedHashMap<>();
        for (Corpus.Text d : docs) byDoc.put(d.doc().id(), d);
        int totalChars = docs.stream().mapToInt(Corpus.Text::length).sum();

        Console.banner("Лабораторна 4. Бот знаходить пункт положення (" + client.provider() + ", " + client.model() + ")");
        r.h1("Лабораторна 4 — результати прогону");
        r.file("Модель відповідей: " + client.provider() + " " + client.model() + (client.isMock() ? " (MOCK — імітація, у звіт не йде)" : "")
                + " · ембединги: " + emb.model() + ", " + emb.dim() + " чисел · варіант " + v.variant() + " (" + v.title() + ") · " + LocalDate.now());
        r.kv("варіант", v.variant() + " з " + Lab4Variant.COUNT + ": " + v.title());
        r.kv("корпус", corpusInfo + "; разом " + String.format(Locale.ROOT, "%,d", totalChars).replace(',', ' ') + " знаків");
        r.kv("ембединги", emb.model() + ", " + emb.dim() + " чисел, пакет до " + emb.batchSize() + " текстів"
                + (emb.tpm() > 0 ? ", не більше " + emb.tpm() + " токенів на хвилину (LAB_EMBED_TPM)" : ""));
        EmbeddingBundle b = emb.bundle();
        r.kv("кеш викладача", b == null ? "немає — шматки індексуються вашим ключем (≈2,7 тис. текстів; безплатний ліміт ≈1000 текстів на добу — кілька днів, кеш продовжує)"
                : b.size() + " векторів (" + b.model() + ", " + b.dim() + ", " + b.created() + ") — індексація без запитів");
        r.kv("ваш кеш ембедингів", emb.cache().diskEnabled() ? emb.cache().file() + " (уже є: " + emb.cache().size() + ")" : "вимкнено (LAB_NO_CACHE=1)");
        r.kv("кеш відповідей", cache.diskEnabled() ? cache.file() + " (уже є: " + cache.size() + ")" : "вимкнено (LAB_NO_CACHE=1)");
        r.kv("пауза між запитами", client.requestDelayMs() + " мс (LAB_DELAY_MS; лише перед новими запитами)");
        r.kv("temperature", "не надсилається — значення моделі за замовчуванням; рівень міркування — effort minimal / high");
        r.endList();
        r.file("## Як читати цей файл\n\n" + LEGEND);
        r.console("Кожен запуск перезаписує " + (resultsFile == null ? "файл результатів" : resultsFile) + " — здавайте ОСТАННІЙ прогін (з вашими питаннями, "
                + "вашим важелем пошуку і бонусом).");
        r.console("Терміни й позначення — у легенді на початку файлу результатів.");

        // ---------------------------------------------------------------- перевірка еталону
        r.section("Перевірка еталону: чи є еталонні фрагменти в завантажених положеннях");
        List<String> excluded = new ArrayList<>();
        List<Lab4Variant.Question> all = new ArrayList<>();
        for (Lab4Variant.Question q : v.questions()) {
            if (q.answerable() && !goldPresent(q, byDoc)) {
                excluded.add(q.shortId());
                continue;
            }
            all.add(q);
        }
        List<Lab4Variant.Question> gold = all.stream().filter(Lab4Variant.Question::answerable).toList();
        if (excluded.isEmpty()) {
            r.p("Усі еталонні фрагменти вашого варіанта знайдено в тексті положень — у метриках " + gold.size() + " питань з відповіддю і "
                    + (all.size() - gold.size()) + " без відповіді.");
        } else {
            for (String id : excluded) r.p("Положення оновилось — питання " + id + " виключено з метрик: його еталонного фрагмента немає в новому тексті.");
            r.p("У метриках лишилось " + gold.size() + " питань з відповіддю; напишіть про це у звіті (числа не порівнюйте з однокурсниками напряму).");
        }

        // ---------------------------------------------------------------- крок 1
        r.section("Крок 1. Без RAG: модель відповідає з пам'яті, без документів");
        r.what("п'ять питань (спільне для всіх і чотири вашого варіанта); модель відповідає без положень, з мінімальним міркуванням."
                        + " Еталон — документ і дослівний фрагмент — показано ПІСЛЯ відповіді.",
                "скільки правил модель вигадала, коли не бачила документів?");
        int c0 = ans.newCalls(), k0 = ans.cachedCalls();
        StringBuilder t1 = new StringBuilder("| № | Питання | Відповідь моделі (без документів) | Еталон: положення і фрагмент | Ключові факти у відповіді |\n|---|---|---|---|---|\n");
        for (Lab4Variant.Question q : v.step1(common)) {
            Answerer.Outcome o = ans.noRag(q.text());
            String a = o.answer() == null ? "(не розібрано: " + o.errors() + ")" : o.answer().answer();
            int facts = 0;
            for (String f : q.keyFacts()) if (o.answer() != null && TextNorm.hasFact(a, f)) facts++;
            r.console("[" + q.shortId() + "] " + q.text());
            r.console("     модель: " + a);
            r.console("     еталон: " + title(docs, q.doc()) + " — «" + String.join("» … «", q.fragments()) + "»; ключові факти: "
                    + String.join(", ", q.keyFacts()) + " (у відповіді " + facts + "/" + q.keyFacts().size() + ")");
            t1.append("| ").append(q.shortId()).append(" | ").append(Report.cell(q.text())).append(" | ").append(Report.cell(a)).append(" | ")
                    .append(Report.cell(title(docs, q.doc()))).append(": «").append(Report.cell(String.join("» … «", q.fragments()))).append("» | ")
                    .append(facts).append("/").append(q.keyFacts().size()).append(" |\n");
        }
        r.file(t1.toString().stripTrailing());
        r.how(List.of(
                "«Відповідь моделі» — що сказала модель, не бачивши жодного положення (мінімальне міркування).",
                "«Еталон» — положення і дослівний фрагмент з відповіддю (… — пропуск між двома фрагментами).",
                "«Ключові факти у відповіді» — скільки ключових слів чи чисел еталону є у відповіді (k/n), шукаємо з початку слова. Це лише підказка: "
                        + "збіг слова ще не означає правильну відповідь, а відсутність — не завжди вигадку. Вирішуєте ви."));
        r.example("якщо на «" + common.text() + "» модель відповіла «тричі викладачеві», а еталон каже «не більше двох разів: один раз викладачеві, другий — "
                + "комісії», — це вигадане правило: звучить упевнено, але не збігається з положенням (слайд 2 лекції 4).");
        r.p("У звіті позначте кожну з 5 відповідей: правда / частково / вигадка — і порахуйте вигадки.");
        calls(r, ans, c0, k0, "генерацій");

        // ---------------------------------------------------------------- крок 2
        r.section("Крок 2. Нарізка на шматки й індексація");
        Map<Chunker.Strategy, List<Chunk>> chunks = new LinkedHashMap<>();
        Map<Chunker.Strategy, Chunker.Stats> stats = new LinkedHashMap<>();
        for (Chunker.Strategy s : Chunker.Strategy.values()) {
            chunks.put(s, Chunker.chunk(docs, s));
            stats.put(s, Chunker.stats(chunks.get(s)));
        }
        r.what("три способи розбити " + docs.size() + " положень на шматки (слайди 10–11). (а) — по " + Chunker.SIZE + " знаків, кожен наступний на "
                        + Chunker.STEP + " далі (перекриття " + Chunker.OVERLAP + "); (б) — за пунктами «N.», «N.N.», «N.N.N.» (номер пункту лишається з "
                        + "його текстом; довгий пункт — на шматки до " + Chunker.SIZE + " знаків по межах речень; уламки коротші за " + Chunker.MIN_CHUNK
                        + " знаків відкидаються); (в) — ті самі шматки, що (б), але з адресою: у префіксі title — назва положення, а текст починається "
                        + "з назви розділу (лише справжні заголовки рівня 1, без титулки й передмови).",
                "скільки шматків і якої довжини дає кожен спосіб і чи ріже фіксована нарізка правила вашого варіанта?");
        StringBuilder t2 = new StringBuilder("| Нарізка | Шматків | Середня довжина, знаків | Найдовший, знаків |\n|---|---|---|---|\n");
        for (Chunker.Strategy s : Chunker.Strategy.values()) {
            Chunker.Stats st = stats.get(s);
            t2.append("| ").append(s.label()).append(" | ").append(st.count()).append(" | ").append(Math.round(st.avgLen())).append(" | ").append(st.maxLen()).append(" |\n");
        }
        r.table(t2.toString());
        StringBuilder pre = new StringBuilder("Що йде в ембединг (початок тексту з префіксом документа):");
        for (Chunker.Strategy s : Chunker.Strategy.values()) {
            String et = example(chunks.get(s), s).embedText().replace('\n', ' ');
            pre.append("\n    ").append(s.label()).append(": «").append(et.length() > 160 ? et.substring(0, 159) + "…" : et).append("»");
        }
        r.p(pre.toString());
        r.how(List.of(
                "«Шматків» — скільки шматків (і скільки ембедингів) дає нарізка на всьому корпусі.",
                "«Середня / найдовший» — довжина тексту шматка в знаках (без префікса). Фіксована майже завжди " + Chunker.SIZE
                        + "; за пунктами — різна: короткі пункти лишаються короткими.",
                "Під таблицею — що йде в ембединг: текст шматка з префіксом документа gemini-embedding-2 (слайд 9): «title: none | text: …» "
                        + "для (а) і (б), «title: назва положення | text: Розділ: … » для (в)."));
        r.sub("Чи цілі правила вашого варіанта у фіксованій нарізці (а)");
        r.what("для кожного питання з відповіддю — пункт положення (шматок (б)), де стоїть еталон, і чи вмістився він цілим в один шматок (а).",
                "де фіксована нарізка розірвала правило вашого варіанта?");
        StringBuilder tr = new StringBuilder("| № | Пункт (шматок (б)) | Довжина, знаків | Цілим в одному шматку (а)? | Де розірвано |\n|---|---|---|---|---|\n");
        String tornShown = null;
        for (Lab4Variant.Question q : gold) {
            Chunk rule = chunks.get(Chunker.Strategy.POINTS).stream().filter(q::matches).findFirst().orElse(null);
            if (rule == null) {
                tr.append("| ").append(q.shortId()).append(" | — | — | — | фрагмент не вміщується в жоден шматок (б) |\n");
                continue;
            }
            List<Chunk> fixed = chunks.get(Chunker.Strategy.FIXED).stream().filter(c -> c.docId().equals(rule.docId())).toList();
            String nr = TextNorm.normalize(rule.text());
            boolean whole = fixed.stream().anyMatch(c -> TextNorm.normalize(c.text()).contains(nr));
            String where = "";
            if (!whole) {
                String head = nr.substring(0, Math.min(60, nr.length())), tail = nr.substring(Math.max(0, nr.length() - 60));
                Chunk a = fixed.stream().filter(c -> TextNorm.normalize(c.text()).contains(head)).findFirst().orElse(null);
                Chunk z = fixed.stream().filter(c -> TextNorm.normalize(c.text()).contains(tail)).reduce((x, y) -> y).orElse(null);
                where = "початок у " + (a == null ? "?" : a.id()) + ", кінець у " + (z == null ? "?" : z.id());
                if (tornShown == null && a != null && z != null && !a.id().equals(z.id())) {
                    String ta = a.text();
                    tornShown = "Приклад з вашого варіанта (питання " + q.shortId() + "): пункт " + rule.id() + " у нарізці (а) розірвано — кінець шматка " + a.id()
                            + ": «…" + Report.cell(ta.substring(Math.max(0, ta.length() - 140))) + "»; продовження — у " + z.id() + ".";
                }
            }
            tr.append("| ").append(q.shortId()).append(" | ").append(rule.id()).append(" | ").append(rule.length()).append(" | ")
                    .append(whole ? "так" : "ні").append(" | ").append(where).append(" |\n");
        }
        r.table(tr.toString());
        r.how(List.of("«Пункт» — id шматка (б), який містить еталонний фрагмент: це ціле правило.",
                "«Цілим в одному шматку (а)?» — «ні», якщо фіксована нарізка поклала початок правила в один шматок, а кінець — в інший "
                        + "(або правило довше за " + Chunker.SIZE + " знаків і цілим не вміститься ніде).",
                "«Де розірвано» — id шматків (а), де лежать початок і кінець правила; відкрийте їх у target/lab4-corpus/ (Ctrl+Shift+F) і подивіться межу."));
        r.p(tornShown != null ? tornShown : "На вашому варіанті фіксована нарізка не розірвала жодного правила з еталоном — це теж результат: подивіться, "
                + "які правила короткі, а які довгі.");

        r.sub("Індексація: ембединги всіх шматків трьох нарізок");
        Map<Chunker.Strategy, List<float[]>> vecs = new LinkedHashMap<>();
        StringBuilder ti = new StringBuilder("| Нарізка | Текстів | З кешу викладача | З вашого кешу | Нових з API | Запитів batchEmbedContents |\n|---|---|---|---|---|---|\n");
        int newIdx = 0;
        for (Chunker.Strategy s : Chunker.Strategy.values()) {
            int rq = emb.requests(), ne = emb.embedded(), ca = emb.cached(), fb = emb.fromBundle();
            vecs.put(s, emb.embed(chunks.get(s).stream().map(Chunk::embedText).toList()));
            newIdx += emb.embedded() - ne;
            ti.append("| ").append(s.label()).append(" | ").append(chunks.get(s).size()).append(" | ").append(emb.fromBundle() - fb).append(" | ")
                    .append(emb.cached() - ca).append(" | ").append(emb.embedded() - ne).append(" | ").append(emb.requests() - rq).append(" |\n");
            System.out.println("    " + s.label() + ": готово");
        }
        r.table(ti.toString());
        r.how(List.of(
                "«Текстів» — шматків нарізки (для кожного потрібен ембединг).",
                "«З кешу викладача» — готові вектори з проєкту (lab4/embeddings-cache.bin.gz): шматки однакові для всіх варіантів, тож їх порахували один раз.",
                "«З вашого кешу» — з " + emb.cache().file() + " (ваші попередні запуски); «Нових з API» — пораховано зараз вашим ключем.",
                "«Запитів batchEmbedContents» — пакетних HTTP-запитів (до " + emb.batchSize() + " текстів і до LAB_EMBED_TPM токенів за хвилину)."));
        if (newIdx > 0 && b != null) {
            r.p("Увага: " + newIdx + " шматків пораховано заново — у кеші викладача їх немає (найімовірніше, положення оновилось після " + b.created() + ").");
        } else if (newIdx > 0) {
            r.p("Кешу викладача немає — шматки проіндексовано вашим ключем; наступні запуски візьмуть їх з вашого кешу.");
        }
        if (emb.waitedMs() > 0) r.p(String.format(Locale.ROOT, "Пауза через ліміт токенів: %.0f с разом.", emb.waitedMs() / 1000.0));

        // ---------------------------------------------------------------- крок 3
        r.section("Крок 3. Векторний пошук: косинус питання з кожним шматком");
        int rq3 = emb.requests();
        List<float[]> qVec = emb.embed(all.stream().map(q -> Prefixes.query(q.text())).toList());
        List<float[]> qBare = emb.embed(gold.stream().map(Lab4Variant.Question::text).toList());
        Map<String, float[]> qv = new LinkedHashMap<>();
        for (int i = 0; i < all.size(); i++) qv.put(all.get(i).id(), qVec.get(i));
        Map<Chunker.Strategy, List<Integer>> ranks = new LinkedHashMap<>();
        Map<Chunker.Strategy, VectorSearch> vs = new LinkedHashMap<>();
        Map<Chunker.Strategy, List<List<VectorSearch.Hit>>> tops = new LinkedHashMap<>();
        for (Chunker.Strategy s : Chunker.Strategy.values()) {
            VectorSearch search = new VectorSearch(chunks.get(s), vecs.get(s));
            vs.put(s, search);
            List<Integer> rk = new ArrayList<>();
            List<List<VectorSearch.Hit>> tp = new ArrayList<>();
            for (Lab4Variant.Question q : gold) {
                List<VectorSearch.Hit> h = search.search(qv.get(q.id()), TOP_K);
                tp.add(h);
                rk.add(RetrievalMetrics.rank(h, q, TOP_K));
            }
            ranks.put(s, rk);
            tops.put(s, tp);
        }
        Chunker.Strategy best = best(ranks);
        List<Integer> bare = new ArrayList<>();
        List<List<VectorSearch.Hit>> bareTop = new ArrayList<>();
        for (int i = 0; i < gold.size(); i++) {
            List<VectorSearch.Hit> h = vs.get(best).search(qBare.get(i), TOP_K);
            bareTop.add(h);
            bare.add(RetrievalMetrics.rank(h, gold.get(i), TOP_K));
        }
        r.what("на " + gold.size() + " питаннях з відповіддю шукаємо 5 найближчих шматків (повний косинус, перебір усіх шматків) і дивимось, чи є серед них "
                        + "шматок з еталонним фрагментом (для «двох пунктів» — усі фрагменти). Питання — з префіксом «task: search result | query: …»; "
                        + "останній рядок — найкраща нарізка, але питання без префікса (шматки ті самі).",
                "яка нарізка дає вищі recall@5 і MRR@5, чи допомагає заголовок ((в) проти (б)) і чи допомагає префікс питання?");
        StringBuilder t3 = new StringBuilder("| Нарізка | recall@5 | MRR@5 | Порівняно з (а) |\n|---|---|---|---|\n");
        List<Integer> base = ranks.get(Chunker.Strategy.FIXED);
        for (Chunker.Strategy s : Chunker.Strategy.values()) {
            List<Integer> rk = ranks.get(s);
            t3.append("| ").append(s.label()).append(s == best ? " ← найкраща" : "").append(" | ").append(RetrievalMetrics.recallText(rk)).append(" | ")
                    .append(RetrievalMetrics.num(RetrievalMetrics.mrr(rk))).append(" | ")
                    .append(s == Chunker.Strategy.FIXED ? "база" : RetrievalMetrics.verdict(RetrievalMetrics.found(rk), RetrievalMetrics.found(base), gold.size()))
                    .append(" |\n");
        }
        t3.append("| ").append(best.label()).append(", питання БЕЗ префікса | ").append(RetrievalMetrics.recallText(bare)).append(" | ")
                .append(RetrievalMetrics.num(RetrievalMetrics.mrr(bare))).append(" | проти тієї ж нарізки з префіксом: ")
                .append(RetrievalMetrics.verdict(RetrievalMetrics.found(bare), RetrievalMetrics.found(ranks.get(best)), gold.size())).append(" |\n");
        r.table(t3.toString());
        r.p("(в) проти (б) — чи допомагає заголовок у шматку: " + RetrievalMetrics.verdict(RetrievalMetrics.found(ranks.get(Chunker.Strategy.POINTS_HEADER)),
                RetrievalMetrics.found(ranks.get(Chunker.Strategy.POINTS)), gold.size()) + "; MRR@5 " + RetrievalMetrics.num(RetrievalMetrics.mrr(ranks.get(Chunker.Strategy.POINTS_HEADER)))
                + " проти " + RetrievalMetrics.num(RetrievalMetrics.mrr(ranks.get(Chunker.Strategy.POINTS))) + ".");
        r.how(HOW_METRICS);
        r.example(exampleRecall(best, ranks.get(best), gold.size()));
        StringBuilder t3q = new StringBuilder("| № | Питання | Тип | (а) | (б) | (в) | " + best.letter + " без префікса |\n|---|---|---|---|---|---|---|\n");
        for (int i = 0; i < gold.size(); i++) {
            Lab4Variant.Question q = gold.get(i);
            t3q.append("| ").append(q.shortId()).append(" | ").append(Report.clip(q.text(), 70)).append(" | ").append(q.kind()).append(" | ")
                    .append(RetrievalMetrics.rankText(ranks.get(Chunker.Strategy.FIXED).get(i))).append(" | ")
                    .append(RetrievalMetrics.rankText(ranks.get(Chunker.Strategy.POINTS).get(i))).append(" | ")
                    .append(RetrievalMetrics.rankText(ranks.get(Chunker.Strategy.POINTS_HEADER).get(i))).append(" | ")
                    .append(RetrievalMetrics.rankText(bare.get(i))).append(" |\n");
        }
        r.sub("Місце правильного шматка для кожного питання");
        r.table(t3q.toString());
        r.how(List.of("Число — місце, на якому серед 5 знайдених з'явився еталонний фрагмент (для «двох пунктів» — останній з двох; 1 — найвище); «—» — "
                        + "у п'ятірці його немає.",
                "«Тип» — чим питання складне: «синонім» — інші слова, ніж у положенні; «код» — номер, код, скорочення; «два пункти» — відповідь у двох місцях; "
                        + "«пряме» — слова питання є в тексті положення."));
        r.sub("Промахи: що пошук знайшов замість (крок 3)");
        r.p("Для кожного питання, де правильного шматка немає в п'ятірці, — id і початок трьох шматків, які пошук поставив найвище. "
                + "У консолі — для найкращої нарізки, у файлі — для всіх трьох і для запиту без префікса.");
        for (Chunker.Strategy s : Chunker.Strategy.values()) misses(r, "вектор, " + s.label(), gold, ranks.get(s), tops.get(s), s == best);
        misses(r, "вектор, " + best.label() + ", без префікса", gold, bare, bareTop, false);
        r.p("Найкраща нарізка: " + best.label() + ". Правило вибору: вищий recall@5; за рівності — вищий MRR@5; за повної рівності — (в), потім (б): "
                + "шматок з адресою корисніший моделі в кроці 5. Далі (кроки 4–6) працюємо з нею.");
        r.console("Запитів ембедингів для питань: " + (emb.requests() - rq3) + " (з префіксом і без).");

        // ---------------------------------------------------------------- крок 4
        r.section("Крок 4. BM25 і гібрид RRF (нарізка " + best.label() + ")");
        List<Integer> bmR = new ArrayList<>(), hyR = new ArrayList<>(), bmMR = null, hyMR = null;
        List<List<VectorSearch.Hit>> bmTop = new ArrayList<>(), hyTop = new ArrayList<>();
        Map<String, List<Chunk>> top5 = new LinkedHashMap<>();
        String analyzerName;
        String exampleTerms;
        try (Bm25Search bm = new Bm25Search(chunks.get(best), false)) {
            analyzerName = bm.analyzerName();
            exampleTerms = String.join(", ", bm.terms(gold.isEmpty() ? common.text() : gold.get(0).text()));
            for (Lab4Variant.Question q : all) {
                List<VectorSearch.Hit> vh = vs.get(best).search(qv.get(q.id()), Rrf.DEPTH);
                List<VectorSearch.Hit> bh = bm.search(q.text(), Rrf.DEPTH);
                List<VectorSearch.Hit> hy = Rrf.fuse(List.of(vh, bh), TOP_K);
                top5.put(q.id(), hy.stream().map(VectorSearch.Hit::chunk).toList());
                if (q.answerable()) {
                    bmR.add(RetrievalMetrics.rank(bh, q, TOP_K));
                    hyR.add(RetrievalMetrics.rank(hy, q, TOP_K));
                    bmTop.add(bh.subList(0, Math.min(TOP_K, bh.size())));
                    hyTop.add(hy);
                }
            }
        }
        if (morfologik) {
            bmMR = new ArrayList<>();
            hyMR = new ArrayList<>();
            try (Bm25Search bm = new Bm25Search(chunks.get(best), true)) {
                for (Lab4Variant.Question q : gold) {
                    List<VectorSearch.Hit> bh = bm.search(q.text(), Rrf.DEPTH);
                    bmMR.add(RetrievalMetrics.rank(bh, q, TOP_K));
                    hyMR.add(RetrievalMetrics.rank(Rrf.fuse(List.of(vs.get(best).search(qv.get(q.id()), Rrf.DEPTH), bh), TOP_K), q, TOP_K));
                }
            } catch (IllegalStateException e) {
                r.p("Бонус: " + e.getMessage());
                bmMR = null;
                hyMR = null;
            }
        }
        r.what("той самий набір питань, три способи пошуку на найкращій нарізці: вектор (крок 3), BM25 — пошук за словами (Lucene, BM25Similarity k1 = "
                        + "1,2, b = 0,75; аналізатор — " + analyzerName + "; індексуємо текст шматка з назвою розділу, без назви положення), і гібрид: RRF "
                        + "зводить два списки за місцями, кожне місце дає 1 / (60 + місце) балів (з кожного списку — топ-" + Rrf.DEPTH + "). Нових запитів 0.",
                "чи знаходить пошук за словами те, що пропускає вектор, і чи бере гібрид найкраще з обох?");
        List<Integer> vecR = ranks.get(best);
        StringBuilder t4 = new StringBuilder("| Спосіб пошуку | recall@5 | MRR@5 | Порівняно з вектором |\n|---|---|---|---|\n");
        t4.append(row4("вектор (косинус)", vecR, null, gold.size()));
        t4.append(row4("BM25 (слова)", bmR, vecR, gold.size()));
        t4.append(row4("гібрид RRF", hyR, vecR, gold.size()));
        if (bmMR != null) {
            t4.append(row4("BM25, морфологія (бонус)", bmMR, vecR, gold.size()));
            t4.append(row4("гібрид RRF, морфологія (бонус)", hyMR, vecR, gold.size()));
        }
        r.table(t4.toString());
        r.how(List.of(
                "«recall@5», «MRR@5» — як у кроці 3 (на тих самих " + gold.size() + " питаннях).",
                "«BM25» — шматок отримує бали за кожне слово запиту, яке в ньому є; рідкісні слова важать більше; синонімів BM25 не знає.",
                "«гібрид RRF» — сума балів 1 / (60 + місце) з обох списків: шматок, високий в обох, піднімається.",
                "«Порівняно з вектором» — різниця у знайдених питаннях; різниця в одне питання (≈ 11 п.п.) — висновку немає."
                        + (bmMR == null ? " Бонус (лематизація): LAB_BM25_MORFOLOGIK=1 у .env і запуск ще раз (0 нових запитів)." : "")));
        r.console("Як BM25 бачить питання «" + (gold.isEmpty() ? common.text() : gold.get(0).text()) + "»: слова " + exampleTerms);
        StringBuilder t4q = new StringBuilder("| № | Питання | Тип | вектор | BM25 | гібрид |" + (bmMR != null ? " BM25 морф. | гібрид морф. |" : "")
                + "\n|---|---|---|---|---|---|" + (bmMR != null ? "---|---|" : "") + "\n");
        for (int i = 0; i < gold.size(); i++) {
            Lab4Variant.Question q = gold.get(i);
            t4q.append("| ").append(q.shortId()).append(" | ").append(Report.clip(q.text(), 70)).append(" | ").append(q.kind()).append(" | ")
                    .append(RetrievalMetrics.rankText(vecR.get(i))).append(" | ").append(RetrievalMetrics.rankText(bmR.get(i))).append(" | ")
                    .append(RetrievalMetrics.rankText(hyR.get(i))).append(" |");
            if (bmMR != null) t4q.append(" ").append(RetrievalMetrics.rankText(bmMR.get(i))).append(" | ").append(RetrievalMetrics.rankText(hyMR.get(i))).append(" |");
            t4q.append("\n");
        }
        r.sub("Місце правильного шматка: вектор, BM25, гібрид");
        r.table(t4q.toString());
        r.example(example4(gold, vecR, bmR, hyR));
        r.sub("Промахи: що пошук знайшов замість (крок 4)");
        misses(r, "BM25", gold, bmR, bmTop, true);
        misses(r, "гібрид RRF", gold, hyR, hyTop, true);

        // ---------------------------------------------------------------- крок 4б
        r.section("Крок 4б. Ваш важіль пошуку: питання мовою положень (src/main/java/ua/knu/promptlab/lab4/MyRetrievalTweak.java)");
        Tweak tweak = null;
        if (rewritePrompt == null || rewritePrompt.isBlank()) {
            r.p("Ви ще не написали промпт (MyRetrievalTweak порожній) — нових запитів 0.");
            r.p("Що зробити: допишіть у MyRetrievalTweak.REWRITE_PROMPT свій промпт, який переписує питання студента мовою положень (офіційні терміни, "
                    + "без вигадування відповіді), і запустіть ще раз: " + gold.size() + " генерацій і 1 пакет ембедингів, решта — з кешу.");
        } else {
            c0 = ans.newCalls();
            k0 = ans.cachedCalls();
            List<String> rewritten = new ArrayList<>();
            for (Lab4Variant.Question q : gold) rewritten.add(ans.rewrite(rewritePrompt, q.text()));
            List<float[]> rv = emb.embed(rewritten.stream().map(Prefixes::query).toList());
            List<Integer> va = new ArrayList<>(), ba = new ArrayList<>(), ha = new ArrayList<>();
            try (Bm25Search bm = new Bm25Search(chunks.get(best), false)) {
                for (int i = 0; i < gold.size(); i++) {
                    Lab4Variant.Question q = gold.get(i);
                    List<VectorSearch.Hit> vh = vs.get(best).search(rv.get(i), Rrf.DEPTH);
                    List<VectorSearch.Hit> bh = bm.search(rewritten.get(i), Rrf.DEPTH);
                    va.add(RetrievalMetrics.rank(vh, q, TOP_K));
                    ba.add(RetrievalMetrics.rank(bh, q, TOP_K));
                    ha.add(RetrievalMetrics.rank(Rrf.fuse(List.of(vh, bh), TOP_K), q, TOP_K));
                }
            }
            tweak = new Tweak(rewritten, vecR, va, bmR, ba, hyR, ha);
            r.what("перед пошуком модель переписує кожне з " + gold.size() + " питань за ВАШИМ промптом мовою положень; шукаємо переписаним питанням "
                            + "на тій самій нарізці " + best.label() + " і порівнюємо з кроками 3–4 («до»).",
                    "чи допомагає переклад студентської мови на мову положень векторному пошуку, BM25 і гібриду — більше, ніж на одне питання?");
            r.p("Ваш промпт: «" + Report.clip(rewritePrompt, 300) + "»");
            StringBuilder tb = new StringBuilder("| Спосіб пошуку | до: recall@5 · MRR@5 | після: recall@5 · MRR@5 | Висновок |\n|---|---|---|---|\n");
            tb.append(rowTweak("вектор", vecR, va, gold.size())).append(rowTweak("BM25", bmR, ba, gold.size())).append(rowTweak("гібрид RRF", hyR, ha, gold.size()));
            r.table(tb.toString());
            StringBuilder tq = new StringBuilder("| № | Питання | Переписане питання | вектор до → після | BM25 до → після | гібрид до → після |\n|---|---|---|---|---|---|\n");
            for (int i = 0; i < gold.size(); i++) {
                tq.append("| ").append(gold.get(i).shortId()).append(" | ").append(Report.clip(gold.get(i).text(), 60)).append(" | ")
                        .append(Report.clip(rewritten.get(i), 90)).append(" | ").append(arrow(vecR.get(i), va.get(i))).append(" | ")
                        .append(arrow(bmR.get(i), ba.get(i))).append(" | ").append(arrow(hyR.get(i), ha.get(i))).append(" |\n");
            }
            r.table(tq.toString());
            r.how(List.of("«до» — питання як є (кроки 3–4); «після» — переписане вашим промптом.",
                    "«Висновок» — за правилом шуму: різниця в одне питання — висновку немає.",
                    "«до → після» — місце правильного шматка («—» — не в п'ятірці)."));
            calls(r, ans, c0, k0, "генерацій");
        }

        // ---------------------------------------------------------------- крок 5
        r.section("Крок 5. Відповідь з цитатами: effort minimal і high (" + all.size() + " питань)");
        r.what("на кожне питання гібридний пошук дає 5 шматків з id; модель відповідає ЛИШЕ з них за схемою {answer, cited_ids, answerable}. Валідатор: "
                        + "кожен id у cited_ids — серед переданих; answerable = true ⇒ хоча б один id; answerable = false ⇒ порада звернутися до деканату. "
                        + "Не пройшло — повтор з причиною (до " + Answerer.MAX_RETRIES + " разів). Два прогони: effort minimal і high.",
                "скільки відповідей правильні, чи каже бот «не знаю» на питаннях без відповіді і скільки коштує high?");
        c0 = ans.newCalls();
        k0 = ans.cachedCalls();
        List<Answerer.Outcome> oMin = new ArrayList<>(), oHigh = new ArrayList<>();
        List<AutoCheck> chMin = new ArrayList<>(), chHigh = new ArrayList<>();
        for (CallOptions.Effort ef : List.of(CallOptions.Effort.MINIMAL, CallOptions.Effort.HIGH)) {
            for (Lab4Variant.Question q : all) {
                List<Chunk> given = top5.get(q.id());
                Answerer.Outcome o = ans.rag(q.text(), given, ef);
                Map<String, Chunk> byId = new LinkedHashMap<>();
                for (Chunk c : given) byId.put(c.id(), c);
                AutoCheck ch = AutoCheck.of(q, o.ok() ? o.answer() : null, byId);
                (ef == CallOptions.Effort.MINIMAL ? oMin : oHigh).add(o);
                (ef == CallOptions.Effort.MINIMAL ? chMin : chHigh).add(ch);
            }
        }
        r.p("Що реально надіслано: minimal — " + client.effortParameter(CallOptions.Effort.MINIMAL) + "; high — " + client.effortParameter(CallOptions.Effort.HIGH) + ".");
        StringBuilder t5q = new StringBuilder("| № | Питання | Відповідь є? (еталон) | minimal: answerable · цитата · факти · спроб | high: answerable · цитата · факти · спроб |\n|---|---|---|---|---|\n");
        for (int i = 0; i < all.size(); i++) {
            Lab4Variant.Question q = all.get(i);
            t5q.append("| ").append(q.shortId()).append(" | ").append(Report.clip(q.text(), 60)).append(" | ").append(q.answerable() ? "так" : "ні").append(" | ")
                    .append(cell5(oMin.get(i), chMin.get(i))).append(" | ").append(cell5(oHigh.get(i), chHigh.get(i))).append(" |\n");
        }
        r.sub("Кожне питання");
        r.table(t5q.toString());
        r.how(List.of(
                "«answerable» — що відповіла модель: «є» (відповідь знайдено) чи «немає»; ✓ — збіглося з еталоном, ✗ — ні.",
                "«цитата» — чи є серед cited_ids шматок з еталонним фрагментом (для «двох пунктів» — обидва фрагменти; «—» — питання без відповіді).",
                "«факти» — скільки ключових слів/чисел еталону є у відповіді (k/n, з початку слова).",
                "«спроб» — скільки запитів знадобилось воротам: 1 — валідатор прийняв першу відповідь; «здались» — усі спроби відхилено.",
                "«ok» у кінці — правильна (авто): answerable збігся, а для питань з відповіддю ще й цитата влучна і всі ключові факти є."));
        Metrics.GainPrice price = gain(chMin, oMin, chHigh, oHigh);
        int okMin = AutoCheck.correctCount(chMin), okHigh = AutoCheck.correctCount(chHigh);
        Metrics.Verdict ver = Metrics.compare(okHigh, okMin, 1, all.size());
        int nNo = (int) all.stream().filter(q -> !q.answerable()).count();
        StringBuilder t5 = new StringBuilder("| effort | Правильних (авто) | answerable збігся | «Не знаю» на питаннях без відповіді | Цитата влучна | Усі ключові факти | Повторів валідатора | Вихідні токени на питання (thinking) | Затримка, мс | Ціна приросту |\n|---|---|---|---|---|---|---|---|---|---|\n");
        t5.append(row5("minimal", all, chMin, oMin, client.reportsThinkingTokens(), "—"));
        t5.append(row5("high", all, chHigh, oHigh, client.reportsThinkingTokens(), price.text()));
        r.sub("Підсумок кроку 5");
        r.table(t5.toString());
        r.how(List.of(
                "«Правильних (авто)» — з " + all.size() + " питань (визначення — вище); це груба автоперевірка, остаточно — ваші очі.",
                "«answerable збігся» — модель правильно визначила, чи є відповідь у знайдених шматках, з " + all.size() + ".",
                "«“Не знаю” на питаннях без відповіді» — на скількох із " + nNo + " питань без відповіді бот сказав answerable = false.",
                "«Цитата влучна», «Усі ключові факти» — з " + gold.size() + " питань з відповіддю.",
                "«Повторів валідатора» — скільки разів валідатор відхилив відповідь і попросив повторити (разом на " + all.size() + " питань).",
                "«Вихідні токени (thinking)» — середнє на питання, разом з повторами; у дужках — токени прихованого міркування (н/д — провайдер не повідомляє).",
                "«Ціна приросту» — як у лабі 3: додаткові вихідні токени high проти minimal (разом на " + all.size() + " питань) на одне додатково правильне."));
        r.p("high проти minimal: " + ver.text().replace("зв.", "пит.").replace("(у межах шуму)", "(різниця не більша за одне питання)") + ".");
        r.example(example5(okMin, okHigh, all.size(), oMin, oHigh, price));
        List<Integer> eye = eyeCheck(all);
        r.sub("Перевірте очима: 3 відповіді (effort minimal)");
        for (int i : eye) {
            Lab4Variant.Question q = all.get(i);
            Answerer.Outcome o = oMin.get(i);
            r.p("[" + q.shortId() + "] " + q.text() + "\n  відповідь: " + (o.answer() == null ? "(немає)" : o.answer().answer())
                    + "\n  cited_ids: " + (o.answer() == null ? "—" : String.join(", ", o.answer().citedIds()))
                    + "\n  еталон: " + (q.answerable() ? title(docs, q.doc()) + " — «" + String.join("» … «", q.fragments()) + "»" : "відповіді в положеннях немає"));
        }
        r.p("У звіті для цих трьох: чи правильна відповідь, чи справді цитований шматок її підтверджує (повний текст — у розділі «Цитовані шматки» "
                + (resultsFile == null ? "файлу результатів" : resultsFile.toString()) + "), чи нічого не додано від себе.");
        calls(r, ans, c0, k0, "генерацій");
        r.file(allAnswers(all, top5, oMin, oHigh));
        r.file(citedChunks(all, top5, oMin, oHigh));

        // ---------------------------------------------------------------- крок 6
        r.section("Крок 6. Ваші 3 питання (src/main/java/ua/knu/promptlab/lab4/MyQuestions.java)");
        List<String> problems = problems(mine, v, client.isMock());
        int myDone = 0;
        if (mine.isEmpty() || !problems.isEmpty()) {
            if (!problems.isEmpty()) {
                r.p("Питання НЕ прийнято — виправте MyQuestions:");
                for (String pr : problems) r.p("  ! " + pr);
            } else {
                r.p("Ви ще не дописали питань (MyQuestions порожній) — нових запитів 0.");
            }
            r.p("Що зробити: допишіть 3 питання (одне — без відповіді в положеннях; хоча б одне — навмисно складне для пошуку: синонім, код або два пункти) "
                    + "і запустіть ще раз: нові запити підуть лише для них (1 пакет ембедингів і 6 генерацій), решта — з кешу. Чи є відповідь у положеннях, "
                    + "перевірте пошуком по target/lab4-corpus/*.txt (у IDEA — Ctrl+Shift+F).");
        } else {
            c0 = ans.newCalls();
            k0 = ans.cachedCalls();
            int rq6 = emb.requests();
            List<float[]> mv = emb.embed(mine.stream().map(m -> Prefixes.query(m.text())).toList());
            r.what("ваші питання проходять увесь ланцюжок: ембединг питання, гібридний пошук (нарізка " + best.label() + "), відповідь minimal і high.",
                    "чи знайшов пошук потрібний пункт і чи збіглася відповідь бота з вашим очікуванням?");
            StringBuilder t6 = new StringBuilder("| # | Ваше питання | Очікування | Знайдені шматки (топ-5) | minimal: answerable · cited_ids | high: answerable · cited_ids |\n|---|---|---|---|---|---|\n");
            try (Bm25Search bm = new Bm25Search(chunks.get(best), false)) {
                for (int i = 0; i < mine.size(); i++) {
                    MyQuestions.Mine m = mine.get(i);
                    List<VectorSearch.Hit> hy = Rrf.fuse(List.of(vs.get(best).search(mv.get(i), Rrf.DEPTH), bm.search(m.text(), Rrf.DEPTH)), TOP_K);
                    List<Chunk> given = hy.stream().map(VectorSearch.Hit::chunk).toList();
                    Answerer.Outcome a1 = ans.rag(m.text(), given, CallOptions.Effort.MINIMAL);
                    Answerer.Outcome a2 = ans.rag(m.text(), given, CallOptions.Effort.HIGH);
                    t6.append("| ").append(i + 1).append(" | ").append(Report.cell(m.text())).append(" | ").append(m.expectAnswer() ? "відповідь є" : "відповіді немає")
                            .append(" | ").append(String.join(", ", given.stream().map(Chunk::id).toList())).append(" | ").append(cell6(a1)).append(" | ")
                            .append(cell6(a2)).append(" |\n");
                    r.console("[" + (i + 1) + "] " + m.text());
                    StringBuilder found = new StringBuilder();
                    for (Chunk c : given) {
                        String line = c.id() + "  (" + Report.clip(c.docTitle(), 50) + (c.section().isBlank() ? "" : "; " + Report.clip(c.section(), 40))
                                + ")  «" + Report.clip(c.text(), 120) + "»";
                        r.console("     " + line);
                        found.append("\n  - ").append(line);
                    }
                    r.console("     minimal: " + (a1.answer() == null ? "(немає)" : a1.answer().answer()));
                    r.console("     high:    " + (a2.answer() == null ? "(немає)" : a2.answer().answer()));
                    r.file("**[" + (i + 1) + "] " + Report.cell(m.text()) + "**\n\nЗнайдені шматки:" + found
                            + "\n\n- minimal: " + (a1.answer() == null ? "(немає)" : Report.cell(a1.answer().answer()))
                            + "\n- high: " + (a2.answer() == null ? "(немає)" : Report.cell(a2.answer().answer())));
                }
            }
            r.table(t6.toString());
            r.how(List.of("«Очікування» — що ви вказали в MyQuestions (є відповідь у положеннях чи ні).",
                    "«Знайдені шматки» — id п'яти шматків гібридного пошуку; їхній початок — вище, повний текст — у target/lab4-corpus/.",
                    "«answerable · cited_ids» — що відповіла модель: є / немає відповіді і на які шматки послалась."));
            myDone = mine.size();
            calls(r, ans, c0, k0, "генерацій");
            r.console("Запитів ембедингів: " + (emb.requests() - rq6));
        }

        // ---------------------------------------------------------------- підсумок
        r.section("Підсумок");
        Chunker.Stats sa = stats.get(Chunker.Strategy.FIXED), sb = stats.get(Chunker.Strategy.POINTS);
        StringBuilder ts = new StringBuilder("| Що | Значення |\n|---|---|\n");
        ts.append("| Шматків (а) / (б) / (в) | ").append(sa.count()).append(" / ").append(sb.count()).append(" / ").append(stats.get(Chunker.Strategy.POINTS_HEADER).count()).append(" |\n");
        for (Chunker.Strategy s : Chunker.Strategy.values())
            ts.append("| Вектор ").append(s.label()).append(": recall@5 · MRR@5 | ").append(RetrievalMetrics.recallText(ranks.get(s))).append(" · ")
                    .append(RetrievalMetrics.num(RetrievalMetrics.mrr(ranks.get(s)))).append(" |\n");
        ts.append("| ").append(best.label()).append(" без префікса питання | ").append(RetrievalMetrics.recallText(bare)).append(" · ")
                .append(RetrievalMetrics.num(RetrievalMetrics.mrr(bare))).append(" |\n");
        ts.append("| BM25 · гібрид RRF (recall@5) | ").append(RetrievalMetrics.recallText(bmR)).append(" · ").append(RetrievalMetrics.recallText(hyR)).append(" |\n");
        ts.append("| Важіль пошуку (4б): гібрид до → після (recall@5) | ").append(tweak == null ? "не виконано"
                : RetrievalMetrics.recallText(tweak.hyBefore()) + " → " + RetrievalMetrics.recallText(tweak.hyAfter())).append(" |\n");
        ts.append("| Відповідь: правильних (авто) minimal · high | ").append(okMin).append("/").append(all.size()).append(" · ").append(okHigh).append("/").append(all.size()).append(" |\n");
        ts.append("| Ціна приросту high | ").append(price.text()).append(" |\n");
        ts.append("| Ваші питання (крок 6) | ").append(myDone == 0 ? "не виконано" : myDone + " питання").append(" |\n");
        ts.append("| Виключено з метрик (положення оновилось) | ").append(excluded.isEmpty() ? "нічого" : String.join(", ", excluded)).append(" |\n");
        ts.append("| Запитів генерації в цьому запуску: нових · з кешу | ").append(ans.newCalls()).append(" · ").append(ans.cachedCalls()).append(" |\n");
        ts.append("| Ембединги: запитів batchEmbedContents · нових текстів · з вашого кешу · з кешу викладача | ").append(emb.requests()).append(" · ")
                .append(emb.embedded()).append(" · ").append(emb.cached()).append(" · ").append(emb.fromBundle()).append(" |\n");
        ts.append("| Ваш кеш ембедингів: векторів · SHA-256 файлу | ").append(emb.cache().size()).append(" · ").append(fileSha(emb.cache().file())).append(" |\n");
        r.table(ts.toString());
        if (client.isMock()) r.p("РЕЖИМ MOCK: корпус, питання й відповіді імітовані — числа у звіт не йдуть.");
        if (resultsFile != null) {
            r.p(client.isMock() ? "MOCK: файли " + resultsFile + " і " + cache.file() + " — лише для перевірки запуску."
                    : "До звіту додайте " + resultsFile + ", " + cache.file() + ", MyQuestions.java, MyRetrievalTweak.java і лог консолі. "
                    + emb.cache().file() + " НЕ здавайте (він великий): у таблиці вище — кількість векторів і SHA-256 файлу.");
        }
        Console.discuss(
                "Крок 1: скільки з 5 відповідей без документів — вигадка? Яка вигадка найнебезпечніша для студента?",
                "Кроки 3–4: яка нарізка і який пошук знайшли найбільше? Що пошук знайшов замість правильного пункту?",
                "Крок 4б: чи допоміг ваш промпт переписування більше, ніж на одне питання?",
                "Крок 5: чи сказав бот «не знаю» на питаннях без відповіді, і чи варта high своєї ціни?",
                "Крок 6: чи знайшов пошук потрібний пункт для ваших питань? Якщо ні — чому?");
        String md = r.markdown();
        Report.write(resultsFile, md);
        return new Result(stats, ranks, bare, best, bmR, hyR, bmMR, hyMR, tweak, excluded, chMin, chHigh, myDone, ans.newCalls(), ans.cachedCalls(),
                emb.requests(), emb.embedded(), emb.cached(), emb.fromBundle(), md);
    }

    // ------------------------------------------------------------------ пояснення

    static final String LEGEND = String.join("\n",
            "- **Шматок (чанк)** — фрагмент положення: (а) 800 знаків з перекриттям 100; (б) пункт «N.», «N.N.» або «N.N.N.»; (в) той самий пункт з назвою "
                    + "положення в префіксі й назвою розділу (рівня 1) на початку тексту.",
            "- **id шматка** — «документ-пункт[-частина]», наприклад `osv_proces-8.3-07` (сьома частина довгого пункту 8.3) або `povtorne-f012` (12-те вікно "
                    + "фіксованої нарізки); «~2» — другий пункт з тим самим номером у документі (наприклад, у додатку).",
            "- **Ембединг** — список з 768 чисел (gemini-embedding-2), що описує зміст тексту. **Префікс**: питання — «task: search result | query: …», "
                    + "шматок — «title: назва або none | text: …».",
            "- **Еталон** — положення і 1–2 дослівні фрагменти з відповіддю. Шматок **влучив**, якщо містить фрагмент (після нормалізації: нижній регістр, "
                    + "пробіли й переноси рядків → один пробіл, перенос слова «-» прибрано).",
            "- **Типи питань**: «пряме» — слова питання є в положенні; «синонім» — інші слова, ніж у положенні; «код» — номер, код, скорочення "
                    + "(талон №2, «каталог 1», ДПА, гуртожиток № 1, РПП); «два пункти» — відповідь у двох місцях.",
            "- **«Два пункти»** — питання знайдено, лише коли в п'ятірці є ВСІ еталонні фрагменти (місце — те, на якому знайдено останній); "
                    + "цитата влучна, лише коли цитовані шматки містять обидва.",
            "- **recall@5** — частка питань, де правильний шматок є серед перших 5; **MRR@5** — середнє з чисел «1 / місце», де «не в п'ятірці» = 0. "
                    + "Через нулі MRR@5 = 0,5 буває і «усі на 2-му місці», і «половина на 1-му, решта не знайдена» — читайте разом з recall@5.",
            "- **Правило шуму**: на 9 питаннях одне питання ≈ 11 п.п.; різниця в одне питання — **висновку немає**.",
            "- **BM25** — пошук за словами (Lucene); **лематизація** — зведення форм слова до початкової («перескладати», «перескладання» → одна основа), "
                    + "бонус кроку 4; **RRF** — злиття списків місць: 1 / (60 + місце) з кожного списку.",
            "- **answerable** — чи є відповідь у знайдених шматках; **cited_ids** — id шматків, на які спирається відповідь.",
            "- **effort** — рівень прихованого міркування: minimal (Gemini thinkingLevel = minimal) і high; **thinking** — токени цього міркування, "
                    + "оплачуються як вихідні. temperature не надсилається.",
            "- **п.п.** — відсоткові пункти; **пит.** — питання; **н/д** — провайдер не повідомляє окремо.");

    static final List<String> HOW_METRICS = List.of(
            "«recall@5» — у скількох із 9 питань правильний шматок є серед перших 5 знайдених (і частка).",
            "«MRR@5» — середнє з 9 чисел «1 / місце» (1-ше місце — 1, 2-ге — 0,5, не в п'ятірці — 0). Нулі входять у середнє, тож за однакового "
                    + "recall@5 вищий MRR@5 означає, що знайдені шматки стоять вище.",
            "«Порівняно з (а)» — різниця у знайдених питаннях; різниця в одне питання (≈ 11 п.п.) — «висновку немає».",
            "«← найкраща» — нарізка, з якою працюють кроки 4–6 (правило вибору — під таблицями).");

    private static String exampleRecall(Chunker.Strategy best, List<Integer> rk, int n) {
        int f = RetrievalMetrics.found(rk);
        int first = (int) rk.stream().filter(x -> x == 1).count();
        return "рядок " + best.label() + ": у " + f + " з " + n + " питань правильний шматок потрапив у п'ятірку (recall@5 = "
                + RetrievalMetrics.num(RetrievalMetrics.recall(rk)) + "), з них у " + first + " — на першому місці; MRR@5 = "
                + RetrievalMetrics.num(RetrievalMetrics.mrr(rk)) + ". Для " + (n - f) + " питань модель у кроці 5 не побачить правильного пункту.";
    }

    private static String row4(String name, List<Integer> rk, List<Integer> vec, int n) {
        return "| " + name + " | " + RetrievalMetrics.recallText(rk) + " | " + RetrievalMetrics.num(RetrievalMetrics.mrr(rk)) + " | "
                + (vec == null ? "база" : RetrievalMetrics.verdict(RetrievalMetrics.found(rk), RetrievalMetrics.found(vec), n)) + " |\n";
    }

    private static String rowTweak(String name, List<Integer> before, List<Integer> after, int n) {
        return "| " + name + " | " + RetrievalMetrics.recallText(before) + " · " + RetrievalMetrics.num(RetrievalMetrics.mrr(before)) + " | "
                + RetrievalMetrics.recallText(after) + " · " + RetrievalMetrics.num(RetrievalMetrics.mrr(after)) + " | "
                + RetrievalMetrics.verdict(RetrievalMetrics.found(after), RetrievalMetrics.found(before), n) + " |\n";
    }

    private static String arrow(int a, int b) {
        return RetrievalMetrics.rankText(a) + " → " + RetrievalMetrics.rankText(b);
    }

    /** Чому один пошук знайшов, а інший ні — пояснення залежить від типу питання. */
    private static String why(Lab4Variant.Question q, boolean bm25Won) {
        String k = q.kind();
        if (bm25Won) {
            if (k.equals("код")) return "Коди й номери мало змінюють напрямок вектора, а BM25 шукає саме ці символи.";
            if (k.equals("синонім")) return "Несподівано для синоніма: мабуть, у питанні все ж є рідкісне слово з тексту положення — знайдіть його.";
            return "У питанні є рідкісні слова з тексту положення — на них BM25 і спрацював.";
        }
        if (k.equals("синонім")) return "Типово для синоніма: спільних слів мало, а зміст близький — вектор це бачить, BM25 ні.";
        if (k.equals("код")) return "Несподівано для коду: подивіться, як аналізатор BM25 розбив код на слова (рядок «Як BM25 бачить питання»).";
        return "Слова питання є в багатьох шматках, тож BM25 розподілив бали між ними, а вектор узяв зміст.";
    }

    private static String example4(List<Lab4Variant.Question> gold, List<Integer> vec, List<Integer> bm, List<Integer> hy) {
        for (int i = 0; i < gold.size(); i++) {
            if (vec.get(i) == 0 && bm.get(i) > 0)
                return "питання " + gold.get(i).shortId() + " («" + Report.clip(gold.get(i).text(), 60) + "», тип «" + gold.get(i).kind() + "»): вектор не знайшов правильний "
                        + "шматок у п'ятірці, BM25 поставив його на " + bm.get(i) + "-те місце, гібрид — " + (hy.get(i) > 0 ? "на " + hy.get(i) + "-те" : "не в п'ятірку")
                        + ". " + why(gold.get(i), true);
        }
        for (int i = 0; i < gold.size(); i++) {
            if (vec.get(i) > 0 && bm.get(i) == 0)
                return "питання " + gold.get(i).shortId() + " («" + Report.clip(gold.get(i).text(), 60) + "», тип «" + gold.get(i).kind() + "»): вектор знайшов правильний "
                        + "шматок на " + vec.get(i) + "-му місці, а BM25 — ні; гібрид — " + (hy.get(i) > 0 ? "на " + hy.get(i) + "-му" : "не в п'ятірці") + ". "
                        + why(gold.get(i), false);
        }
        return "на ваших питаннях вектор і BM25 помиляються на тих самих питаннях (або не помиляються зовсім) — тоді й гібрид нічого не додає; "
                + "подивіться в «Промахи», що саме пошук знайшов замість правильного пункту.";
    }

    /** Промахи: для питань з місцем 0 — id і перші 120 знаків трьох найвищих шматків. consoleToo — друкувати і в консоль. */
    private static void misses(Report r, String what, List<Lab4Variant.Question> gold, List<Integer> ranks, List<List<VectorSearch.Hit>> tops, boolean consoleToo) {
        StringBuilder sb = new StringBuilder("**" + what + "**");
        int n = 0;
        for (int i = 0; i < gold.size(); i++) {
            if (ranks.get(i) > 0) continue;
            n++;
            sb.append("\n- ").append(gold.get(i).shortId()).append(" «").append(Report.clip(gold.get(i).text(), 70)).append("» — знайдено замість:");
            List<VectorSearch.Hit> t = tops.get(i);
            for (int j = 0; j < Math.min(3, t.size()); j++) {
                sb.append("\n  ").append(j + 1).append(". ").append(t.get(j).chunk().id()).append(": «").append(Report.clip(t.get(j).chunk().text(), 120)).append("»");
            }
        }
        if (n == 0) sb.append(": промахів немає.");
        r.file(sb.toString());
        if (consoleToo) System.out.println(Report.indent(sb.toString().replace("**", "")));
    }

    private static String cell5(Answerer.Outcome o, AutoCheck c) {
        if (o.answer() == null || !o.ok()) return "здались (" + o.attempts() + " спроб)" + (o.truncated() ? ", обрізано" : "");
        return (o.answer().answerable() ? "є" : "немає") + (c.answerableOk() ? " ✓" : " ✗") + " · " + c.citeText() + " · " + c.factsText() + " · "
                + o.attempts() + (c.correct() ? " · ok" : "");
    }

    private static String cell6(Answerer.Outcome o) {
        if (o.answer() == null || !o.ok()) return "здались (" + o.attempts() + " спроб)";
        return (o.answer().answerable() ? "є" : "немає") + " · " + (o.answer().citedIds().isEmpty() ? "—" : String.join(", ", o.answer().citedIds()));
    }

    private static String row5(String name, List<Lab4Variant.Question> all, List<AutoCheck> ch, List<Answerer.Outcome> os, boolean thinkKnown, String price) {
        int n = all.size(), ansOk = 0, dk = 0, noAns = 0, cite = 0, facts = 0, gold = 0, retries = 0;
        double out = 0, th = 0, lat = 0;
        for (int i = 0; i < n; i++) {
            AutoCheck c = ch.get(i);
            Answerer.Outcome o = os.get(i);
            if (c.answered() && c.answerableOk()) ansOk++;
            if (!all.get(i).answerable()) {
                noAns++;
                if (c.answered() && c.answerableOk()) dk++;
            } else {
                gold++;
                if (Boolean.TRUE.equals(c.citeHit())) cite++;
                if (c.answered() && c.factsFound() == c.factsTotal()) facts++;
            }
            retries += o.errors().size();
            out += o.outputTokens();
            th += o.thinkingTokens();
            lat += o.latencyMs();
        }
        return "| " + name + " | " + AutoCheck.correctCount(ch) + "/" + n + " | " + ansOk + "/" + n + " | " + dk + "/" + noAns + " | " + cite + "/" + gold + " | "
                + facts + "/" + gold + " | " + retries + " | " + String.format(Locale.ROOT, "%.0f (%s)", out / Math.max(1, n), thinkKnown ? String.format(Locale.ROOT, "%.0f", th / Math.max(1, n)) : "н/д")
                + " | " + String.format(Locale.ROOT, "%.0f", lat / Math.max(1, n)) + " | " + price + " |\n";
    }

    private static Metrics.GainPrice gain(List<AutoCheck> a, List<Answerer.Outcome> oa, List<AutoCheck> b, List<Answerer.Outcome> ob) {
        double outA = oa.stream().mapToInt(Answerer.Outcome::outputTokens).sum(), outB = ob.stream().mapToInt(Answerer.Outcome::outputTokens).sum();
        return Metrics.gainPrice(AutoCheck.correctCount(a), outA, AutoCheck.correctCount(b), outB);
    }

    private static String example5(int okMin, int okHigh, int n, List<Answerer.Outcome> oMin, List<Answerer.Outcome> oHigh, Metrics.GainPrice p) {
        double outMin = oMin.stream().mapToInt(Answerer.Outcome::outputTokens).sum() / (double) Math.max(1, n);
        double outHigh = oHigh.stream().mapToInt(Answerer.Outcome::outputTokens).sum() / (double) Math.max(1, n);
        return "рядок «high»: " + okHigh + " з " + n + " правильних проти " + okMin + " у minimal; " + String.format(Locale.ROOT, "%.0f", outHigh)
                + " вихідних токенів на питання проти " + String.format(Locale.ROOT, "%.0f", outMin) + ". "
                + (Math.abs(okHigh - okMin) <= 1 ? "Різниця не більша за одне питання — висновку немає, хоч токени витрачено. "
                : okHigh > okMin ? "Різниця більша за одне питання — high справді допоміг. " : "Різниця більша за одне питання — high гірший. ")
                + "Ціна приросту: " + p.text() + ". Якщо правильного шматка немає в п'ятірці (кроки 3–4), жоден effort його не замінить.";
    }

    /** Три відповіді для перевірки очима: два перші питання з відповіддю і перше без відповіді. */
    static List<Integer> eyeCheck(List<Lab4Variant.Question> all) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < all.size() && out.size() < 2; i++) if (all.get(i).answerable()) out.add(i);
        for (int i = 0; i < all.size(); i++) if (!all.get(i).answerable()) {
            out.add(i);
            break;
        }
        return out;
    }

    private static String allAnswers(List<Lab4Variant.Question> all, Map<String, List<Chunk>> top5, List<Answerer.Outcome> oMin, List<Answerer.Outcome> oHigh) {
        StringBuilder sb = new StringBuilder("### Усі відповіді кроку 5 (для перевірки очима)\n\n");
        for (int i = 0; i < all.size(); i++) {
            Lab4Variant.Question q = all.get(i);
            sb.append("**[").append(q.shortId()).append("] ").append(Report.cell(q.text())).append("**  \n");
            sb.append("Передані шматки: ").append(String.join(", ", top5.get(q.id()).stream().map(Chunk::id).toList())).append("\n\n");
            for (int k = 0; k < 2; k++) {
                Answerer.Outcome o = (k == 0 ? oMin : oHigh).get(i);
                sb.append("- ").append(k == 0 ? "minimal" : "high").append(": ");
                if (o.answer() == null) sb.append("(немає відповіді: ").append(Report.cell(String.join("; ", o.errors()))).append(")");
                else sb.append(Report.cell(o.answer().answer())).append(" — answerable = ").append(o.answer().answerable()).append(", cited_ids = [")
                        .append(String.join(", ", o.answer().citedIds())).append("]").append(o.ok() ? "" : " (валідатор не прийняв)");
                if (!o.errors().isEmpty()) sb.append("  \n  відхилено валідатором: ").append(Report.cell(String.join("; ", o.errors())));
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }

    /** Повний текст кожного шматка, на який послалась модель у кроці 5 (щоб перевірити цитату очима). */
    private static String citedChunks(List<Lab4Variant.Question> all, Map<String, List<Chunk>> top5, List<Answerer.Outcome> oMin, List<Answerer.Outcome> oHigh) {
        Map<String, Chunk> byId = new LinkedHashMap<>();
        Map<String, Set<String>> who = new LinkedHashMap<>();
        for (int i = 0; i < all.size(); i++) {
            for (Chunk c : top5.get(all.get(i).id())) byId.put(c.id(), c);
            for (Answerer.Outcome o : List.of(oMin.get(i), oHigh.get(i))) {
                if (o.answer() == null) continue;
                for (String id : o.answer().citedIds()) if (byId.containsKey(id)) who.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(all.get(i).shortId());
            }
        }
        StringBuilder sb = new StringBuilder("### Цитовані шматки (повний текст)\n\nЩо це: кожен шматок, на який послалась модель у кроці 5, повністю — щоб перевірити, "
                + "чи він справді підтверджує відповідь. Питання: чи немає у відповіді того, чого немає в шматку?\n");
        if (who.isEmpty()) sb.append("\n(модель не послалась на жоден шматок)\n");
        for (Map.Entry<String, Set<String>> e : who.entrySet()) {
            Chunk c = byId.get(e.getKey());
            sb.append("\n**").append(c.id()).append("** — ").append(Report.cell(c.docTitle())).append(c.section().isBlank() ? "" : "; розділ: " + Report.cell(c.section()))
                    .append("; с. ").append(c.page()).append("; цитують питання: ").append(String.join(", ", e.getValue())).append("\n\n");
            for (String line : c.text().strip().split("\n")) sb.append("> ").append(line).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    // ------------------------------------------------------------------ допоміжне

    /**
     * Чи є еталон питання в завантажених положеннях: для «двох пунктів» — усі фрагменти (другий пункт може бути в іншому положенні),
     * інакше — хоч один фрагмент у положенні питання.
     */
    static boolean goldPresent(Lab4Variant.Question q, Map<String, Corpus.Text> byDoc) {
        if (q.needsAll()) {
            return q.fragments().stream().allMatch(f -> byDoc.values().stream().anyMatch(d -> TextNorm.containsNormalized(d.full(), f)));
        }
        Corpus.Text d = byDoc.get(q.doc());
        return d != null && q.fragments().stream().anyMatch(f -> TextNorm.containsNormalized(d.full(), f));
    }

    /** Найкраща нарізка: більший recall@5, за рівності — більший MRR@5, за повної рівності — пізніша ((в) перед (б) перед (а)). */
    static Chunker.Strategy best(Map<Chunker.Strategy, List<Integer>> ranks) {
        Chunker.Strategy best = null;
        for (Chunker.Strategy s : Chunker.Strategy.values()) {
            if (best == null) {
                best = s;
                continue;
            }
            int f = RetrievalMetrics.found(ranks.get(s)), fb = RetrievalMetrics.found(ranks.get(best));
            double m = RetrievalMetrics.mrr(ranks.get(s)), mb = RetrievalMetrics.mrr(ranks.get(best));
            if (f > fb || f == fb && m >= mb - 1e-12) best = s;
        }
        return best;
    }

    /** Приклад шматка для показу: про повторне складання екзаменів (є і в справжньому, і у вигаданому корпусі), інакше будь-який довгий. */
    private static Chunk example(List<Chunk> cs, Chunker.Strategy s) {
        for (Chunk c : cs) if (c.text().contains("складання екзаменів") && (!s.withHeader() || !c.section().isBlank())) return c;
        for (Chunk c : cs) if (c.length() > 200 && (!s.withHeader() || !c.section().isBlank())) return c;
        return cs.get(0);
    }

    private static String title(List<Corpus.Text> docs, String id) {
        if (id == null) return "—";
        for (Corpus.Text d : docs) if (d.doc().id().equals(id)) return d.doc().title();
        return id;
    }

    private static String fileSha(Path p) {
        try {
            return Files.isRegularFile(p) ? CorpusLoader.sha256(Files.readAllBytes(p)).substring(0, 16) : "файлу немає";
        } catch (IOException e) {
            return "не прочитався";
        }
    }

    private static void calls(Report r, Answerer a, int newBefore, int cachedBefore, String what) {
        r.console("Запитів у кроці (" + what + "): нових " + (a.newCalls() - newBefore) + ", з кешу " + (a.cachedCalls() - cachedBefore));
    }

    /** Службові слова, які не рахуються в схожості ваших питань з питаннями варіанта. */
    static final Set<String> STOP_WORDS = Set.of("і", "й", "та", "а", "але", "або", "чи", "в", "у", "на", "з", "із", "зі", "до", "за", "від", "по", "про", "для",
            "при", "після", "що", "як", "це", "не", "ні", "так", "я", "мені", "мене", "мій", "моя", "моє", "мої", "ми", "нам", "ви", "вам", "можна", "треба",
            "якщо", "коли", "скільки", "який", "яка", "яке", "які", "де", "хто", "кому", "куди", "чому", "щоб", "буде", "є", "мати", "мають", "може");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}'’]+");

    /** Значущі слова тексту (нижній регістр, без службових). */
    static Set<String> contentWords(String text) {
        Set<String> out = new HashSet<>();
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT).replace('’', '\''));
        while (m.find()) if (!STOP_WORDS.contains(m.group())) out.add(m.group());
        return out;
    }

    /** Схожість Жаккара за значущими словами. */
    static double similarity(String a, String b) {
        Set<String> wa = contentWords(a), wb = contentWords(b);
        Set<String> union = new HashSet<>(wa);
        union.addAll(wb);
        if (union.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(wa);
        inter.retainAll(wb);
        return (double) inter.size() / union.size();
    }

    /** Що не так з вашими питаннями (порожньо — усе гаразд). Схожість — з питаннями ВАШОГО варіанта (у MOCK — з питаннями MOCK). */
    static List<String> problems(List<MyQuestions.Mine> mine, Lab4Variant v, boolean mock) {
        List<String> out = new ArrayList<>();
        if (mine.isEmpty()) return out;
        if (mine.size() != 3) out.add("потрібно рівно 3 питання, а не " + mine.size());
        if (mine.stream().allMatch(MyQuestions.Mine::expectAnswer)) out.add("одне з питань має бути без відповіді в положеннях (expectAnswer = false)");
        for (MyQuestions.Mine m : mine) {
            if (m.text() == null || m.text().isBlank()) {
                out.add("порожнє питання");
                continue;
            }
            for (Lab4Variant.Question q : v.questions()) {
                double s = similarity(m.text(), q.text());
                if (s >= 0.5)
                    out.add("«" + Report.clip(m.text(), 60) + "» майже повторює питання " + (mock ? "MOCK " : "вашого варіанта ") + q.shortId() + " «" + q.text()
                            + "» (схожість значущих слів " + String.format(Locale.ROOT, "%.2f", s).replace('.', ',') + ", поріг 0,5) — сформулюйте своє"
                            + (mock ? " (у MOCK порівнюємо з вигаданими питаннями MOCK, у справжньому прогоні — з питаннями вашого варіанта)" : ""));
            }
        }
        return out;
    }
}
