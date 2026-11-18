package ua.knu.promptlab;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ua.knu.promptlab.lab2.JsonGate;
import ua.knu.promptlab.lab2.Lab2Variant;
import ua.knu.promptlab.lab2.Ticket;
import ua.knu.promptlab.lab2.TicketData;
import ua.knu.promptlab.lab2.TicketSchema;
import ua.knu.promptlab.lab2.TicketValidator;
import ua.knu.promptlab.mock.FakeLlmServer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Варіанти лабораторної 2: дані, сумісність варіанта 1 з {@link TicketData}, перевірюваність обов'язкових правил кодом
 * (еталонні реалізації — {@link #rule(Lab2Variant, Ticket)}; ті самі — у ключі сценарію викладача) та імітація на всіх варіантах.
 */
class Lab2VariantsTest {

    private static LlmClient claude;
    private static LlmClient gemini;

    @BeforeAll
    static void start() {
        String baseUrl = FakeLlmServer.startOnce().baseUrl();
        claude = new ClaudeClient(new Config(Config.Provider.CLAUDE, "k", Config.Provider.CLAUDE.defaultModel, baseUrl, true, 0, null), baseUrl);
        gemini = new GeminiClient(new Config(Config.Provider.GEMINI, "k", Config.Provider.GEMINI.defaultModel, baseUrl, true, 0, null), baseUrl);
    }

    @AfterAll
    static void stop() {
        FakeLlmServer.stop();
    }

    // ------------------------------------------------------------------ еталонні правила варіантів (ключ викладача)

    private static final Pattern LONG_NUMBER = Pattern.compile("\\+?\\d(?:[ ()\\-]*\\d){6,}");
    private static final Pattern INFINITIVE = Pattern.compile("(ти|ть|тися|тись)$");

    private static String firstWord(String s) {
        String w = s.strip().split("\\s+")[0].toLowerCase(Locale.ROOT);
        return w.replaceAll("[^\\p{L}'’-]", "");
    }

    /** Еталонна реалізація обов'язкового правила варіанта (так його очікуємо у validate() студента). */
    static Optional<String> rule(Lab2Variant v, Ticket t) {
        Lab2Variant.Rule r = v.rule();
        String s = t.summary() == null ? "" : t.summary();
        List<String> steps = t.steps() == null ? List.of() : t.steps();
        switch (r.code()) {
            case "summary_max_len":
                return s.length() > r.max() ? Optional.of("поле summary задовге (" + s.length() + " символів), потрібно до " + r.max()) : Optional.empty();
            case "summary_no_numbers":
                return LONG_NUMBER.matcher(s).find() ? Optional.of("summary містить номер картки або телефону — приберіть його") : Optional.empty();
            case "steps_infinitive":
                for (String st : steps)
                    if (!INFINITIVE.matcher(firstWord(st)).find())
                        return Optional.of("крок «" + st + "» не починається з дієслова в неозначеній формі (перевірити, зателефонувати…)");
                return Optional.empty();
            case "summary_mentions_product":
                return s.toLowerCase(Locale.ROOT).contains(v.product().toLowerCase(Locale.ROOT)) ? Optional.empty()
                        : Optional.of("summary не згадує назву продукту «" + v.product() + "»");
            case "steps_distinct_first_word": {
                Set<String> seen = new HashSet<>();
                for (String st : steps)
                    if (!seen.add(firstWord(st))) return Optional.of("два кроки починаються з того самого слова «" + firstWord(st) + "»");
                return Optional.empty();
            }
            case "p3_max_steps":
                return "p3".equals(t.priority()) && steps.size() > r.max()
                        ? Optional.of("для пріоритету p3 забагато кроків (" + steps.size() + "), дозволено не більше " + r.max()) : Optional.empty();
            case "summary_no_latin":
                return s.matches("(?s).*[A-Za-z].*") ? Optional.of("summary містить англійські слова — напишіть українською") : Optional.empty();
            default:
                throw new IllegalArgumentException("невідоме правило " + r.code());
        }
    }

    // ------------------------------------------------------------------ дані

    @Test
    void allEightVariantsLoadWithValidLabels() {
        List<Lab2Variant> all = Lab2Variant.loadAll();
        assertEquals(8, all.size());
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 8; i++) {
            Lab2Variant v = all.get(i);
            assertEquals(i + 1, v.variant());
            assertEquals(10, v.cases().size(), "варіант " + v.variant());
            for (int k = 0; k < 10; k++) {
                Lab2Variant.Item c = v.cases().get(k);
                assertEquals(k + 1, c.id());
                assertTrue(Ticket.CATEGORIES.contains(c.category()), c.category());
                assertTrue(Ticket.PRIORITIES.contains(c.priority()), c.priority());
                assertFalse(c.message().isBlank());
                assertFalse(c.message().contains("\"\"\""), "потрійні лапки зламають розмітку запиту");
                if (c.category().equals("feature_request")) assertEquals("p3", c.priority(), "побажання — p3: " + c.message());
            }
            Set<String> cats = new HashSet<>();
            v.cases().forEach(c -> cats.add(c.category()));
            assertTrue(cats.size() >= 4, "варіант " + v.variant() + " має мало категорій: " + cats);
            long disputed = v.cases().stream().filter(c -> c.note().startsWith("Спірний")).count();
            assertTrue(disputed >= 1 && disputed <= 2, "спірних кейсів у варіанті " + v.variant() + ": " + disputed);
            assertTrue(v.cases().stream().anyMatch(c -> c.priority().equals("p0")), "немає p0 у варіанті " + v.variant());
            assertFalse(v.rule().text().isBlank());
            assertFalse(v.rule().reason().isBlank());
            assertFalse(v.product().isBlank());
            codes.add(v.rule().code() + (v.rule().max() == null ? "" : v.rule().max()));
        }
        assertEquals(8, codes.size(), "правила мають бути різні: " + codes);
    }

    /** Варіант 1 = TicketData побайтово (лабораторна до появи варіантів; без LAB_VARIANT береться саме він). */
    @Test
    void variantOneIsTicketDataByteForByte() {
        Lab2Variant v = Lab2Variant.load(1);
        assertEquals(TicketData.SYSTEM, v.system());
        assertEquals(TicketData.CASES, v.ticketCases());
        assertEquals("summary_max_len", v.rule().code());
        assertEquals(80, (int) v.rule().max());
    }

    @Test
    void messagesAreUniqueAndSystemPromptsKeepTheSameDefinitions() {
        Set<String> seen = new HashSet<>();
        String defs = "p1 — заблокований, але без жорсткого часу; p2 — працює з незручностями; p3 — побажання або питання. Відповідай українською.";
        for (Lab2Variant v : Lab2Variant.loadAll()) {
            for (Lab2Variant.Item c : v.cases()) assertTrue(seen.add(c.message()), "повтор: " + c.message());
            assertTrue(v.system().endsWith(defs), v.system());
            assertTrue(v.system().contains("p0 — клієнт заблокований і час критичний (згадано "), v.system());
            if (v.variant() > 1) assertTrue(v.system().contains("«" + v.product() + "»"), v.system());
        }
    }

    // ------------------------------------------------------------------ правила варіантів

    @Test
    void everyVariantRuleIsCheckableByCode() {
        for (Lab2Variant v : Lab2Variant.loadAll()) {
            Ticket good = new Ticket("bug", "p3", "calm", v.product() + ": коротко про проблему",
                    List.of("відтворити проблему", "перевірити логи", "відповісти клієнту"));
            assertTrue(rule(v, good).isEmpty(), "добрий тікет не має порушувати правило варіанта " + v.variant() + ": " + rule(v, good));
            assertTrue(TicketValidator.validate(good).isEmpty());
            Ticket bad = switch (v.rule().code()) {
                case "summary_max_len" -> withSummary(good, "x".repeat(v.rule().max() + 1));
                case "summary_no_numbers" -> withSummary(good, "Клієнт, картка 5168 7420 1234 5678");
                case "steps_infinitive" -> withSteps(good, List.of("Перевірте лінію", "відповісти клієнту"));
                case "summary_mentions_product" -> withSummary(good, "коротко про проблему");
                case "steps_distinct_first_word" -> withSteps(good, List.of("Перевірити баланс", "перевірити налаштування"));
                case "p3_max_steps" -> withSteps(good, List.of("а", "б", "в", "г"));
                case "summary_no_latin" -> withSummary(good, "Не працює Moodle");
                default -> throw new IllegalStateException(v.rule().code());
            };
            assertTrue(rule(v, bad).isPresent(), "поганий тікет мав порушити правило варіанта " + v.variant());
        }
        // правило варіанта 7 стосується лише p3
        Lab2Variant v7 = Lab2Variant.load(7);
        assertTrue(rule(v7, new Ticket("bug", "p2", "calm", "x", List.of("а", "б", "в", "г", "ґ"))).isEmpty());
        // номер телефону з пробілами ловиться, суми й час — ні
        Lab2Variant v2 = Lab2Variant.load(2);
        assertTrue(rule(v2, new Ticket("login", "p1", "calm", "SMS не приходить на +380 67 123 45 67", List.of("а"))).isPresent());
        assertTrue(rule(v2, new Ticket("billing", "p2", "calm", "Списали 1800 гривень двічі о 12:00", List.of("а"))).isEmpty());
    }

    private static Ticket withSummary(Ticket t, String s) {
        return new Ticket(t.category(), t.priority(), t.sentiment(), s, t.steps());
    }

    private static Ticket withSteps(Ticket t, List<String> steps) {
        return new Ticket(t.category(), t.priority(), t.sentiment(), t.summary(), steps);
    }

    // ------------------------------------------------------------------ імітація

    private static String user(String message) {
        return "Повідомлення клієнта:\n\"\"\"" + message + "\"\"\"";
    }

    /** Імітація: перша спроба частини кейсів порушує правило варіанта, повтор із причиною — виправляє. */
    @Test
    void mockViolatesVariantRuleOnFirstTryAndFixesOnRetry() {
        for (Lab2Variant v : Lab2Variant.loadAll()) {
            if (v.variant() == 1) continue; // варіант 1 — стара імітація без змін (ClientsTest)
            for (LlmClient c : new LlmClient[]{claude, gemini}) {
                int violations = 0;
                for (Lab2Variant.Item it : v.cases()) {
                    LlmResponse first = c.askJson(v.system(), user(it.message()), TicketSchema.json(), 2048);
                    TicketValidator.Result p = TicketValidator.parseExact(first.text());
                    assertTrue(p.ok(), first.text());
                    Ticket t = TicketValidator.normalize(p.ticket()).ticket();
                    if (TicketValidator.validate(t).isPresent()) continue; // ці кейси ловлять базові правила 1–6 (summary > 200, p0 для побажання)
                    if (rule(v, t).isPresent()) {
                        violations++;
                        String again = user(it.message()) + "\n\nПопередня відповідь відхилена: " + rule(v, t).get() + ". Виправ і поверни тікет знову.";
                        Ticket fixed = TicketValidator.parseExact(c.askJson(v.system(), again, TicketSchema.json(), 2048).text()).ticket();
                        assertTrue(rule(v, fixed).isEmpty(), "варіант " + v.variant() + ": повтор не виправив " + fixed);
                    }
                }
                assertTrue(violations >= 1 && violations <= 8, "варіант " + v.variant() + ": порушень правила " + violations);
            }
        }
    }

    @Test
    void mockGateGivesMeaningfulAndDifferentNumbersPerVariant() {
        Set<String> fingerprints = new HashSet<>();
        for (Lab2Variant v : Lab2Variant.loadAll()) {
            for (LlmClient c : new LlmClient[]{claude, gemini}) {
                JsonGate gate = new JsonGate(c, 3, 2048);
                int first = 0, fixed = 0, failed = 0, pass = 0, inTokens = 0;
                List<JsonGate.Outcome> out = new ArrayList<>();
                for (TicketData.Case k : v.ticketCases()) {
                    JsonGate.Outcome o = gate.run(v.system(), k.message());
                    out.add(o);
                    assertFalse(o.truncated());
                    inTokens += o.inputTokens();
                    if (o.ok() && o.attempts() == 1) first++;
                    else if (o.ok()) fixed++;
                    else failed++;
                    if (o.ok() && o.ticket().priority().equals(k.expectedPriority()) && o.ticket().category().equals(k.expectedCategory())) pass++;
                }
                assertTrue(first >= 5 && first <= 8, "варіант " + v.variant() + ": p = " + first);
                assertTrue(fixed >= 1, "варіант " + v.variant() + ": v має бути визначене");
                assertEquals(1, failed, "варіант " + v.variant() + ": один кейс провалює всі повтори");
                assertTrue(pass >= 4, "варіант " + v.variant() + ": eval " + pass);
                if (c == gemini) fingerprints.add(first + "/" + fixed + "/" + pass + "/" + inTokens);
            }
        }
        assertEquals(8, fingerprints.size(), "числа мають відрізнятися між варіантами: " + fingerprints);
    }

    @Test
    void mockPromptOnlyStepWorksForEveryVariant() {
        for (Lab2Variant v : Lab2Variant.loadAll()) {
            String sys = v.system() + "\n\n" + TicketSchema.asPromptInstructions();
            int parsed = 0;
            for (int i = 0; i < 5; i++) {
                String text = gemini.ask(sys, user(v.cases().get(i).message()), null, 2048).text();
                if (TicketValidator.parse(text).ok()) parsed++;
            }
            assertTrue(parsed >= 1, "варіант " + v.variant());
        }
    }
}
