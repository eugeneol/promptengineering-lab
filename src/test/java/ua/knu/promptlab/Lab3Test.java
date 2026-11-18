package ua.knu.promptlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ua.knu.promptlab.lab3.AnswerCache;
import ua.knu.promptlab.lab3.Classifier;
import ua.knu.promptlab.lab3.Lab3Prompts;
import ua.knu.promptlab.lab3.Lab3Run;
import ua.knu.promptlab.lab3.Labels;
import ua.knu.promptlab.lab3.Metrics;
import ua.knu.promptlab.lab3.MyPromptTweaks;
import ua.knu.promptlab.lab3.Tweaks;
import ua.knu.promptlab.lab3.Variant;
import ua.knu.promptlab.mock.FakeLlmServer;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Лабораторна 3: дані варіантів (мітки, alt, схожість прикладів), арифметика вимірювань (точність, шум, найважчі, голосування, поріг)
 * на підроблених відповідях, кеш, ваша правка (крок 3) і параметри міркування — без мережі. Повний прогін — проти імітації (MOCK).
 */
class Lab3Test {

    @BeforeAll
    static void start() {
        FakeLlmServer.startOnce();
    }

    /** Адреса імітації щоразу заново: тест повного прогону перезапускає сервер (новий порт). */
    private static String url() {
        return FakeLlmServer.startOnce().baseUrl();
    }

    @AfterAll
    static void stop() {
        FakeLlmServer.stop();
    }

    private static Config cfg(Config.Provider p, String model, String level, Integer budget) {
        return new Config(p, "test-key", model, url(), true, 0, level, budget);
    }

    // ------------------------------------------------------------------ дані варіантів

    @Test
    void allEightVariantsLoadWithValidLabels() {
        List<Variant> all = Variant.loadAll();
        assertEquals(8, all.size());
        for (Variant v : all) {
            String w = "варіант " + v.variant() + ": ";
            assertEquals(15, v.requests().size(), w + "звернень");
            assertEquals(5, v.requests().stream().filter(Variant.Inquiry::isTrap).count(), w + "пасток");
            assertTrue(v.requests().stream().filter(q -> "urgency".equals(q.trapField())).count() <= 2, w + "не більше 2 пасток на urgency");
            for (Variant.Inquiry q : v.requests()) {
                assertTrue(Variant.CATEGORIES.contains(q.category()), w + q.id() + " category " + q.category());
                assertTrue(Variant.URGENCIES.contains(q.urgency()), w + q.id() + " urgency " + q.urgency());
                assertTrue(Variant.CATEGORIES.contains(q.distractor()) && !q.distractor().equals(q.category()), w + q.id() + " distractor");
                assertFalse(q.text().isBlank(), w + q.id());
                assertTrue(q.accepts(q.expected()), w + q.id() + " основна мітка має прийматися");
                if (q.isTrap()) {
                    assertTrue(Variant.TRAPS.containsKey(q.trap()), w + q.id() + " невідома пастка " + q.trap());
                    assertTrue(Variant.FIELDS.contains(q.trapField()), w + q.id() + " trap_field");
                    assertFalse(q.why() == null || q.why().isBlank(), w + q.id() + " у пастки має бути «чому»");
                }
                if (q.hasAlt()) {
                    assertFalse(q.why() == null || q.why().isBlank(), w + q.id() + " у спірної мітки має бути «чому»");
                    q.alt().forEach((field, vals) -> {
                        assertTrue(Variant.FIELDS.contains(field), w + q.id() + " alt-поле " + field);
                        assertEquals(2, vals.size(), w + q.id() + " alt — рівно дві відповіді");
                        List<String> allowed = field.equals("category") ? Variant.CATEGORIES : field.equals("urgency") ? Variant.URGENCIES : List.of("true", "false");
                        assertTrue(allowed.containsAll(q.accepted(field)), w + q.id() + " alt-значення " + vals);
                    });
                }
            }
            assertEquals(5, new HashSet<>(v.hardestFallback()).size(), w + "запасний список — 5 різних");
            v.hardestFallback().forEach(v::byId); // кидає, якщо такого id немає
            assertEquals(Variant.FEW_SHOT_K, v.examples().size(), w + "прикладів k = 3");
            for (Variant.Example e : v.examples()) {
                assertTrue(Variant.CATEGORIES.contains(e.category()) && Variant.URGENCIES.contains(e.urgency()), w + e.id());
            }
            assertTrue(Variant.CATEGORIES.containsAll(v.pair()) && v.pair().size() == 2, w + "пара категорій");
        }
    }

    @Test
    void idsAndTextsAreUniqueAcrossVariants() {
        Set<String> ids = new HashSet<>(), texts = new HashSet<>();
        int n = 0;
        for (Variant v : Variant.loadAll()) {
            for (Variant.Inquiry q : v.requests()) {
                assertTrue(ids.add(q.id()), "id повторюється: " + q.id());
                assertTrue(texts.add(q.text()), "текст повторюється: " + q.text());
                n++;
            }
            for (Variant.Example e : v.examples()) {
                assertTrue(ids.add(e.id()), "id повторюється: " + e.id());
                assertTrue(texts.add(e.text()), "приклад збігається з іншим текстом: " + e.text());
                n++;
            }
        }
        assertEquals(8 * 15 + 8 * 3, n);
    }

    /** Приклад few-shot не має майже копіювати тестове звернення (будь-якого варіанта): схожість Жаккара за словами &lt; 0,5. */
    @Test
    void examplesAreNotSimilarToAnyTestInquiry() {
        assertEquals(1.0, Variant.jaccard("Коли буде виплата?", "коли БУДЕ виплата"), 1e-9);
        assertEquals(0.0, Variant.jaccard("гуртожиток", "стипендія"), 1e-9);
        List<Variant> all = Variant.loadAll();
        double worst = 0;
        for (Variant ve : all) {
            for (Variant.Example e : ve.examples()) {
                for (Variant vt : all) {
                    for (Variant.Inquiry q : vt.requests()) {
                        double s = Variant.jaccard(e.text(), q.text());
                        worst = Math.max(worst, s);
                        assertTrue(s < 0.5, e.id() + " занадто схожий на " + q.id() + ": " + s);
                    }
                }
            }
        }
        assertTrue(worst > 0, "перевірка справді щось порівнює");
    }

    @Test
    void variantsDifferByInquiriesTrapsAndPair() {
        Set<List<String>> pairs = new HashSet<>();
        Set<Set<String>> trapSets = new HashSet<>();
        for (Variant v : Variant.loadAll()) {
            pairs.add(v.pair());
            Set<String> traps = new HashSet<>();
            v.requests().stream().filter(Variant.Inquiry::isTrap).forEach(q -> traps.add(q.trap()));
            trapSets.add(traps);
            assertFalse(traps.contains("injection"), "ін'єкції прибрано з усіх варіантів (вони — у Л6)");
        }
        assertEquals(8, pairs.size(), "у кожного варіанта своя пара, яку найчастіше плутають");
        assertEquals(8, trapSets.size(), "набори пасток між варіантами різні");
    }

    @Test
    void variantNumberFromSettings() {
        assertEquals(1, Variant.resolve(null, true));
        assertEquals(3, Variant.resolve("3", false));
        assertEquals(5, Variant.resolve(" 5 ", false));
        IllegalArgumentException none = assertThrows(IllegalArgumentException.class, () -> Variant.resolve(null, false));
        assertTrue(none.getMessage().contains("LAB_VARIANT=") && none.getMessage().contains("mod 8") && none.getMessage().contains("Лаба 3 (MOCK)"), none.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Variant.resolve("9", false));
        assertThrows(IllegalArgumentException.class, () -> Variant.resolve("0", true));
        assertThrows(IllegalArgumentException.class, () -> Variant.resolve("три", false));
    }

    // ------------------------------------------------------------------ розбір відповіді, промпти, схема

    @Test
    void labelsParseNormalizeAndFailSoftly() {
        Labels l = Labels.parse("{\"category\": \"Study\", \"urgency\": \"HIGH \", \"needs_human\": true}");
        assertTrue(l.parsed());
        assertEquals("study", l.category());
        assertEquals("high", l.urgency());
        Labels r = Labels.parse("{\"reasoning\": \"бо іспит завтра\", \"category\": \"study\", \"urgency\": \"high\", \"needs_human\": false}");
        assertEquals("бо іспит завтра", r.reasoning());
        assertFalse(Labels.parse("{\"category\": \"study\", \"urgency\":").parsed());
        assertFalse(Labels.parse("{\"category\": \"study\", \"urgency\": \"low\"}").parsed());
        assertFalse(Labels.parse("").parsed());
        assertFalse(Variant.load(1).requests().get(0).accepts(Labels.unparsed("x")));
    }

    @Test
    void systemPromptHasClarifiedDefinitionsAndTweaksGoInTheRightPlace() {
        String s = Lab3Prompts.SYSTEM;
        for (String must : List.of("будь-який розклад", "скарги на працівників", "загроза відрахування за несплату", "навіть якщо студент згадує свою ситуацію",
                "Сам стрес чи страх відрахування — не high", "видача будь-якого документа", "розблокування чи активація акаунта", "самостійно відновити")) {
            assertTrue(s.contains(must), "у системному промпті немає: " + must);
        }
        Variant v = Variant.load(2);
        String sys = Lab3Prompts.system(v.fewShot());
        assertTrue(sys.startsWith(Lab3Prompts.SYSTEM));
        assertEquals(3, sys.split("\nВідповідь: \\{", -1).length - 1);
        assertEquals(Lab3Prompts.SYSTEM, Lab3Prompts.system(List.of()));
        assertTrue(Lab3Prompts.withCareer(sys).endsWith(Lab3Prompts.CAREER));
        List<Variant.Example> withMine = new ArrayList<>(v.fewShot());
        withMine.add(Tweaks.example("Мій приклад", "schedule", "low", false));
        String mine = Lab3Prompts.system(withMine, "Моє правило.");
        assertTrue(mine.indexOf(Lab3Prompts.RULE_PREFIX + "Моє правило.") < mine.indexOf("Приклади"), "правило — перед прикладами");
        assertEquals(4, mine.split("\nВідповідь: \\{", -1).length - 1);
    }

    @Test
    void reasoningGoesFirstAlsoInLegacyGeminiSchema() {
        JsonNode s = Lab3Prompts.schema(true);
        assertEquals("reasoning", s.path("properties").fieldNames().next());
        assertEquals("reasoning", s.path("required").get(0).asText());
        assertFalse(Lab3Prompts.schema(false).path("properties").has("reasoning"));
        assertEquals(7, s.path("properties").path("category").path("enum").size());
        JsonNode legacy = GeminiClient.toLegacySchema(s);
        assertFalse(legacy.has("additionalProperties"));
        assertEquals("reasoning", legacy.path("propertyOrdering").get(0).asText(), "у responseSchema порядок задає propertyOrdering");
        assertEquals(4, legacy.path("propertyOrdering").size());
        assertEquals("needs_human", legacy.path("propertyOrdering").get(3).asText());
    }

    // ------------------------------------------------------------------ арифметика на підроблених відповідях

    private static List<Labels> perfect(List<Variant.Inquiry> qs) {
        return new ArrayList<>(qs.stream().map(Variant.Inquiry::expected).toList());
    }

    @Test
    void accuracyCountsAllThreeLabelsAndTrapsSeparately() {
        Variant v = Variant.load(1);
        List<Variant.Inquiry> qs = v.requests();
        List<Labels> got = perfect(qs);
        Metrics.Score s = Metrics.score(qs, got);
        assertEquals(15, s.correct());
        assertEquals(5, s.trapsCorrect());
        assertEquals("15/15 (100%)", s.accuracyText());

        int trap = qs.indexOf(qs.stream().filter(Variant.Inquiry::isTrap).findFirst().orElseThrow());
        Variant.Inquiry t = qs.get(trap);
        got.set(trap, new Labels(t.distractor(), t.urgency(), t.needsHuman(), null)); // лише категорія не та
        got.set(0, new Labels(qs.get(0).category(), qs.get(0).urgency(), !qs.get(0).needsHuman(), null)); // лише needs_human
        s = Metrics.score(qs, got);
        assertEquals(13, s.correct());
        assertEquals(14, s.categoryOk());
        assertEquals(15, s.urgencyOk());
        assertEquals(14, s.humanOk());
        assertEquals(4, s.trapsCorrect());
        assertEquals("category", Metrics.wrongFields(t, got.get(trap)));
        assertEquals(0.8, s.trapAccuracy(), 1e-9);
    }

    @Test
    void altAnswersCountAsMatch() {
        Variant.Inquiry q = Variant.load(1).byId("V01-05"); // needs_human: false або true
        assertTrue(q.hasAlt());
        assertTrue(q.accepts(new Labels("access", "normal", false, null)));
        assertTrue(q.accepts(new Labels("access", "normal", true, null)), "alt-значення — теж збіг");
        assertFalse(q.accepts(new Labels("access", "low", true, null)), "alt стосується лише свого поля");
        assertEquals("", Metrics.wrongFields(q, new Labels("access", "normal", true, null)));

        Variant.Inquiry c = Variant.load(2).byId("V02-09"); // category: other або study
        Labels study = new Labels("study", c.urgency(), c.needsHuman(), null);
        assertTrue(c.accepts(study));
        assertTrue(Metrics.confusions(List.of(c), List.of(study)).isEmpty(), "допустима категорія — не «сплутана»");
        assertEquals(1, Metrics.score(List.of(c), List.of(study)).categoryOk());
        assertFalse(c.accepts(new Labels("schedule", c.urgency(), c.needsHuman(), null)));

        Variant.Inquiry u = Variant.load(2).byId("V02-13"); // urgency: normal або high
        assertEquals(List.of("normal", "high"), u.accepted("urgency"));
        assertEquals(List.of("true"), u.accepted("needs_human"));
        long alts = Variant.loadAll().stream().flatMap(v -> v.requests().stream()).filter(Variant.Inquiry::hasAlt).count();
        assertTrue(alts >= 8 && alts <= 15, "alt — лише для справді спірних: " + alts);
    }

    @Test
    void confusionPairsAndMatrix() {
        Variant v = Variant.load(1);
        List<Variant.Inquiry> qs = v.requests();
        List<Labels> got = perfect(qs);
        // дві помилки study → electives і одна access → other
        int changed = 0;
        for (int i = 0; i < qs.size() && changed < 2; i++) {
            if (qs.get(i).category().equals("study")) {
                got.set(i, new Labels("electives", qs.get(i).urgency(), qs.get(i).needsHuman(), null));
                changed++;
            }
        }
        for (int i = 0; i < qs.size(); i++) {
            if (qs.get(i).category().equals("access")) {
                got.set(i, new Labels("other", qs.get(i).urgency(), qs.get(i).needsHuman(), null));
                break;
            }
        }
        Map<String, Integer> conf = Metrics.confusions(qs, got);
        assertEquals(List.of("study → electives", "access → other"), new ArrayList<>(conf.keySet()));
        assertEquals(Integer.valueOf(2), conf.get("study → electives"));
        assertEquals(2, Metrics.pairErrors(qs, got, v.pair()), "пара варіанта study ↔ electives");
        int[][] m = Metrics.matrix(qs, got);
        assertEquals(2, m[Variant.CATEGORIES.indexOf("study")][Variant.CATEGORIES.indexOf("electives")]);
        got.set(1, Labels.unparsed("обрізано"));
        assertEquals(1, Metrics.matrix(qs, got)[Variant.CATEGORIES.indexOf(qs.get(1).category())][7], "стовпець «не розібрано»");
    }

    @Test
    void noiseRuleNoConclusionWithinSpread() {
        Metrics.Noise n = Metrics.noise(15, 9, 11);
        assertEquals(2, n.spread());
        assertEquals(10.0, n.mean(), 1e-9);
        assertEquals(0, Metrics.compare(12, n.mean(), n.spread(), 15).sign(), "+2 при розкиді 2 — висновку немає");
        assertEquals(1, Metrics.compare(13, n.mean(), n.spread(), 15).sign());
        assertEquals(0, Metrics.compare(8, n.mean(), n.spread(), 15).sign());
        assertEquals(-1, Metrics.compare(7, n.mean(), n.spread(), 15).sign());
        Metrics.Noise same = Metrics.noise(15, 10, 10);
        assertEquals(0, same.rawSpread());
        assertEquals(1, same.spread(), "розкид не менше 1 звернення");
        assertEquals(0, Metrics.compare(11, same.mean(), same.spread(), 15).sign());
        assertEquals("6,7 п.п.", Metrics.pp(1, 15));
        String text = Metrics.compare(13, 10, 2, 15).text();
        assertTrue(text.startsWith("+3,0 зв. (+20,0 п.п.)") && text.endsWith("краще за шум"), text);
        assertEquals(2, Metrics.noise(5, 3, 1, 2).rawSpread());
    }

    @Test
    void hardestArePickedFromErrorsAndFilledWithTraps() {
        Variant v = Variant.load(3);
        List<Variant.Inquiry> qs = v.requests();
        List<Labels> z1 = perfect(qs), z2 = perfect(qs), few = perfect(qs);
        Labels wrong0 = new Labels(qs.get(0).distractor(), qs.get(0).urgency(), qs.get(0).needsHuman(), null);
        z1.set(0, wrong0);
        z2.set(0, wrong0);
        few.set(0, wrong0);                                                                                     // 01: 3 помилки
        z1.set(5, new Labels(qs.get(5).category(), "low", qs.get(5).needsHuman(), null));                         // 06: 1 помилка + s1 ≠ s2
        few.set(8, new Labels(qs.get(8).category(), qs.get(8).urgency(), !qs.get(8).needsHuman(), null));         // 09: 1 помилка
        List<Metrics.Pick> p = Metrics.pickHardest(qs, z1, z2, few, v.hardestFallback(), 5);
        assertEquals(5, p.size());
        List<String> ids = p.stream().map(x -> x.inquiry().shortId()).toList();
        assertTrue(ids.containsAll(List.of("01", "06", "09")), ids.toString());
        assertEquals(6, p.stream().filter(x -> x.inquiry().shortId().equals("01")).findFirst().orElseThrow().score());
        assertEquals(2, p.stream().filter(x -> x.reason().startsWith("добрано: пастка")).count(), "решту добрано пастками");
        assertEquals(ids.stream().sorted().toList(), ids, "у таблицях — за номером");
        // усе правильно → лише пастки
        List<Metrics.Pick> clean = Metrics.pickHardest(qs, perfect(qs), perfect(qs), perfect(qs), v.hardestFallback(), 5);
        assertTrue(clean.stream().allMatch(x -> x.inquiry().isTrap()));
    }

    @Test
    void majorityVoteAndAgreement() {
        Labels a = new Labels("study", "high", true, "x"), a2 = new Labels("study", "high", true, "інше міркування");
        Labels b = new Labels("access", "high", true, null), c = new Labels("study", "normal", true, null);
        Metrics.Vote unanimous = Metrics.vote(List.of(a, a2, a));
        assertEquals(3, unanimous.agree());
        Metrics.Vote twoOfThree = Metrics.vote(List.of(b, a, a2));
        assertEquals(2, twoOfThree.agree());
        assertEquals("study", twoOfThree.majority().category());
        Metrics.Vote split = Metrics.vote(List.of(c, b, a));
        assertEquals(1, split.agree());
        assertEquals("normal", split.majority().urgency(), "нічия → перша вибірка");
        assertEquals("1/3", split.agreementText());
    }

    @Test
    void thresholdRoutesLowAgreementToHumanAndCountsMissedHuman() {
        Variant.Inquiry q = new Variant.Inquiry("T-01", "x", "study", "high", true, null, null, "access", "", null);
        Labels ok = new Labels("study", "high", true, null), bad = new Labels("access", "high", true, null);
        Labels botAnswers = new Labels("study", "high", false, null); // помилка: людина потрібна, а бот відповів сам
        List<Metrics.Vote> votes = List.of(
                new Metrics.Vote(ok, 3, 3),          // одностайно і правильно
                new Metrics.Vote(ok, 2, 3),          // 2/3 і правильно
                new Metrics.Vote(botAnswers, 2, 3),  // 2/3, але пропущене needs_human=true
                new Metrics.Vote(bad, 1, 3));        // розбіжність і помилка
        List<Variant.Inquiry> qs = List.of(q, q, q, q);
        Metrics.Routing r2 = Metrics.route(votes, qs, 2);
        assertEquals(1, r2.toHuman());
        assertEquals(3, r2.auto());
        assertEquals(2, r2.autoCorrect());
        assertEquals(1, r2.caughtErrors());
        assertEquals(1, r2.missedErrors());
        assertEquals(1, r2.missedHuman());
        assertEquals("1/4 (25%)", r2.humanShareText());
        Metrics.Routing r3 = Metrics.route(votes, qs, 3);
        assertEquals(3, r3.toHuman());
        assertEquals(0, r3.missedHuman());
        assertEquals("1/1 (100%)", r3.autoAccuracyText());
        assertEquals(0, Metrics.route(votes, qs, 1).toHuman(), "поріг 1/3 — усе автоматично");
        assertEquals(1, Metrics.disagreements(List.of(ok, ok), List.of(ok, bad)));
    }

    // ------------------------------------------------------------------ ваша правка (крок 3)

    @Test
    void tweaksAreValidatedAgainstCopiesAndLabels() {
        Variant v = Variant.load(4);
        assertTrue(MyPromptTweaks.current().problems(v).isEmpty(), "заготовка MyPromptTweaks має бути валідною");
        assertTrue(Tweaks.NONE.isEmpty());
        Tweaks good = new Tweaks("Скарга на працівника гуртожитку — other.", List.of(Tweaks.example("Охоронець не пускає мене в гуртожиток після 23:00, хоча я попереджала.", "dorm", "high", true)));
        assertTrue(good.problems(v).isEmpty(), good.problems(v).toString());
        Tweaks copy = new Tweaks("", List.of(Tweaks.example(v.requests().get(1).text(), "dorm", "normal", true)));
        assertTrue(copy.problems(v).get(0).contains("занадто схожий на тестове звернення 02"), copy.problems(v).toString());
        Tweaks badLabels = new Tweaks("", List.of(Tweaks.example("Якийсь новий текст про пральню", "laundry", "urgent", false)));
        assertEquals(2, badLabels.problems(v).size());
        Tweaks tooMany = new Tweaks("", List.of(Tweaks.example("а один", "dorm", "low", false), Tweaks.example("б два", "dorm", "low", false),
                Tweaks.example("в три", "dorm", "low", false)));
        assertTrue(tooMany.problems(v).get(0).contains("не більше 2"));
        assertFalse(new Tweaks("x".repeat(Tweaks.MAX_RULE_CHARS + 1), List.of()).problems(v).isEmpty());
    }

    // ------------------------------------------------------------------ кеш

    @Test
    void cacheSurvivesRestartAndSeparatesSamples() throws Exception {
        Path dir = Files.createTempDirectory("lab3cache");
        Path file = dir.resolve("target").resolve("lab3-cache.json");
        LlmClient c = new GeminiClient(cfg(Config.Provider.GEMINI, "gemini-3.5-flash-lite", null, null), url());
        ObjectNode req = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("user", "звернення");
        String k1 = AnswerCache.key(c, req, "s1"), k2 = AnswerCache.key(c, req, "s2");
        assertNotEquals(k1, k2);
        assertNotEquals(k1, AnswerCache.key(c, req.deepCopy().put("user", "звернення!"), "s1"));
        AnswerCache cache = new AnswerCache(file, true);
        cache.put(k1, new AnswerCache.Entry("{\"category\":\"study\"}", 10, 5, 0, 123, "end_turn", "STOP"));
        assertTrue(Files.isRegularFile(file));
        AnswerCache reread = new AnswerCache(file, true);
        assertEquals(1, reread.size());
        assertEquals(123, reread.get(k1).orElseThrow().latencyMs());
        assertTrue(reread.get(k2).isEmpty());

        Path off = dir.resolve("off.json");
        AnswerCache noDisk = new AnswerCache(off, false);
        noDisk.put(k1, reread.get(k1).orElseThrow());
        assertTrue(noDisk.get(k1).isPresent(), "у пам'яті працює");
        assertFalse(Files.exists(off), "LAB_NO_CACHE=1 — файл не пишеться");
    }

    @Test
    void classifierAsksOnceThenServesFromCache() {
        LlmClient c = new GeminiClient(cfg(Config.Provider.GEMINI, "gemini-3.5-flash-lite", null, null), url());
        Classifier cls = new Classifier(c, new AnswerCache(Path.of("unused.json"), false));
        Variant.Inquiry q = Variant.load(4).requests().get(0);
        CallOptions off = CallOptions.of(CallOptions.Effort.MINIMAL);
        Classifier.Answer first = cls.ask(Lab3Prompts.SYSTEM, q, false, off, "s1");
        Classifier.Answer again = cls.ask(Lab3Prompts.SYSTEM, q, false, off, "s1");
        Classifier.Answer sample2 = cls.ask(Lab3Prompts.SYSTEM, q, false, off, "s2");
        assertTrue(first.labels().parsed());
        assertFalse(first.cached());
        assertTrue(again.cached());
        assertFalse(sample2.cached(), "інша мітка вибірки — новий запит");
        assertEquals(2, cls.newCalls());
        assertEquals(1, cls.cachedCalls());
    }

    // ------------------------------------------------------------------ параметри міркування

    @Test
    void geminiEffortParameterDependsOnModelGeneration() {
        GeminiClient g3 = new GeminiClient(cfg(Config.Provider.GEMINI, "gemini-3.5-flash-lite", null, null), url());
        assertEquals("minimal", g3.thinkingConfig(CallOptions.Effort.MINIMAL).path("thinkingLevel").asText());
        assertEquals("low", g3.thinkingConfig(CallOptions.Effort.LOW).path("thinkingLevel").asText());
        assertEquals("medium", g3.thinkingConfig(CallOptions.Effort.MEDIUM).path("thinkingLevel").asText());
        assertEquals("high", g3.thinkingConfig(CallOptions.Effort.HIGH).path("thinkingLevel").asText());
        assertNull(g3.thinkingConfig(CallOptions.Effort.DEFAULT)); // як у лабах 1–2: нічого не надсилаємо

        GeminiClient g25 = new GeminiClient(cfg(Config.Provider.GEMINI, "gemini-2.5-flash", null, null), url());
        assertEquals(0, g25.thinkingConfig(CallOptions.Effort.MINIMAL).path("thinkingBudget").asInt(-1));
        assertEquals(1024, g25.thinkingConfig(CallOptions.Effort.LOW).path("thinkingBudget").asInt());
        assertEquals(4096, g25.thinkingConfig(CallOptions.Effort.HIGH).path("thinkingBudget").asInt());
        assertTrue(g25.effortParameter(CallOptions.Effort.HIGH).contains("thinkingBudget"));

        GeminiClient forced = new GeminiClient(cfg(Config.Provider.GEMINI, "gemini-3.5-flash-lite", "low", 8192), url());
        assertEquals(8192, forced.thinkingConfig(CallOptions.Effort.HIGH).path("thinkingBudget").asInt(), "LAB_THINKING_BUDGET має пріоритет");
        GeminiClient level = new GeminiClient(cfg(Config.Provider.GEMINI, "gemini-3.5-flash-lite", "low", null), url());
        assertEquals("high", level.thinkingConfig(CallOptions.Effort.HIGH).path("thinkingLevel").asText(), "явний effort важливіший за LAB_THINKING_LEVEL");
        assertEquals("low", level.thinkingConfig(CallOptions.Effort.DEFAULT).path("thinkingLevel").asText());

        ObjectNode body = g3.buildJsonRequest("s", "u", Lab3Prompts.schema(false), 100, CallOptions.of(CallOptions.Effort.HIGH));
        assertFalse(body.path("generationConfig").has("temperature"), "у лабі 3 temperature не надсилається");
        assertTrue(body.path("generationConfig").has("thinkingConfig"));
        assertTrue(g3.effortParameter(CallOptions.Effort.HIGH).contains("thinkingLevel"));
        assertEquals(0.5, g3.buildJsonRequest("s", "u", Lab3Prompts.schema(false), 100, CallOptions.of(CallOptions.Effort.LOW).withTemperature(0.5))
                .path("generationConfig").path("temperature").asDouble(), "явна temperature все ще надсилається");
    }

    @Test
    void claude5UsesAdaptiveThinkingAndEffort() {
        FakeLlmServer.claudeGeneration = 5;
        try {
            ClaudeClient c = new ClaudeClient(cfg(Config.Provider.CLAUDE, "claude-sonnet-5-5", null, null), url());
            assertEquals(ClaudeClient.Mechanism.EFFORT, c.mechanism(), "починаємо з новішого механізму");
            ObjectNode min = c.buildJsonRequest("s", "u", Lab3Prompts.schema(false), 2048, CallOptions.of(CallOptions.Effort.MINIMAL));
            assertEquals("between_tools", min.path("thinking").path("type").asText(), "найнижче в 5.x — between_tools (disabled → 400)");
            assertEquals("low", min.path("output_config").path("effort").asText());
            assertEquals(2048, min.path("max_tokens").asInt());
            assertFalse(min.has("temperature"));
            ObjectNode low = c.buildJsonRequest("s", "u", Lab3Prompts.schema(false), 2048, CallOptions.of(CallOptions.Effort.LOW));
            assertEquals("adaptive", low.path("thinking").path("type").asText());
            assertEquals("low", low.path("output_config").path("effort").asText());
            assertEquals(2048 + 1024, low.path("max_tokens").asInt(), "max_tokens покриває і міркування, і текст");
            ObjectNode high = c.buildJsonRequest("s", "u", Lab3Prompts.schema(false), 2048, CallOptions.of(CallOptions.Effort.HIGH));
            assertEquals("high", high.path("output_config").path("effort").asText());
            assertEquals(2048 + 4096, high.path("max_tokens").asInt());
            assertTrue(high.path("output_config").path("format").has("schema"), "effort і схема — в одному output_config");
            assertFalse(high.path("thinking").has("budget_tokens"));
            // через імітацію: блок thinking (без тексту) не потрапляє в текст відповіді, механізм лишається EFFORT
            String err = captureErr(() -> {
                LlmResponse r = c.askJson(Lab3Prompts.SYSTEM, Lab3Prompts.user(Variant.load(5).requests().get(0).text()), Lab3Prompts.schema(false), 2048,
                        CallOptions.of(CallOptions.Effort.HIGH));
                assertTrue(Labels.parse(r.text()).parsed(), r.text());
                assertTrue(r.outputTokens() > 900, "токени міркування — всередині output_tokens: " + r.outputTokens());
            });
            assertEquals(ClaudeClient.Mechanism.EFFORT, c.mechanism());
            assertFalse(err.contains("effort"), err);
            assertFalse(c.reportsThinkingTokens());
        } finally {
            FakeLlmServer.claudeGeneration = null;
        }
    }

    @Test
    void claude4FallsBackToBudgetTokensOnFirst400AndRemembers() {
        FakeLlmServer.claudeGeneration = 4;
        try {
            ClaudeClient c = new ClaudeClient(cfg(Config.Provider.CLAUDE, "claude-haiku-4-5", null, null), url());
            String q = Lab3Prompts.user(Variant.load(2).requests().get(0).text());
            String err = captureErr(() -> {
                LlmResponse r = c.askJson(Lab3Prompts.SYSTEM, q, Lab3Prompts.schema(false), 2048, CallOptions.of(CallOptions.Effort.MINIMAL));
                assertTrue(Labels.parse(r.text()).parsed(), r.text());
                c.askJson(Lab3Prompts.SYSTEM, q, Lab3Prompts.schema(false), 8192, CallOptions.of(CallOptions.Effort.LOW));
                c.askJson(Lab3Prompts.SYSTEM, q, Lab3Prompts.schema(false), 8192, CallOptions.of(CallOptions.Effort.HIGH));
            });
            assertEquals(ClaudeClient.Mechanism.BUDGET, c.mechanism(), "перша 400 про effort → budget_tokens");
            assertEquals(1, err.split("не приймає effort", -1).length - 1, "повідомлення в консолі — один раз: " + err);
            ObjectNode min = c.buildJsonRequest("s", "u", Lab3Prompts.schema(false), 2048, CallOptions.of(CallOptions.Effort.MINIMAL));
            assertFalse(min.has("thinking"), "мінімальне в 4.x — без thinking");
            assertFalse(min.path("output_config").has("effort"));
            ObjectNode low = c.buildJsonRequest("s", "u", Lab3Prompts.schema(false), 2048, CallOptions.of(CallOptions.Effort.LOW));
            assertEquals("enabled", low.path("thinking").path("type").asText());
            assertEquals(1024, low.path("thinking").path("budget_tokens").asInt());
            assertEquals(2048 + 1024, low.path("max_tokens").asInt(), "бюджет міркування входить у max_tokens");
            ObjectNode high = c.buildJsonRequest("s", "u", Lab3Prompts.schema(false), 2048, CallOptions.of(CallOptions.Effort.HIGH));
            assertEquals(4096, high.path("thinking").path("budget_tokens").asInt());
            assertTrue(c.effortParameter(CallOptions.Effort.HIGH).contains("budget_tokens"));
            assertTrue(c.effortParameter(CallOptions.Effort.MINIMAL).contains("без міркування"));
        } finally {
            FakeLlmServer.claudeGeneration = null;
        }
    }

    @Test
    void mockGenerationFollowsModelNameUnlessForced() {
        assertEquals(4, FakeLlmServer.claudeGeneration("claude-haiku-4-5"));
        assertEquals(4, FakeLlmServer.claudeGeneration("claude-sonnet-4-5-20250929"));
        assertEquals(4, FakeLlmServer.claudeGeneration("claude-3-7-sonnet"));
        assertEquals(5, FakeLlmServer.claudeGeneration("claude-sonnet-5-5"));
        FakeLlmServer.claudeGeneration = 4;
        try {
            assertEquals(4, FakeLlmServer.claudeGeneration("claude-sonnet-5-5"));
        } finally {
            FakeLlmServer.claudeGeneration = null;
        }
    }

    @Test
    void mockReactsToEffortWithMoreThinkingAndLatency() {
        LlmClient c = new GeminiClient(cfg(Config.Provider.GEMINI, "gemini-3.5-flash-lite", null, null), url());
        Classifier cls = new Classifier(c, memory());
        int[] correct = new int[3];
        double[] thinking = new double[3], latency = new double[3];
        CallOptions.Effort[] levels = {CallOptions.Effort.MINIMAL, CallOptions.Effort.LOW, CallOptions.Effort.HIGH};
        for (int k = 0; k < 3; k++) {
            List<Classifier.Answer> as = new ArrayList<>();
            for (Variant v : Variant.loadAll())
                for (Variant.Inquiry q : v.requests()) as.add(cls.ask(Lab3Prompts.SYSTEM, q, CallOptions.of(levels[k]), "s1"));
            for (Classifier.Answer a : as) {
                correct[k] += a.correct() ? 1 : 0;
                thinking[k] += a.entry().thinkingTokens();
                latency[k] += a.entry().latencyMs();
            }
        }
        assertEquals(0.0, thinking[0], "мінімальне — 0 токенів міркування в імітації");
        assertTrue(thinking[1] > 0 && thinking[2] > 2 * thinking[1], "high міркує набагато більше за low: " + thinking[1] + " / " + thinking[2]);
        assertTrue(latency[2] > latency[0], "і відповідає повільніше");
        assertTrue(correct[2] > correct[0], "на всіх 120 зверненнях вищий effort в імітації в середньому точніший: " + correct[0] + " → " + correct[2]);
    }

    // ------------------------------------------------------------------ temperature: необов'язковий параметр

    @Test
    void temperatureFallbackForGeminiAndClaudeInMock() {
        FakeLlmServer.noTemperature = true;
        try {
            GeminiClient g = new GeminiClient(cfg(Config.Provider.GEMINI, "gemini-3.5-flash-lite", null, null), url());
            ClaudeClient c4 = new ClaudeClient(cfg(Config.Provider.CLAUDE, "claude-haiku-4-5", null, null), url());
            String err = captureErr(() -> {
                for (LlmClient c : new LlmClient[]{g, c4}) {
                    assertTrue(c.temperatureSupported());
                    assertFalse(c.ask(null, "Що таке токен?", 1.0, 300).text().isBlank(), c.provider());
                    assertFalse(c.temperatureSupported(), c.provider() + ": після 400 temperature більше не надсилається");
                    c.ask(null, "Що таке токен?", 0.0, 300);
                    LlmResponse r = c.askJson(Lab3Prompts.SYSTEM, Lab3Prompts.user(Variant.load(3).requests().get(0).text()), Lab3Prompts.schema(false), 2048,
                            CallOptions.of(CallOptions.Effort.MINIMAL).withTemperature(1.0));
                    assertTrue(Labels.parse(r.text()).parsed(), c.provider());
                }
            });
            assertEquals(2, err.split("не приймає temperature", -1).length - 1, "по одному повідомленню на клієнт: " + err);
            assertFalse(g.buildRequest(null, "hi", 0.5, 10).path("generationConfig").has("temperature"));
            assertFalse(c4.buildRequest(null, "hi", 0.5, 10).has("temperature"));
        } finally {
            FakeLlmServer.noTemperature = null;
        }
        // без прапорця імітація temperature приймає (еталон MOCK лаб 1–2 не змінюється)
        ClaudeClient fresh = new ClaudeClient(cfg(Config.Provider.CLAUDE, "claude-sonnet-5-5", null, null), url());
        fresh.ask(null, "hi", 0.0, 50);
        assertTrue(fresh.temperatureSupported());
        GeminiClient g = new GeminiClient(cfg(Config.Provider.GEMINI, "gemini-3.5-flash-lite", null, null), url());
        g.ask(null, "hi", 0.0, 50);
        assertTrue(g.temperatureSupported());
    }

    @Test
    void gainPriceCountsExtraOutputTokensPerExtraCorrect() {
        Metrics.GainPrice p = Metrics.gainPrice(1, 130, 3, 510);
        assertEquals(2, p.gain());
        assertEquals(380.0, p.extraOutTokens(), 1e-9);
        assertEquals(190.0, p.perCorrect(), 1e-9);
        assertTrue(p.text().contains("190") && p.text().contains("+1 правильне"), p.text());
        Metrics.GainPrice none = Metrics.gainPrice(2, 130, 2, 6000);
        assertFalse(none.hasGain());
        assertTrue(Double.isNaN(none.perCorrect()));
        assertTrue(none.text().startsWith("приросту немає") && none.text().contains("5870"), none.text());
        Metrics.GainPrice worse = Metrics.gainPrice(3, 130, 2, 130);
        assertEquals("приросту немає", worse.text());
        Metrics.GainPrice free = Metrics.gainPrice(1, 130, 2, 120);
        assertEquals(0.0, free.perCorrect(), 1e-9);
        assertTrue(free.text().contains("без помітних додаткових"), free.text());
        assertEquals("приросту немає (+302 токени без користі)", Metrics.gainPrice(2, 80, 2, 382).text());
        assertEquals("токен", Metrics.tokens(21));
        assertEquals("токенів", Metrics.tokens(11));
        assertEquals("токенів", Metrics.tokens(6613));
    }

    private static String captureErr(Runnable r) {
        PrintStream old = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            r.run();
        } finally {
            System.setErr(old);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Test
    void configReadsLab3Settings() throws Exception {
        Path none = Path.of("no-such-file.env");
        assertEquals(Integer.valueOf(2048), Config.load(none, Map.of("LAB_MOCK", "1", "LAB_THINKING_BUDGET", "2048")).thinkingBudget());
        assertNull(Config.load(none, Map.of("LAB_MOCK", "1")).thinkingBudget());
        Path env = Files.createTempFile("lab3", ".env");
        Files.writeString(env, "LAB_VARIANT=6\n# коментар\n", StandardCharsets.UTF_8);
        assertEquals("6", Config.setting("LAB_VARIANT", env, Map.of()));
        assertEquals("2", Config.setting("LAB_VARIANT", env, Map.of("LAB_VARIANT", "2")), "змінна середовища має пріоритет");
        assertNull(Config.setting("LAB_NO_CACHE", env, Map.of()));
    }

    // ------------------------------------------------------------------ повний прогін на імітації

    private static Lab3Run.Result quietRun(LlmClient client, int variant, AnswerCache cache, Tweaks tweaks, Path results) {
        PrintStream old = System.out;
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        try {
            return Lab3Run.run(client, Variant.load(variant), cache, tweaks, true, results);
        } finally {
            System.setOut(old);
        }
    }

    private static AnswerCache memory() {
        return new AnswerCache(Path.of("unused.json"), false);
    }

    @Test
    void mockRunFitsBudgetIsDeterministicAndDiffersBetweenVariants() {
        Set<String> fingerprints = new HashSet<>();
        int sumZero = 0, sumFew = 0, noisy = 0, dynamic = 0;
        String v3 = null;
        for (int n = 1; n <= Variant.COUNT; n++) {
            Lab3Run.Result r = quietRun(freshMockGemini(), n, memory(), Tweaks.NONE, null);
            assertEquals(4, r.all15().size(), "zero s1, zero s2, few-shot, ваша ітерація");
            assertEquals(6, r.hard5().size(), "база, +reasoning, effort low, effort high, голосування, EmotionPrompt");
            assertTrue(r.newCalls() <= 75, "бюджет прогону ≤ 75 запитів: " + r.newCalls());
            assertEquals(75, r.newCalls(), "30 + 15 + 0 (правки немає) + 15 + 10 + 5");
            assertNull(r.all15().get(3).score(), "без правки крок 3 не робить запитів");
            assertEquals(15, r.all15().get(0).score().n());
            assertEquals(3, r.thresholds().size());
            fingerprints.add(r.all15().get(0).score().correct() + "/" + r.all15().get(1).score().correct() + "/" + r.all15().get(2).score().correct()
                    + "/" + r.hard5().get(4).score().correct());
            sumZero += r.all15().get(0).score().correct() + r.all15().get(1).score().correct();
            sumFew += 2 * r.all15().get(2).score().correct();
            if (r.noise15().rawSpread() > 0) noisy++;
            if (!r.hardest().stream().map(p -> p.inquiry().id()).toList().equals(Variant.load(n).hardestFallback())) dynamic++;
            assertTrue(r.hard5().get(2).stats().thinking() > 0, "у effort low імітація повертає токени міркування");
            assertTrue(r.hard5().get(3).stats().thinking() > r.hard5().get(2).stats().thinking(), "effort high міркує більше за low");
            assertEquals(0.0, r.hard5().get(0).stats().thinking(), "у базовій лінії міркування мінімальне");
            assertEquals("—", r.hard5().get(0).price());
            for (int k = 1; k <= 4; k++) assertFalse(r.hard5().get(k).price().isEmpty(), "ціна приросту є в рядку " + k);
            if (n == 3) v3 = withoutLatency(r);
        }
        assertTrue(fingerprints.size() >= 6, "числа мають відрізнятися між варіантами: " + fingerprints);
        assertTrue(sumFew > sumZero, "few-shot в імітації в середньому допомагає: " + sumZero + " → " + sumFew);
        assertTrue(noisy >= 2, "розкид двох однакових zero-shot прогонів ненульовий хоча б у частині варіантів: " + noisy);
        assertTrue(dynamic >= 4, "найважчі обираються за результатами, а не з JSON: " + dynamic);
        // детермінованість: той самий варіант на свіжій імітації дає ті самі числа (крім затримки)
        assertEquals(v3, withoutLatency(quietRun(freshMockGemini(), 3, memory(), Tweaks.NONE, null)));
    }

    @Test
    void rerunAfterOwnTweakAsksOnlyChangedRequests() throws Exception {
        LlmClient c = freshMockGemini();
        AnswerCache cache = memory();
        Lab3Run.Result first = quietRun(c, 1, cache, Tweaks.NONE, null);
        assertEquals(75, first.newCalls());
        Tweaks rule = new Tweaks("Якщо студент питає про оцінку, залік чи перездачу з вибіркової дисципліни — це study, а не electives.", List.of());
        Lab3Run.Result second = quietRun(c, 1, cache, rule, null);
        assertEquals(15, second.newCalls(), "нові запити — лише крок 3");
        assertEquals(75, second.cachedCalls());
        assertNotNull(second.iteration());
        assertEquals(second.all15().get(2).score().correct() + 2, second.all15().get(3).score().correct(), "у варіанті 1 це правило допомагає (MOCK)");
        assertEquals(1, second.iteration().sign());
        Lab3Run.Result third = quietRun(c, 1, cache, rule, null);
        assertEquals(0, third.newCalls(), "повтор без змін — усе з кешу");
        // інша правка — інший результат; копія тестового звернення — відмова без запитів
        Tweaks example = new Tweaks("", List.of(Tweaks.example("Хочу вийти з вибіркової «Фотографія» і замість неї взяти «Етику ШІ».", "electives", "normal", true)));
        Lab3Run.Result fourth = quietRun(c, 1, cache, example, null);
        assertEquals(15, fourth.newCalls());
        Tweaks copy = new Tweaks("", List.of(Tweaks.example(Variant.load(1).requests().get(2).text(), "study", "low", false)));
        Lab3Run.Result fifth = quietRun(c, 1, cache, copy, null);
        assertEquals(0, fifth.newCalls());
        assertNull(fifth.iteration());
        // файл результатів для звіту
        Path md = Files.createTempDirectory("lab3md").resolve("target").resolve("lab3-results.md");
        quietRun(c, 1, cache, rule, md);
        String text = Files.readString(md, StandardCharsets.UTF_8);
        for (String must : List.of("Підсумок на всіх 15", "Підсумок на 5 найважчих", "| 01 |", "zero-shot, прогін s2", "ваша ітерація", "Поріг", "правило «",
                "Як читати", "Ціна приросту", "effort high", "Приклад:", "«люд»", "✗ zero s1", "згода 2/3", "в межах шуму", "з них needs_human=true",
                "temperature не надсилається"))
            assertTrue(text.contains(must), "у lab3-results.md немає: " + must);
    }

    @Test
    void ownTweakCanHelpOrHurtInMock() {
        Set<Integer> signs = new HashSet<>();
        Map<Integer, Tweaks> demo = Map.of(
                1, new Tweaks("Якщо студент питає про оцінку, залік чи перездачу з вибіркової дисципліни — це study, а не electives.", List.of()),
                5, new Tweaks("Якщо система працює, але бракує оцінки чи заліку — це study; якщо система не пускає чи видає помилку — access.", List.of()),
                8, new Tweaks("", List.of(Tweaks.example("Не можу зайти в кабінет студента, щоб замовити довідку: пише «сесія завершилась».", "access", "normal", true))));
        for (var e : demo.entrySet()) {
            LlmClient c = freshMockGemini();
            AnswerCache cache = memory();
            quietRun(c, e.getKey(), cache, Tweaks.NONE, null);
            Lab3Run.Result r = quietRun(c, e.getKey(), cache, e.getValue(), null);
            signs.add(r.iteration().sign());
        }
        assertEquals(Set.of(-1, 0, 1), signs, "власна правка в MOCK може допомогти, не змінити або зашкодити");
    }

    @Test
    void mockRunOnBothClaudeGenerationsPicksTheRightEffortMechanism() {
        for (int gen : new int[]{5, 4}) {
            FakeLlmServer.stop();
            FakeLlmServer.claudeGeneration = gen;
            try {
                String u = url();
                String model = gen == 5 ? "claude-sonnet-5-5" : "claude-haiku-4-5";
                ClaudeClient c = new ClaudeClient(new Config(Config.Provider.CLAUDE, "k", model, u, true, 0, null), u);
                String err = captureErr(() -> {
                    Lab3Run.Result r = quietRun(c, 1, memory(), Tweaks.NONE, null);
                    assertEquals(75, r.newCalls(), "gen " + gen + ": бюджет той самий (400-помилки не рахуються як запити лаби)");
                    assertNotNull(r.hard5().get(2).score(), "gen " + gen + ": effort low не пропущено");
                    assertNotNull(r.hard5().get(3).score(), "gen " + gen + ": effort high не пропущено");
                    assertTrue(r.hard5().get(3).stats().out() > r.hard5().get(0).stats().out(), "effort high дає більше вихідних токенів");
                    assertTrue(r.markdown().contains("(н/д)"), "Claude не повідомляє токени міркування окремо");
                });
                assertEquals(gen == 5 ? ClaudeClient.Mechanism.EFFORT : ClaudeClient.Mechanism.BUDGET, c.mechanism());
                assertEquals(gen == 5 ? 0 : 1, err.split("не приймає effort", -1).length - 1, err);
            } finally {
                FakeLlmServer.claudeGeneration = null;
            }
        }
    }

    private static LlmClient freshMockGemini() {
        FakeLlmServer.stop();
        String u = url();
        return new GeminiClient(new Config(Config.Provider.GEMINI, "k", "gemini-3.5-flash-lite", u, true, 0, null), u);
    }

    private static String withoutLatency(Lab3Run.Result r) {
        return (r.all15().toString() + r.hard5() + r.hardest()).replaceAll("latencyMs=[0-9.E]+", "").replaceAll("[0-9]+ / [0-9]+ \\([0-9]+\\)\\s*\\|\\s*[0-9]+", "");
    }
}
