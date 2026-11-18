package ua.knu.promptlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ua.knu.promptlab.lab3.AnswerCache;
import ua.knu.promptlab.lab4.AutoCheck;
import ua.knu.promptlab.lab4.Bm25Search;
import ua.knu.promptlab.lab4.Chunk;
import ua.knu.promptlab.lab4.Chunker;
import ua.knu.promptlab.lab4.Corpus;
import ua.knu.promptlab.lab4.CorpusLoader;
import ua.knu.promptlab.lab4.EmbeddingBundle;
import ua.knu.promptlab.lab4.EmbeddingCache;
import ua.knu.promptlab.lab4.Embedder;
import ua.knu.promptlab.lab4.Lab4Run;
import ua.knu.promptlab.lab4.Lab4Variant;
import ua.knu.promptlab.lab4.MyQuestions;
import ua.knu.promptlab.lab4.MyRetrievalTweak;
import ua.knu.promptlab.lab4.PdfText;
import ua.knu.promptlab.lab4.Prefixes;
import ua.knu.promptlab.lab4.RagAnswer;
import ua.knu.promptlab.lab4.RagPrompts;
import ua.knu.promptlab.lab4.RetrievalMetrics;
import ua.knu.promptlab.lab4.Rrf;
import ua.knu.promptlab.lab4.TextNorm;
import ua.knu.promptlab.lab4.VectorSearch;
import ua.knu.promptlab.mock.FakeLlmServer;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Лабораторна 4: нарізки, колонтитули, нормалізація, косинус, BM25, RRF, recall@5 / MRR@5 (приклади зі слайдів), валідатор cited_ids,
 * кеш і пакетні ембединги (проти імітації), дані варіантів і повний прогін у MOCK — без мережі.
 */
class Lab4Test {

    @BeforeAll
    static void start() {
        FakeLlmServer.startOnce();
    }

    @AfterAll
    static void stop() {
        FakeLlmServer.stop();
    }

    private static String url() {
        return FakeLlmServer.startOnce().baseUrl();
    }

    private static Corpus.Doc doc(String id, String title) {
        return new Corpus.Doc(id, title, "T-01:2026", "", 1, null, null);
    }

    private static Corpus.Text text(String id, String title, String... pages) {
        return new Corpus.Text(doc(id, title), List.of(pages));
    }

    private static Lab4Variant.Question q(String id, String text, String fragment) {
        return new Lab4Variant.Question(id, text, "пряме", true, "d", List.of(fragment), List.of(), false, null);
    }

    // ------------------------------------------------------------------ нормалізація

    @Test
    void normalizationJoinsHyphenatedWordsAndUnifiesSpacesCaseAndQuotes() {
        assertEquals("незадовільну оцінку (f, fx)", TextNorm.normalize("незадо-\nвільну   Оцінку\n(F, FX)"));
        assertEquals("обов'язкові \"правила\" - талон №3", TextNorm.normalize("Обов’язкові «правила» – талон № 3"));
        assertTrue(TextNorm.containsNormalized("Повторне складання\nекзаменів допускається не більше двох разів", "складання екзаменів допускається"));
        assertFalse(TextNorm.containsNormalized("будь-що", ""));
        assertTrue(TextNorm.hasFact("Можна двічі: викладачеві й комісії", "двох|двічі"));
        assertFalse(TextNorm.hasFact("Можна тричі", "двох|двічі"));
        // ключові факти — по межах слів
        assertTrue(TextNorm.hasFact("Перескладають комісії.", "коміс"), "з початку слова — так");
        assertFalse(TextNorm.hasFact("Студентів вистачає.", "ста"), "не з початку слова — ні");
        assertFalse(TextNorm.hasFact("Обов'язкова атестація.", "тест"), "«тест» не знайде «атестація»");
        assertFalse(TextNorm.hasFact("Група від 120 осіб", "12"), "«12» не знайде «120»");
        assertTrue(TextNorm.hasFact("Група від 12 осіб", "12"));
        assertFalse(TextNorm.hasFact("Стипендію платять щомісяця", "місяц"), "не з початку слова");
    }

    // ------------------------------------------------------------------ колонтитули

    @Test
    void headersAndPageNumbersAreRemovedFromEveryPage() {
        String head = "ЕЛЕКТРОННИЙ ФОНД ЛОКАЛЬНИХ\nНОРМАТИВНО-ПРАВОВИХ ДОКУМЕНТІВ\nКАРПАТСЬКОГО НАЦІОНАЛЬНОГО УНІВЕРСИТЕТУ\nІМЕНІ ВАСИЛЯ СТЕФАНИКА\n02-07.45:2026\n"
                + "НАВЧАЛЬНО-МЕТОДИЧНИЙ ВІДДІЛ Редакція 11\n";
        List<String> pages = List.of(head + " \nПОЛОЖЕННЯ\nпро щось\n1 \n", head + "1.1. Перший пункт закінчується\nна цій сторінці.\n2\n",
                head + "1.2. Другий пункт.\n3\n", head + "17\nТаблиця на сторінці з номером угорі\n");
        List<String> clean = PdfText.clean(pages);
        assertEquals(4, clean.size());
        assertEquals("ПОЛОЖЕННЯ\nпро щось", clean.get(0));
        assertEquals("1.1. Перший пункт закінчується\nна цій сторінці.", clean.get(1));
        assertEquals("1.2. Другий пункт.", clean.get(2));
        assertEquals("Таблиця на сторінці з номером угорі", clean.get(3), "номер сторінки вгорі (одразу після колонтитула) теж прибирається");
        for (String p : clean) assertFalse(p.contains("ФОНД") || p.contains("Редакція"), p);
    }

    @Test
    void splitDoesNotDetachPointNumbers() {
        String text = "16.2. Оскарженню підлягають результати контролю. " + "Довге речення про правила оскарження результатів. ".repeat(30);
        List<String> parts = Chunker.split(text, 800);
        assertTrue(parts.get(0).startsWith("16.2. Оскарженню"), parts.get(0));
        for (String p : parts) assertFalse(p.strip().matches("\\d+(\\.\\d+)*\\."), "номер пункту окремим шматком: " + p);
    }

    @Test
    void mockCorpusLoadsWithoutHeaders() {
        List<Corpus.Text> docs = CorpusLoader.loadMock(Corpus.load(true));
        assertEquals(3, docs.size());
        for (Corpus.Text d : docs) {
            assertFalse(d.full().contains("ЕЛЕКТРОННИЙ ФОНД") || d.full().contains("Редакція 1"), d.doc().id());
            assertTrue(d.doc().title().contains("Умовного університету"), "вигадані положення мають бути явно вигадані");
        }
    }

    /**
     * Лише для викладача, офлайн: справжні PDF у папці LAB4_TEACHER_PDF_DIR (без мережі). Без змінної тест нічого не робить.
     * Перевіряє, що в нарізці (в) жоден шматок ind_grafik, ombudsman і osv_proces не має «розділу» з передмови («УПРОВАДЖЕНО НА ЗАМІНУ»).
     */
    @Test
    void teacherOnlyRealCorpusHasNoPrefaceSections() throws Exception {
        String dir = System.getenv("LAB4_TEACHER_PDF_DIR");
        if (dir == null || dir.isBlank()) return;
        Path cache = Files.createTempDirectory("lab4-teacher-corpus");
        List<Corpus.Text> docs = new CorpusLoader(cache, Path.of(dir), false).load(Corpus.load(false));
        int seen = 0;
        for (Chunk c : Chunker.chunk(docs, Chunker.Strategy.POINTS_HEADER)) {
            if (!Set.of("ind_grafik", "ombudsman", "osv_proces").contains(c.docId())) continue;
            seen++;
            assertFalse(c.section().contains("УПРОВАДЖЕНО") || c.section().contains("НА ЗАМІНУ") || c.section().contains("ВНЕСЕНО"),
                    "передмова — не розділ: " + c.id() + " | " + c.section());
        }
        assertTrue(seen > 100, "шматків трьох положень: " + seen);
    }

    /**
     * Усі HTTP-заголовки — ASCII. 12.11.2026 User-Agent з кирилицею зламав завантаження всіх 17 PDF («invalid header value»).
     * Перевіряємо побудований запит завантажувача, захист у HttpJson і всі літерали .header("…", "…") у коді.
     */
    @Test
    void httpHeadersAreAscii() throws Exception {
        assertTrue(HttpJson.isAscii(CorpusLoader.USER_AGENT), CorpusLoader.USER_AGENT);
        for (Corpus.Doc d : Corpus.load(false).documents()) {
            java.net.http.HttpRequest req = CorpusLoader.downloadRequest(d.url());
            req.headers().map().forEach((k, vs) -> {
                assertTrue(HttpJson.isAscii(k), "назва заголовка: " + k);
                for (String v : vs) assertTrue(HttpJson.isAscii(v), k + ": " + v);
            });
            assertTrue(req.headers().firstValue("User-Agent").isPresent(), d.id());
        }
        assertFalse(HttpJson.isAscii("Mozilla/5.0 (навчальний проєкт)"));
        // захист: заголовок з кирилицею (наприклад, ключ з .env) — зрозуміла помилка ще до мережі
        ApiException e = assertThrows(ApiException.class, () -> new HttpJson(0, 1).post("http://127.0.0.1:9/x",
                Map.of("x-goog-api-key", "AIzaключ"), new ObjectMapper().createObjectNode()));
        assertTrue(e.getMessage().contains("ASCII") && e.getMessage().contains(".env"), e.getMessage());
        // усі літерали заголовків у коді (якщо тести запущено з кореня проєкту, як у Maven)
        Path src = Path.of("src", "main", "java");
        if (Files.isDirectory(src)) {
            java.util.regex.Pattern lit = java.util.regex.Pattern.compile("\\.header\\(\\s*\"([^\"]*)\"\\s*,\\s*\"([^\"]*)\"");
            int seen = 0;
            try (var files = Files.walk(src)) {
                for (Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                    java.util.regex.Matcher m = lit.matcher(Files.readString(f, StandardCharsets.UTF_8));
                    while (m.find()) {
                        seen++;
                        assertTrue(HttpJson.isAscii(m.group(1)) && HttpJson.isAscii(m.group(2)), f.getFileName() + ": " + m.group());
                    }
                }
            }
            assertTrue(seen >= 1, "знайдено літералів .header: " + seen);
        }
    }

    /**
     * Справжній кеш викладача (src/main/resources/lab4/embeddings-cache.bin.gz) покриває все, що студент попросить з порожнім target/:
     * питання всіх 8 варіантів (з префіксом і без) — завжди; шматки (а), (б), (в) справжнього корпусу — якщо задано LAB4_TEACHER_PDF_DIR (офлайн).
     * Тоді перший прогін не робить жодного запиту на ембединги корпусу й питань варіанта.
     */
    @Test
    void realTeacherBundleCoversQuestionsAndCorpus() throws Exception {
        EmbeddingBundle b = EmbeddingBundle.fromResources(false, Embedder.DEFAULT_MODEL, Embedder.DIM);
        assertNotNull(b, "у проєкті немає кешу викладача " + EmbeddingBundle.RESOURCE);
        assertEquals(Embedder.DIM, b.dim());
        List<String> missing = new ArrayList<>();
        for (Lab4Variant v : Lab4Variant.loadAll()) {
            for (Lab4Variant.Question q : v.questions()) {
                if (b.get(EmbeddingCache.key(b.model(), b.dim(), Prefixes.query(q.text()))) == null) missing.add(q.id() + " (з префіксом)");
                if (q.answerable() && b.get(EmbeddingCache.key(b.model(), b.dim(), q.text())) == null) missing.add(q.id() + " (без префікса)");
            }
        }
        assertTrue(missing.isEmpty(), "питань немає в кеші викладача: " + missing);
        String dir = System.getenv("LAB4_TEACHER_PDF_DIR");
        if (dir == null || dir.isBlank()) return;
        List<Corpus.Text> docs = new CorpusLoader(Files.createTempDirectory("lab4-bundle-corpus"), Path.of(dir), false).load(Corpus.load(false));
        Map<String, Integer> lost = new LinkedHashMap<>();
        for (Chunker.Strategy s : Chunker.Strategy.values()) {
            for (Chunk c : Chunker.chunk(docs, s)) {
                if (b.get(EmbeddingCache.key(b.model(), b.dim(), c.embedText())) == null) lost.merge(s.label(), 1, Integer::sum);
            }
        }
        assertTrue(lost.isEmpty(), "шматків немає в кеші викладача (нарізку змінено після індексації?): " + lost);
        Set<String> all = ua.knu.promptlab.lab4.TeacherIndex.texts(docs, false);
        assertEquals(all.size(), b.size(), "кеш викладача має рівно стільки векторів, скільки текстів збирає TeacherIndex");
    }

    @Test
    void realCorpusListHas17DocumentsOnEfund() {
        Corpus c = Corpus.load(false);
        assertEquals(17, c.documents().size());
        Set<String> ids = new HashSet<>();
        for (Corpus.Doc d : c.documents()) {
            assertTrue(d.url().startsWith("https://efund.cnu.edu.ua/") && d.url().endsWith(".pdf"), d.id());
            assertTrue(d.sha256() != null && d.sha256().length() == 64, d.id());
            assertTrue(d.title().startsWith("Положення"), d.id());
            assertTrue(ids.add(d.id()), "повтор id " + d.id());
        }
    }

    @Test
    void loaderWithoutPdfAndOfflineExplainsManualDownload() throws Exception {
        Path tmp = Files.createTempDirectory("lab4corpus");
        CorpusLoader ld = new CorpusLoader(tmp.resolve("cache"), tmp.resolve("lab4-pdf"), false);
        Corpus one = new Corpus("s", "2026-11-12", "n", List.of(Corpus.load(false).documents().get(1)));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ld.load(one));
        assertTrue(e.getMessage().contains("Завантажте PDF вручну за посиланням"), e.getMessage());
        assertTrue(e.getMessage().contains("lab4-pdf") && e.getMessage().contains("efund.cnu.edu.ua"), e.getMessage());
        // кеш тексту: якщо файл є (правильна версія), PDF не потрібен
        Corpus.Doc d = one.documents().get(0);
        Files.createDirectories(tmp.resolve("cache"));
        Files.writeString(tmp.resolve("cache").resolve(d.id() + ".txt"),
                "# lab4-corpus v" + CorpusLoader.CLEAN_VERSION + " | " + d.id() + " | pdf-sha256 " + d.sha256() + " | pages 2\nСторінка 1\n\f\nСторінка 2");
        List<Corpus.Text> got = ld.load(one);
        assertEquals(List.of("Сторінка 1", "Сторінка 2"), got.get(0).pages());
        assertEquals(1, ld.stats().fromCache());
        assertTrue(ld.stats().changed().isEmpty());
    }

    // ------------------------------------------------------------------ нарізки

    @Test
    void fixedChunksAre800WithOverlap100() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) sb.append("слово").append(i).append(' ');
        Corpus.Text d = text("doc", "Положення", sb.toString());
        List<Chunk> cs = Chunker.fixed(d);
        String full = d.full();
        assertTrue(cs.size() >= 3);
        for (int i = 0; i < cs.size(); i++) {
            Chunk c = cs.get(i);
            assertTrue(c.length() <= Chunker.SIZE);
            assertEquals(full.substring(i * Chunker.STEP, Math.min(full.length(), i * Chunker.STEP + Chunker.SIZE)), c.text(), "шматок " + i);
            if (i + 1 < cs.size()) {
                assertEquals(Chunker.SIZE, c.length());
                assertTrue(cs.get(i + 1).text().startsWith(c.text().substring(Chunker.STEP)), "перекриття " + Chunker.OVERLAP + " знаків");
            }
        }
        assertTrue(cs.get(cs.size() - 1).text().endsWith(full.substring(full.length() - 20)), "кінець тексту не загублено");
        assertEquals("doc-f001", cs.get(0).id());
        assertTrue(cs.get(0).embedText().startsWith("title: none | text: "));
    }

    @Test
    void pointRegexMatchesNumberedPointsOnly() {
        assertEquals("16", Chunker.pointNumber("16. Оскарження результатів"));
        assertEquals("16.2", Chunker.pointNumber("16.2. Оскарженню підлягають результати"));
        assertEquals("16.2.1", Chunker.pointNumber("16.2.1. Поточного контролю"));
        assertEquals("1.10", Chunker.pointNumber("1.10 Вибрані та закріплені компоненти"), "без крапки після номера (як в одному з положень)");
        assertNull(Chunker.pointNumber("17.4 цього Положення."), "посилання на пункт посеред речення — не новий пункт");
        assertNull(Chunker.pointNumber("2026 рік"));
        assertNull(Chunker.pointNumber("Протокол від 31 серпня 2026 р. №13"));
    }

    @Test
    void pointChunkingKeepsRulesWholeAddsSectionAndSplitsLongPoints() {
        StringBuilder longBody = new StringBuilder("8.3. Види контролю\n");
        for (int i = 0; i < 40; i++) longBody.append("Речення номер ").append(i).append(" про правила контролю знань.\n");
        Corpus.Text d = text("osv", "Положення про освітній процес",
                "ПОЛОЖЕННЯ\nпро освітній процес\nм. Івано-Франківськ",
                "ПЕРЕДМОВА\n1. ВНЕСЕНО Навчально-методичний відділ\n5. УПРОВАДЖЕНО\nНА ЗАМІНУ\nПоложення редакції 6, введеного в дію наказом ректора.",
                "Вступ до положення про освітній процес, що пояснює його мету.\n8. Контроль результатів навчання\n8.1. Поточний контроль проводиться на заняттях.\n"
                        + "8.2. Оцінювання —\nза 100-бальною шкалою, у кожному семестрі.\n2026 рік",
                longBody + "13. Особливості заочної форми\n13.1. Перескладання допускається не більше двох разів.");
        List<Chunk> b = Chunker.points(d, Chunker.Strategy.POINTS);
        Map<String, Chunk> byId = new LinkedHashMap<>();
        for (Chunk c : b) byId.put(c.id(), c);
        for (Chunk c : b) {
            assertFalse(c.section().contains("УПРОВАДЖЕНО") || c.section().contains("ВНЕСЕНО"), "передмова — не розділ: " + c.id() + " " + c.section());
            assertTrue(c.text().strip().length() >= Chunker.MIN_CHUNK, "уламок коротший за " + Chunker.MIN_CHUNK + ": " + c.id());
        }
        Chunk p81 = byId.get("osv-8.1");
        assertNotNull(p81, byId.keySet().toString());
        assertTrue(p81.text().startsWith("8. Контроль результатів навчання\n8.1."), "заголовок без тексту приєднано до наступного пункту: " + p81.text());
        assertEquals("8. Контроль результатів навчання", p81.section());
        assertEquals("8.2. Оцінювання —\nза 100-бальною шкалою, у кожному семестрі.\n2026 рік", byId.get("osv-8.2").text(), "пункт на кількох рядках — один шматок");
        List<Chunk> long83 = b.stream().filter(c -> c.id().startsWith("osv-8.3-")).toList();
        assertTrue(long83.size() >= 2, "довгий пункт розрізано: " + byId.keySet());
        assertTrue(long83.get(0).text().startsWith("8.3. Види контролю\nРечення номер 0"), "номер пункту лишається з наступним реченням: " + long83.get(0).text());
        for (Chunk c : long83) {
            assertTrue(c.length() <= Chunker.SIZE);
            assertTrue(c.text().endsWith("."), "ріжемо по межі речення: …" + c.text().substring(Math.max(0, c.length() - 30)));
            assertEquals("8. Контроль результатів навчання", c.section(), "розділ — лише рівень 1");
        }
        Chunk p131 = byId.get("osv-13.1");
        assertEquals("13. Особливості заочної форми", p131.section());
        assertEquals(4, p131.page());
        // (в): ті самі шматки, але з адресою; BM25 бачить розділ, але не назву документа
        List<Chunk> v = Chunker.points(d, Chunker.Strategy.POINTS_HEADER);
        assertEquals(b.size(), v.size());
        Chunk v131 = v.stream().filter(c -> c.id().equals("osv-13.1")).findFirst().orElseThrow();
        assertEquals(p131.text(), v131.text());
        assertEquals("title: Положення про освітній процес | text: Розділ: 13. Особливості заочної форми.\n" + p131.text(), v131.embedText());
        assertEquals("title: none | text: " + p131.text(), p131.embedText());
        assertEquals("Розділ: 13. Особливості заочної форми.\n" + p131.text(), v131.searchText());
        assertEquals(p131.text(), p131.searchText());
    }

    @Test
    void headingMustBeShortAndNotSentence() {
        assertTrue(Chunker.isHeading("Загальні положення", "1.1. Перший пункт.", null));
        assertTrue(Chunker.isHeading("ЗАГАЛЬНІ ПОЛОЖЕННЯ", "1.1. Перший пункт.", null));
        assertFalse(Chunker.isHeading("Академічна стипендія у розмірі", "мінімальної ординарної стипендії, встановленої урядом, виплачується щомісяця", null));
        assertFalse(Chunker.isHeading("УПРОВАДЖЕНО", "НА ЗАМІНУ", null), "пункт передмови — не заголовок");
        assertFalse(Chunker.isHeading("Положення діє з дня затвердження.", "1.1. …", null));
        assertTrue(Chunker.isHeading("Особливості освітнього процесу на заочній", "формі", "13.1. Текст"));
    }

    @Test
    void splitRespectsMaxAndSentenceBoundaries() {
        String text = "Перше речення. " + "Друге дуже довге речення без крапки ".repeat(40) + "кінець. Третє.";
        List<String> parts = Chunker.split(text, 800);
        for (String p : parts) assertTrue(p.length() <= 800, p.length() + "");
        assertEquals(TextNorm.normalize(text), TextNorm.normalize(String.join(" ", parts)), "нічого не загублено");
    }

    @Test
    void tornExampleFindsWordCutAtBoundary() {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 1700) sb.append("незадовільну оцінку отримав здобувач ");
        List<Chunk> cs = Chunker.fixed(text("d", "Т", sb.toString()));
        Chunk[] t = Chunker.tornExample(cs, "оцінку");
        assertNotNull(t);
        assertTrue(Character.isLetter(t[0].text().charAt(t[0].length() - 1)));
    }

    // ------------------------------------------------------------------ косинус (слайд 8)

    @Test
    void cosineMatchesSlideExampleAndIgnoresLength() {
        float[] q = {0.9f, 0.1f, 0f}, a = {0.9f, 0f, 0.1f}, b = {0.6f, 0.7f, 0f}, c = {0f, 0.6f, 0.8f}, a2 = {1.8f, 0f, 0.2f};
        assertEquals(0.988, VectorSearch.cosine(q, a), 0.001);
        assertEquals(0.731, VectorSearch.cosine(q, b), 0.001);
        assertEquals(0.066, VectorSearch.cosine(q, c), 0.001);
        assertEquals(VectorSearch.cosine(q, a), VectorSearch.cosine(q, a2), 1e-9, "2A — той самий напрямок, той самий косинус");
        assertEquals(0.0, VectorSearch.cosine(q, new float[3]), 0.0);
        Chunk ca = new Chunk("A", "d", "t", "", 1, "A", Chunker.Strategy.FIXED), cb = new Chunk("B", "d", "t", "", 1, "B", Chunker.Strategy.FIXED),
                cc = new Chunk("C", "d", "t", "", 1, "C", Chunker.Strategy.FIXED);
        List<VectorSearch.Hit> hits = new VectorSearch(List.of(cc, cb, ca), List.of(c, b, a)).search(q, 3);
        assertEquals(List.of("A", "B", "C"), hits.stream().map(h -> h.chunk().id()).toList());
    }

    // ------------------------------------------------------------------ BM25 і RRF

    @Test
    void bm25FindsExactWordsAndCodes() {
        List<Chunk> cs = new ArrayList<>();
        String[] texts = {"Повторне складання екзаменів: талон №2 викладачеві, талон №3 комісії.", "Оплата за проживання у гуртожитку щомісяця.",
                "Університет забезпечує освітній процес в університеті.", "Скарга подається протягом двох робочих днів.",
                "Перескладання екзамену для заочної форми допускається не більше двох разів."};
        for (int i = 0; i < texts.length; i++) cs.add(new Chunk("c" + i, "d", "Положення", "", 1, texts[i], Chunker.Strategy.POINTS));
        try (Bm25Search bm = new Bm25Search(cs, false)) {
            assertEquals("c0", bm.search("Що таке талон №3?", 5).get(0).chunk().id());
            assertEquals("c1", bm.search("оплата гуртожиток проживання", 5).get(0).chunk().id());
            assertEquals("c4", bm.search("перескладання", 5).get(0).chunk().id());
            assertTrue(bm.search("перескладати", 5).isEmpty(), "без лематизації «перескладати» ≠ «перескладання»");
            assertTrue(bm.terms("Скільки разів?").contains("скільки"), "нижній регістр");
        }
        assertEquals(1.2f, Bm25Search.K1);
        assertEquals(0.75f, Bm25Search.B);
        if (Bm25Search.morfologikAvailable()) {
            try (Bm25Search bm = new Bm25Search(cs, true)) {
                assertFalse(bm.search("пересклали екзамени", 5).isEmpty(), "лематизація зводить форми слова");
            }
        }
    }

    @Test
    void rrfMatchesSlideExample() {
        assertEquals(1.0 / 61 + 1.0 / 66, Rrf.score(1) + Rrf.score(6), 1e-12);
        assertEquals(0.0315, Rrf.score(1) + Rrf.score(6), 0.0001);
        assertEquals(0.0323, Rrf.score(3) + Rrf.score(1), 0.0001);
        assertEquals(0.0308, Rrf.score(8) + Rrf.score(2), 0.0001);
        assertEquals(0.0161, Rrf.score(2), 0.0001);
        // слайд 14: вектор — [8.3, повторне вивчення, розд.13, …, 16.2 на 8-му]; BM25 — [розд.13, 16.2, …, 8.3 на 6-му]
        Map<String, Chunk> c = new LinkedHashMap<>();
        for (String id : List.of("r13", "s83", "p162", "pv", "x1", "x2", "x3", "x4", "y1", "y2", "y3")) c.put(id, new Chunk(id, "d", "t", "", 1, id, Chunker.Strategy.POINTS));
        List<VectorSearch.Hit> vec = hits(c, "s83", "pv", "r13", "x1", "x2", "x3", "x4", "p162");
        List<VectorSearch.Hit> bm = hits(c, "r13", "p162", "y1", "y2", "y3", "s83");
        List<VectorSearch.Hit> fused = Rrf.fuse(List.of(vec, bm), 4);
        assertEquals(List.of("r13", "s83", "p162", "pv"), fused.stream().map(h -> h.chunk().id()).toList());
        assertEquals(0.0323, fused.get(0).score(), 0.0001);
        assertEquals(0.0315, fused.get(1).score(), 0.0001);
    }

    private static List<VectorSearch.Hit> hits(Map<String, Chunk> c, String... ids) {
        List<VectorSearch.Hit> out = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) out.add(new VectorSearch.Hit(c.get(ids[i]), 1.0 / (i + 1)));
        return out;
    }

    // ------------------------------------------------------------------ метрики (слайд 15)

    @Test
    void recallAndMrrMatchSlideExample() {
        List<Integer> ranks = List.of(1, 3, 0, 2);
        assertEquals(0.75, RetrievalMetrics.recall(ranks), 1e-9);
        assertEquals((1 + 1.0 / 3 + 0 + 0.5) / 4, RetrievalMetrics.mrr(ranks), 1e-9);
        assertEquals(0.458, RetrievalMetrics.mrr(ranks), 0.001);
        assertEquals("3/4 (0,75)", RetrievalMetrics.recallText(ranks));
        assertEquals(0, RetrievalMetrics.recall(List.of(6, 0)), 1e-9, "місце за п'ятіркою не рахується");
        assertTrue(RetrievalMetrics.verdict(7, 6, 9).contains("висновку немає"));
        assertTrue(RetrievalMetrics.verdict(8, 6, 9).contains("краще"));
        assertTrue(RetrievalMetrics.verdict(4, 6, 9).contains("гірше"));
    }

    @Test
    void rankFindsChunkContainingFragmentAfterNormalization() {
        Lab4Variant.Question question = q("V-01", "?", "Повторне складання екзаменів допускається не більше двох разів");
        Chunk other = new Chunk("a", "d", "t", "", 1, "Перескладання для заочної форми — не більше двох разів.", Chunker.Strategy.POINTS);
        Chunk gold = new Chunk("b", "d", "t", "", 1, "…повторне складання\nекзаменів допускається не\nбільше двох разів з кожної дисципліни", Chunker.Strategy.POINTS);
        List<VectorSearch.Hit> hs = List.of(new VectorSearch.Hit(other, 0.9), new VectorSearch.Hit(gold, 0.8));
        assertEquals(2, RetrievalMetrics.rank(hs, question, 5));
        assertEquals(0, RetrievalMetrics.rank(hs, question, 1));
    }

    @Test
    void twoPointQuestionNeedsAllFragments() {
        Lab4Variant.Question two = new Lab4Variant.Question("V-09", "?", "два пункти", true, "d", List.of("подати заяву протягом тижня", "інакше відрахування"),
                List.of("тижн"), false, null);
        Chunk a = new Chunk("a", "d", "t", "", 1, "Треба подати заяву протягом тижня після сесії.", Chunker.Strategy.POINTS);
        Chunk x = new Chunk("x", "d", "t", "", 1, "Інший пункт про розклад.", Chunker.Strategy.POINTS);
        Chunk b = new Chunk("b", "d", "t", "", 1, "Хто не подав — інакше відрахування з університету.", Chunker.Strategy.POINTS);
        assertTrue(two.needsAll());
        assertEquals(0, RetrievalMetrics.rank(List.of(new VectorSearch.Hit(a, 1), new VectorSearch.Hit(x, 1)), two, 5), "знайдено лише один фрагмент — не знайдено");
        assertEquals(3, RetrievalMetrics.rank(List.of(new VectorSearch.Hit(a, 1), new VectorSearch.Hit(x, 1), new VectorSearch.Hit(b, 1)), two, 5),
                "місце — там, де знайдено останній фрагмент");
        Map<String, Chunk> given = Map.of("a", a, "b", b, "x", x);
        assertFalse(AutoCheck.of(two, new RagAnswer("Протягом тижня.", List.of("a"), true), given).citeHit(), "цитата лише одного пункту");
        assertTrue(AutoCheck.of(two, new RagAnswer("Протягом тижня, інакше відрахування.", List.of("a", "b"), true), given).citeHit());
    }

    // ------------------------------------------------------------------ валідатор і автоперевірка

    @Test
    void validatorChecksCitedIdsAndAnswerable() {
        List<String> ids = List.of("osv-8.3-07", "osv-13-02");
        assertTrue(RagAnswer.validate("{\"answer\":\"Двічі.\",\"cited_ids\":[\"osv-8.3-07\"],\"answerable\":true}", ids).ok());
        RagAnswer.Check fake = RagAnswer.validate("{\"answer\":\"Двічі.\",\"cited_ids\":[\"osv-9.9\"],\"answerable\":true}", ids);
        assertFalse(fake.ok());
        assertTrue(fake.error().contains("osv-9.9") && fake.error().contains("не було серед переданих"), fake.error());
        assertTrue(RagAnswer.validate("{\"answer\":\"Двічі.\",\"cited_ids\":[],\"answerable\":true}", ids).error().contains("cited_ids порожній"));
        assertTrue(RagAnswer.validate("{\"answer\":\"Немає.\",\"cited_ids\":[],\"answerable\":false}", ids).error().contains("деканату"));
        assertTrue(RagAnswer.validate("{\"answer\":\"" + RagPrompts.NOT_FOUND + "\",\"cited_ids\":[],\"answerable\":false}", ids).ok());
        assertFalse(RagAnswer.validate("не JSON", ids).ok());
        assertFalse(RagAnswer.validate("{\"answer\":\"x\",\"cited_ids\":\"osv\",\"answerable\":true}", ids).ok());
        assertFalse(RagAnswer.validate("{\"answer\":\"x\",\"cited_ids\":[\"osv-13-02\"],\"answerable\":\"так\"}", ids).ok());
    }

    @Test
    void autoCheckNeedsAnswerableCitationAndFacts() {
        Lab4Variant.Question gold = new Lab4Variant.Question("V-01", "Скільки разів?", "синонім", true, "osv",
                List.of("допускається не більше двох разів"), List.of("двох|двічі", "коміс"), false, null);
        Chunk hit = new Chunk("osv-8.3", "osv", "t", "", 1, "Повторне складання допускається не більше двох разів", Chunker.Strategy.POINTS);
        Chunk miss = new Chunk("osv-13", "osv", "t", "", 1, "Заочна форма", Chunker.Strategy.POINTS);
        Map<String, Chunk> given = Map.of(hit.id(), hit, miss.id(), miss);
        AutoCheck ok = AutoCheck.of(gold, new RagAnswer("Двічі: викладачеві й комісії.", List.of("osv-8.3"), true), given);
        assertTrue(ok.correct());
        assertEquals("2/2", ok.factsText());
        assertFalse(AutoCheck.of(gold, new RagAnswer("Двічі: викладачеві й комісії.", List.of("osv-13"), true), given).correct(), "цитата не та");
        assertFalse(AutoCheck.of(gold, new RagAnswer("Двічі.", List.of("osv-8.3"), true), given).correct(), "бракує факту");
        assertFalse(AutoCheck.of(gold, new RagAnswer(RagPrompts.NOT_FOUND, List.of(), false), given).correct());
        Lab4Variant.Question none = new Lab4Variant.Question("V-10", "Кіт?", "без відповіді", false, null, null, null, false, "немає");
        assertTrue(AutoCheck.of(none, new RagAnswer(RagPrompts.NOT_FOUND, List.of(), false), given).correct());
        assertFalse(AutoCheck.of(none, new RagAnswer("Так, можна.", List.of("osv-13"), true), given).correct());
        assertFalse(AutoCheck.of(none, null, given).correct(), "ворота здалися — неправильно");
    }

    // ------------------------------------------------------------------ ембединги (проти імітації)

    @Test
    void embedderBuildsBatchRequestsWithoutTaskTypeAndParsesResponse() throws Exception {
        Embedder e = new Embedder(url(), "test-key", Embedder.DEFAULT_MODEL, Embedder.DIM, 2, 0, new EmbeddingCache(Path.of("unused"), false));
        JsonNode body = e.buildRequest(List.of(Prefixes.query("Скільки разів?"), Prefixes.document(null, "Текст")));
        assertEquals(2, body.path("requests").size());
        JsonNode r0 = body.path("requests").get(0);
        assertEquals("models/gemini-embedding-2", r0.path("model").asText());
        assertEquals("task: search result | query: Скільки разів?", r0.path("content").path("parts").get(0).path("text").asText());
        assertEquals(768, r0.path("outputDimensionality").asInt());
        assertFalse(body.toString().toLowerCase().contains("task_type") || body.toString().contains("taskType"), "task_type для gemini-embedding-2 не надсилаємо");
        assertEquals("title: none | text: Текст", body.path("requests").get(1).path("content").path("parts").get(0).path("text").asText());
        assertEquals("title: Положення | text: Т", Prefixes.document("Положення", "Т"));
        JsonNode resp = new ObjectMapper().readTree("{\"embeddings\":[{\"values\":[0.6,0.8]},{\"values\":[1,0]}]}");
        List<float[]> v = Embedder.parse(resp, 2);
        assertEquals(0.8f, v.get(0)[1], 1e-6);
        assertThrows(ApiException.class, () -> Embedder.parse(resp, 3), "кількість ембедингів має збігатися з кількістю текстів");
        assertThrows(ApiException.class, () -> Embedder.parse(resp, 2, 768), "вектор має бути рівно 768 чисел");
        assertEquals(2, Embedder.parse(resp, 2, 2).size());
    }

    @Test
    void retryDelayAndDailyQuotaAreReadFrom429Body() {
        String minute = "{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\",\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\"37s\"}]}}";
        assertEquals(37_500L, HttpJson.retryDelayMs(minute).getAsLong());
        assertFalse(HttpJson.isDailyQuota(minute));
        assertTrue(HttpJson.isDailyQuota("{\"error\":{\"message\":\"quota_id: EmbedContentRequestsPerDayPerUserPerProjectPerModel-FreeTier\"}}"));
        assertTrue(HttpJson.retryDelayMs("{}").isEmpty());
    }

    @Test
    void embedderWaitsOn429AndReportsDailyLimit() throws Exception {
        Embedder e = new Embedder(url(), "test-key", Embedder.DEFAULT_MODEL, Embedder.DIM, 10, 0, new EmbeddingCache(Path.of("unused"), false));
        FakeLlmServer.embed429 = 1;
        try {
            long t0 = System.currentTimeMillis();
            assertEquals(1, e.embed(List.of("title: none | text: 429 спершу")).size());
            assertTrue(System.currentTimeMillis() - t0 >= 1000, "чекали retryDelay з тіла 429");
        } finally {
            FakeLlmServer.embed429 = 0;
        }
        FakeLlmServer.embedDailyLimit = true;
        try {
            ApiException ex = assertThrows(ApiException.class, () -> e.embed(List.of("title: none | text: добовий ліміт")));
            assertTrue(ex.dailyQuota());
            assertTrue(Lab4Run_apiText(ex).contains("Продовжіть завтра"));
        } finally {
            FakeLlmServer.embedDailyLimit = false;
        }
    }

    private static String Lab4Run_apiText(ApiException e) {
        try {
            var m = Lab4Run.class.getDeclaredMethod("apiErrorText", ApiException.class);
            m.setAccessible(true);
            return (String) m.invoke(null, e);
        } catch (ReflectiveOperationException x) {
            throw new IllegalStateException(x);
        }
    }

    @Test
    void tokenBudgetSplitsBatchesAndBundleRoundTrips() throws Exception {
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 4; i++) texts.add("title: none | text: " + "слово ".repeat(100) + i); // ≈ 210 токенів оцінкою
        Embedder e = new Embedder(url(), "test-key", Embedder.DEFAULT_MODEL, Embedder.DIM, 100, 0, new EmbeddingCache(Path.of("unused"), false), null, 450);
        List<float[]> v = e.embed(texts.subList(0, 2));
        assertEquals(1, e.requests(), "два тексти ≈ 420 токенів вміщаються в 450 за хвилину");
        assertEquals(450, e.tpm());
        // кеш викладача: записати → прочитати → Embedder бере вектори без запитів
        Path f = Files.createTempDirectory("lab4bundle").resolve("b.bin.gz");
        Map<String, float[]> map = new LinkedHashMap<>();
        for (int i = 0; i < 2; i++) map.put(EmbeddingCache.key(Embedder.DEFAULT_MODEL, Embedder.DIM, texts.get(i)), v.get(i));
        EmbeddingBundle.write(f, Embedder.DEFAULT_MODEL, Embedder.DIM, "2026-11-12", map);
        EmbeddingBundle b;
        try (var in = Files.newInputStream(f)) {
            b = EmbeddingBundle.read(in);
        }
        assertEquals(2, b.size());
        assertEquals(VectorSearch.cosine(v.get(0), v.get(1)), VectorSearch.cosine(b.get(EmbeddingCache.key(Embedder.DEFAULT_MODEL, Embedder.DIM, texts.get(0))),
                b.get(EmbeddingCache.key(Embedder.DEFAULT_MODEL, Embedder.DIM, texts.get(1)))), 1e-4, "int16 майже не змінює косинус");
        Embedder withBundle = new Embedder(url(), "test-key", Embedder.DEFAULT_MODEL, Embedder.DIM, 100, 0, new EmbeddingCache(Path.of("unused"), false), b, 0);
        withBundle.embed(texts);
        assertEquals(2, withBundle.fromBundle());
        assertEquals(2, withBundle.embedded(), "решту (немає в кеші викладача) пораховано запитом");
        assertNotNull(EmbeddingBundle.fromResources(true, Embedder.DEFAULT_MODEL, Embedder.DIM), "MOCK-кеш викладача є в ресурсах");
        assertNull(EmbeddingBundle.fromResources(true, "інша-модель", Embedder.DIM), "кеш для іншої моделі не використовується");
    }

    @Test
    void embedderBatchesAgainstMockAndUsesDiskCache() throws Exception {
        Path f = Files.createTempDirectory("lab4emb").resolve("emb.tsv");
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 5; i++) texts.add(Prefixes.document(null, "Шматок номер " + i + " про стипендію і гуртожиток"));
        Embedder e = new Embedder(url(), "test-key", Embedder.DEFAULT_MODEL, Embedder.DIM, 2, 0, new EmbeddingCache(f, true));
        List<float[]> v = e.embed(texts);
        assertEquals(3, e.requests(), "5 текстів пакетами по 2 → 3 запити");
        assertEquals(5, e.embedded());
        for (float[] x : v) {
            assertEquals(768, x.length);
            double n = 0;
            for (float y : x) n += y * y;
            assertEquals(1.0, Math.sqrt(n), 1e-3, "імітація нормалізує вектор");
        }
        Embedder again = new Embedder(url(), "test-key", Embedder.DEFAULT_MODEL, Embedder.DIM, 2, 0, new EmbeddingCache(f, true));
        List<float[]> v2 = again.embed(texts);
        assertEquals(0, again.requests(), "усе з кешу на диску");
        assertEquals(5, again.cached());
        assertArrayEquals(v.get(3), v2.get(3), 1e-7f);
        assertNotEquals(EmbeddingCache.key("m", 768, "a"), EmbeddingCache.key("m", 768, "task: search result | query: a"), "інший префікс — інший ключ");
        assertNotEquals(EmbeddingCache.key("m", 768, "a"), EmbeddingCache.key("m", 3072, "a"));
        // імітація: питання ближче до шматка зі спільними словами, ніж до непов'язаного
        Embedder m = new Embedder(url(), "test-key", Embedder.DEFAULT_MODEL, Embedder.DIM, 100, 0, new EmbeddingCache(f, false));
        List<float[]> w = m.embed(List.of(Prefixes.query("Скільки разів можна перескладати іспит?"),
                Prefixes.document(null, "Повторне складання екзаменів допускається не більше двох разів"),
                Prefixes.document(null, "Оплата за проживання у гуртожитку здійснюється щомісяця")));
        assertTrue(VectorSearch.cosine(w.get(0), w.get(1)) > VectorSearch.cosine(w.get(0), w.get(2)));
    }

    // ------------------------------------------------------------------ варіанти

    @Test
    void allEightVariantsHave12QuestionsWithValidGold() {
        Set<String> docIds = new HashSet<>();
        Corpus.load(false).documents().forEach(d -> docIds.add(d.id()));
        Lab4Variant.Question common = Lab4Variant.common(false);
        assertEquals("Скільки разів можна перескладати іспит?", common.text());
        assertTrue(docIds.contains(common.doc()));
        Set<String> texts = new HashSet<>();
        for (Lab4Variant v : Lab4Variant.loadAll()) {
            String w = "варіант " + v.variant() + ": ";
            assertEquals(12, v.questions().size(), w);
            assertEquals(9, v.answerable().size(), w);
            assertEquals(5, v.step1(common).size(), w + "крок 1 — 5 питань");
            List<String> kinds = v.answerable().stream().map(Lab4Variant.Question::kind).toList();
            long syn = kinds.stream().filter("синонім"::equals).count();
            assertTrue(syn >= 2 && syn <= 3, w + "синонімічних 2–3: " + kinds);
            Set<String> frs = new HashSet<>();
            for (Lab4Variant.Question q : v.answerable()) for (String f : q.fragments()) assertTrue(frs.add(f), w + "повтор фрагмента " + f);
            assertTrue(kinds.contains("код") && kinds.contains("два пункти"), w + kinds);
            for (Lab4Variant.Question q : v.questions()) {
                assertTrue(texts.add(q.text()), "повтор питання " + q.text());
                if (q.kind().equals("два пункти")) assertTrue(q.needsAll(), q.id() + ": «два пункти» — два фрагменти");
                assertTrue(q.id().startsWith(String.format("V%02d-", v.variant())), q.id());
                if (q.answerable()) {
                    assertTrue(docIds.contains(q.doc()), q.id() + " " + q.doc());
                    assertTrue(q.fragments().size() >= 1 && q.fragments().size() <= 2, q.id());
                    for (String f : q.fragments()) {
                        int words = f.trim().split("\\s+").length;
                        assertTrue(words >= 6 && words <= 25, q.id() + ": " + words + " слів");
                    }
                    assertTrue(q.keyFacts().size() >= 1 && q.keyFacts().size() <= 3, q.id());
                } else {
                    assertFalse(q.step1(), q.id());
                    assertFalse(q.why() == null || q.why().isBlank(), q.id() + " — чому відповіді немає");
                }
            }
        }
        assertEquals(1, Lab4Variant.resolve(null, true));
        assertThrows(IllegalArgumentException.class, () -> Lab4Variant.resolve(null, false));
        assertThrows(IllegalArgumentException.class, () -> Lab4Variant.resolve("9", false));
        assertEquals(5, Lab4Variant.resolve(" 5 ", false));
    }

    @Test
    void myQuestionsTemplateIsEmptyAndLooksForDuplicates() {
        assertTrue(MyQuestions.current().isEmpty(), "шаблон для студента — порожній");
        assertTrue(MyRetrievalTweak.current().isEmpty(), "шаблон кроку 4б — порожній");
    }

    @Test
    void keyFactsAreNotTrivialAndNotInTheQuestion() {
        List<Lab4Variant.Question> qs = new ArrayList<>();
        qs.add(Lab4Variant.common(false));
        for (Lab4Variant v : Lab4Variant.loadAll()) qs.addAll(v.questions());
        int n = 0;
        for (Lab4Variant.Question q : qs) {
            if (!q.answerable()) continue;
            n++;
            for (String f : q.keyFacts()) {
                for (String alt : f.split("\\|")) {
                    assertFalse(Set.of("ні", "так", "можна", "ста", "не", "є").contains(alt.strip()), q.id() + ": тривіальний факт «" + alt + "»");
                    assertFalse(alt.strip().matches("\\d"), q.id() + ": однозначне число без контексту «" + alt + "»");
                }
                assertFalse(TextNorm.hasFact(q.text(), f), q.id() + ": ключовий факт «" + f + "» вже є в самому питанні");
            }
        }
        assertEquals(1 + 8 * 9, n);
    }

    // ------------------------------------------------------------------ повний прогін у MOCK

    private static Lab4Run.Result runMock(Config.Provider p, String model, Path dir, List<MyQuestions.Mine> mine) {
        return runMock(p, model, dir, mine, "", Lab4Variant.mock(1));
    }

    private static Lab4Run.Result runMock(Config.Provider p, String model, Path dir, List<MyQuestions.Mine> mine, String tweak, Lab4Variant variant) {
        Config cfg = new Config(p, "test-key", model, url(), true, 0, null, null);
        LlmClient client = p == Config.Provider.CLAUDE ? new ClaudeClient(cfg, url()) : new GeminiClient(cfg, url());
        Embedder emb = new Embedder(url(), "test-key", Embedder.DEFAULT_MODEL, Embedder.DIM, 100, 0, new EmbeddingCache(dir.resolve("emb.tsv"), true),
                EmbeddingBundle.fromResources(true, Embedder.DEFAULT_MODEL, Embedder.DIM), 0);
        AnswerCache cache = new AnswerCache(dir.resolve("cache.json"), true);
        PrintStream old = System.out;
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        try {
            return Lab4Run.run(client, emb, cache, variant, Lab4Variant.common(true), CorpusLoader.loadMock(Corpus.load(true)),
                    "mock", mine, tweak, true, dir.resolve("results.md"));
        } finally {
            System.setOut(old);
        }
    }

    @Test
    void fullMockRunOnGeminiAndClaudeThenFromCache() throws Exception {
        for (Config.Provider p : List.of(Config.Provider.GEMINI, Config.Provider.CLAUDE)) {
            Path dir = Files.createTempDirectory("lab4run");
            String model = p == Config.Provider.CLAUDE ? "claude-sonnet-5-5" : "gemini-3.5-flash-lite";
            Lab4Run.Result r = runMock(p, model, dir, List.of());
            String w = p + ": ";
            assertEquals(3, r.chunkStats().size());
            assertTrue(r.chunkStats().get(Chunker.Strategy.POINTS).count() > r.chunkStats().get(Chunker.Strategy.FIXED).count(), w);
            assertEquals(9, r.vectorRanks().get(Chunker.Strategy.FIXED).size());
            assertEquals(9, r.bm25Ranks().size());
            assertNotNull(r.bm25MorfRanks(), w + "бонус морфології");
            assertEquals(12, r.minimal().size());
            assertEquals(12, r.high().size());
            assertTrue(r.newCalls() >= 5 + 24 && r.newCalls() <= 5 + 24 * 3, w + r.newCalls());
            assertEquals(0, r.embedRequests(), w + "шматки й питання MOCK — у кеші викладача");
            assertTrue(r.embedFromBundle() > 0, w);
            assertNull(r.tweak(), w + "крок 4б без промпта не виконується");
            assertTrue(r.excluded().isEmpty(), w);
            String md = Files.readString(dir.resolve("results.md"));
            for (String must : List.of("## Як читати цей файл", "Перевірка еталону", "Крок 1.", "Крок 2.", "Крок 3.", "Крок 4.", "Крок 4б.", "Крок 5.", "Крок 6.",
                    "Що це:", "Як читати:", "Приклад:", "recall@5", "MRR@5", "cited_ids", "answerable", "effort", "RRF", "BM25", "Ціна приросту",
                    "Промахи: що пошук знайшов замість", "Цитовані шматки", "(в) проти (б)", "«Два пункти»", "лематизація", "Чи цілі правила вашого варіанта",
                    "SHA-256 файлу"))
                assertTrue(md.contains(must), w + "у results.md немає «" + must + "»");
            assertTrue(md.contains("MOCK"), w);
            Lab4Run.Result again = runMock(p, model, dir, List.of());
            assertEquals(0, again.newCalls(), w + "повторний прогін — з кешу");
            assertEquals(0, again.embedRequests(), w);
            assertEquals(r.newCalls(), again.cachedCalls(), w);
            // крок 6: три власні питання → нові запити лише для них
            Lab4Run.Result mine = runMock(p, model, dir, List.of(new MyQuestions.Mine("Скільки балів треба для заліку?", true),
                    new MyQuestions.Mine("Що робити, якщо захворів під час сесії?", true), new MyQuestions.Mine("Чи дають знижку на їдальню відмінникам?", false)));
            assertEquals(3, mine.myQuestions());
            assertTrue(mine.newCalls() >= 6 && mine.newCalls() <= 18, w + mine.newCalls());
            assertEquals(1, mine.embedRequests(), w + "один пакет ембедингів для трьох питань");
            // крок 4б: ваш промпт → 9 генерацій (переписати питання) і 1 пакет ембедингів
            Lab4Run.Result tw = runMock(p, model, dir, List.of(), "Перепиши питання мовою положень університету.", Lab4Variant.mock(1));
            assertNotNull(tw.tweak());
            assertEquals(9, tw.tweak().rewritten().size());
            assertTrue(tw.tweak().rewritten().stream().anyMatch(x -> x.contains("екзамен")), w + "імітація замінює «іспит» на «екзамен»: " + tw.tweak().rewritten());
            assertEquals(9, tw.newCalls(), w);
            assertEquals(1, tw.embedRequests(), w);
            assertTrue(Files.readString(dir.resolve("results.md")).contains("до: recall@5"), w);
        }
    }

    @Test
    void questionWithMissingGoldIsExcludedFromMetrics() throws Exception {
        Lab4Variant m = Lab4Variant.mock(1);
        List<Lab4Variant.Question> qs = new ArrayList<>(m.questions());
        Lab4Variant.Question first = qs.get(0);
        qs.set(0, new Lab4Variant.Question(first.id(), first.text(), first.kind(), true, first.doc(), List.of("цього фрагмента в положенні немає зовсім"),
                first.keyFacts(), first.step1(), null));
        Path dir = Files.createTempDirectory("lab4excl");
        Lab4Run.Result r = runMock(Config.Provider.GEMINI, "gemini-3.5-flash-lite", dir, List.of(), "", new Lab4Variant(m.variant(), m.title(), m.docs(), qs));
        assertEquals(List.of(first.shortId()), r.excluded());
        assertEquals(8, r.vectorRanks().get(Chunker.Strategy.FIXED).size(), "у метриках — 8 питань");
        assertEquals(11, r.minimal().size());
        assertTrue(Files.readString(dir.resolve("results.md")).contains("Положення оновилось — питання " + first.shortId() + " виключено з метрик"));
    }

    @Test
    void myQuestionsProblemsAreReported() throws Exception {
        Path dir = Files.createTempDirectory("lab4mine");
        Lab4Run.Result r = runMock(Config.Provider.GEMINI, "gemini-3.5-flash-lite", dir,
                List.of(new MyQuestions.Mine("Що таке талон №3?", true), new MyQuestions.Mine("Ще одне?", true)));
        assertEquals(0, r.myQuestions(), "2 питання, усі з відповіддю, одне — копія питання варіанта → не прийнято");
        String md = Files.readString(dir.resolve("results.md"));
        assertTrue(md.contains("Питання НЕ прийнято"));
        assertTrue(md.contains("«Що таке талон №2?»") || md.contains("«Що таке талон №3?»") || md.contains("M-02"), "показано, з яким питанням збіг");
    }
}
