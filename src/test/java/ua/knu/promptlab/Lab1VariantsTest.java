package ua.knu.promptlab;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ua.knu.promptlab.mock.FakeLlmServer;
import ua.knu.promptlab.steps.Lab1Variant;
import ua.knu.promptlab.steps.Step5NonDeterminism;
import ua.knu.promptlab.steps.Step6PromptVariants;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Варіанти лабораторної 1: дані, сумісність варіанта 1 зі старими текстами, checkFormat на різних вимогах, імітація. */
class Lab1VariantsTest {

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

    // ------------------------------------------------------------------ дані

    @Test
    void allEightVariantsLoad() {
        List<Lab1Variant> all = Lab1Variant.loadAll();
        assertEquals(8, all.size());
        for (int i = 0; i < 8; i++) {
            Lab1Variant v = all.get(i);
            assertEquals(i + 1, v.variant());
            for (String s : List.of(v.title(), v.city(), v.cityGenitive(), v.object(), v.step1().system(), v.step1().user(), v.step2().user(),
                    v.step3().system(), v.step3().user(), v.step4().en(), v.step4().ua(), v.step5().user(), v.step6().promptA(),
                    v.step6().task(), v.step6().example(), v.step6().formatText()))
                assertTrue(s != null && !s.isBlank(), "порожнє поле у варіанті " + v.variant());
            Lab1Variant.Format f = v.step6().format();
            assertTrue(f.bullets() >= 3 && f.bullets() <= 5, "пунктів " + f.bullets());
            assertTrue(f.maxWords() >= 10 && f.maxWords() <= 15, "слів " + f.maxWords());
            assertTrue(v.step6().task().contains(v.cityGenitive()), v.step6().task());
            assertTrue(v.step6().promptA().startsWith("Напиши про "), v.step6().promptA());
            if ("no_city".equals(f.extra())) assertTrue(v.city().toLowerCase().startsWith(f.cityStem()), f.cityStem());
        }
    }

    /** Варіант 1 = тексти лабораторної ДО появи варіантів, побайтово (частина групи вже здала — звіти мають лишитися порівнянними). */
    @Test
    void variantOneIsTheLegacyLabByteForByte() {
        Lab1Variant v = Lab1Variant.load(1);
        assertEquals("Ти асистент кафедри інформаційних технологій. Відповідай українською, не більше 3 речень.", v.step1().system());
        assertEquals("Поясни, що таке токен у великих мовних моделях.", v.step1().user());
        assertNull(v.step2().system());
        assertEquals("Поясни, що таке токен у великих мовних моделях. Коротко.", v.step2().user());
        assertEquals("Відповідай українською, не більше 3 речень.", v.step3().system());
        assertEquals("Чим відрізняється вхідний токен від вихідного з точки зору ціни?", v.step3().user());
        assertEquals("Large language models predict the next token. A token is not a word: it can be a word, "
                + "a part of a word, or even a single character. Prompts are priced by tokens, "
                + "so the same idea costs different amounts in different languages.", v.step4().en());
        assertEquals("Великі мовні моделі передбачають наступний токен. Токен — це не слово: це може бути слово, "
                + "частина слова або навіть окремий символ. Запити тарифікуються за токенами, "
                + "тому одна й та сама думка коштує по-різному різними мовами.", v.step4().ua());
        assertEquals("Придумай одну назву для кав'ярні біля університету. У відповіді — лише назва, без пояснень.", v.step5().user());
        assertEquals("Напиши про Львів", v.step6().promptA());
        assertEquals("A: «Напиши про Львів»", v.labelA());
        assertEquals("Напиши опис Львова для першокурсників з інших міст, які вперше приїжджають навчатися. "
                + "Формат: рівно 3 пункти маркованого списку (кожен починається з «- »), кожен пункт до 15 слів. "
                + "Без вступу і висновку.", v.step6().task());
        assertEquals("\n\nПриклад формату (для Києва):\n"
                + "- Столиця над Дніпром: центр політики, бізнесу та університетів країни.\n"
                + "- Метро, київська кава та парки допоможуть перевести подих між парами.\n"
                + "- Хрещатик і Андріївський узвіз — маршрути, з яких варто починати знайомство.", v.step6().example());
        assertEquals(Lab1Variant.LEGACY_FORMAT, v.step6().format());
    }

    @Test
    void textsAreUniqueAcrossVariants() {
        Set<String> seen = new HashSet<>();
        for (Lab1Variant v : Lab1Variant.loadAll()) {
            for (String s : List.of(v.step1().user(), v.step2().user(), v.step3().user(), v.step4().en(), v.step4().ua(),
                    v.step5().user(), v.step6().promptA(), v.step6().task(), v.city(), v.object()))
                assertTrue(seen.add(s), "повторюється у варіанті " + v.variant() + ": " + s);
        }
    }

    /** Абзаци кроку 4 — однакової довжини (±10 % до варіанта 1), інакше «× до EN» не порівняти між студентами. */
    @Test
    void step4ParagraphsHaveComparableLength() {
        int en1 = Lab1Variant.load(1).step4().en().length(), ua1 = Lab1Variant.load(1).step4().ua().length();
        for (Lab1Variant v : Lab1Variant.loadAll()) {
            int en = v.step4().en().length(), ua = v.step4().ua().length();
            assertTrue(Math.abs(en - en1) <= en1 * 0.10, "EN варіанта " + v.variant() + ": " + en + " vs " + en1);
            assertTrue(Math.abs(ua - ua1) <= ua1 * 0.10, "UA варіанта " + v.variant() + ": " + ua + " vs " + ua1);
        }
    }

    @Test
    void variantResolutionIsBackwardCompatible() {
        assertEquals(1, LabVariant.resolveCompat(null));
        assertEquals(1, LabVariant.resolveCompat("  "));
        assertEquals(4, LabVariant.resolveCompat(" 4 "));
        assertEquals(8, LabVariant.resolveCompat("8"));
        for (String bad : List.of("0", "9", "abc", "3.5")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> LabVariant.resolveCompat(bad));
            assertTrue(e.getMessage().contains("LAB_VARIANT=N"), e.getMessage());
        }
        assertTrue(LabVariant.MISSING_WARNING.contains("варіант 1") && !LabVariant.MISSING_WARNING.contains("\n"));
    }

    // ------------------------------------------------------------------ checkFormat

    @Test
    void legacyCheckFormatOutputIsUnchanged() {
        String ok = "- Один два три.\n- Чотири п'ять.\n- Шість сім вісім дев'ять.";
        assertEquals("PASS  (пунктів: 3, макс. слів у пункті: 4)", Step6PromptVariants.checkFormat(ok));
        assertEquals("FAIL  (пунктів: 2, макс. слів у пункті: 2)", Step6PromptVariants.checkFormat("- а б\n- в"));
        assertEquals(Step6PromptVariants.checkFormat(ok), Step6PromptVariants.checkFormat(ok, Lab1Variant.LEGACY_FORMAT));
    }

    /** Приклад формату з промпта C кожного варіанта мусить сам проходити перевірку свого варіанта. */
    @Test
    void eachVariantsOwnExamplePassesItsFormat() {
        for (Lab1Variant v : Lab1Variant.loadAll()) {
            String example = v.step6().example().substring(v.step6().example().indexOf(":\n") + 2);
            String verdict = Step6PromptVariants.checkFormat(example, v.step6().format());
            assertTrue(verdict.startsWith("PASS"), "варіант " + v.variant() + ": " + verdict);
        }
    }

    @Test
    void checkFormatHandlesDifferentRequirements() {
        Lab1Variant.Format four12 = Lab1Variant.load(2).step6().format();
        assertTrue(Step6PromptVariants.checkFormat("- а\n- б\n- в\n- г", four12).startsWith("PASS"));
        assertTrue(Step6PromptVariants.checkFormat("- а\n- б\n- в", four12).startsWith("FAIL"));
        assertTrue(Step6PromptVariants.checkFormat("- а\n- б\n- в\n- 1 2 3 4 5 6 7 8 9 10 11 12 13", four12).startsWith("FAIL"));

        Lab1Variant.Format numbered = Lab1Variant.load(7).step6().format();
        String five = "1. а\n2. б\n3. в\n4. г\n5) ґ";
        assertTrue(Step6PromptVariants.checkFormat(five, numbered).startsWith("PASS"), Step6PromptVariants.checkFormat(five, numbered));
        assertTrue(Step6PromptVariants.checkFormat(five.replaceAll("\\d[.)]", "-"), numbered).startsWith("FAIL"));

        Lab1Variant.Format five12 = Lab1Variant.load(4).step6().format();
        String twelve = "- 1 2 3 4 5 6 7 8 9 10 11 12";
        assertEquals("PASS  (пунктів: 5, макс. слів у пункті: 12)",
                Step6PromptVariants.checkFormat("- а\n- б\n• в\n* г\n" + twelve, five12));
        assertEquals("FAIL  (пунктів: 5, макс. слів у пункті: 13)",
                Step6PromptVariants.checkFormat("- а\n- б\n- в\n- г\n" + twelve + " 13", five12));
        assertEquals("FAIL  (пунктів: 6, макс. слів у пункті: 1)",
                Step6PromptVariants.checkFormat("- а\n- б\n- в\n- г\n- ґ\n- д", five12));
        // ліміт слів — параметр: той самий текст проходить для 15 слів і не проходить для 10
        assertTrue(Step6PromptVariants.checkFormat("- а\n- б\n" + twelve, new Lab1Variant.Format(3, 15, false, null, null, null)).startsWith("PASS"));
        assertTrue(Step6PromptVariants.checkFormat("- а\n- б\n" + twelve, new Lab1Variant.Format(3, 10, false, null, null, null)).startsWith("FAIL"));

        Lab1Variant.Format digit = Lab1Variant.load(5).step6().format();
        assertTrue(Step6PromptVariants.checkFormat("- 1 замок\n- 2 музеї\n- 1085 рік\n- 160 км", digit).startsWith("PASS"));
        assertTrue(Step6PromptVariants.checkFormat("- 1 замок\n- два музеї\n- 1085 рік\n- 160 км", digit).startsWith("FAIL"));

        Lab1Variant.Format noCity = Lab1Variant.load(6).step6().format();
        assertTrue(Step6PromptVariants.checkFormat("- Став поруч.\n- Замок і театр.\n- Багато студентів.", noCity).startsWith("PASS"));
        assertEquals("FAIL  (пунктів: 3, макс. слів у пункті: 3, без назви міста: 1 з 3)",
                Step6PromptVariants.checkFormat("- Тернопільський став.\n- Замок і театр.\n- Студенти Тернополя.", noCity));

        Lab1Variant.Format colon = Lab1Variant.load(8).step6().format();
        assertTrue(Step6PromptVariants.checkFormat("- Море: пляжі.\n- **Культура:** театр.\n- Їжа: кафе.\n- Навчання: університети.", colon).startsWith("PASS"));
        assertTrue(Step6PromptVariants.checkFormat("- Море і пляжі.\n- Культура: театр.\n- Їжа: кафе.\n- Навчання: університети.", colon).startsWith("FAIL"));
    }

    // ------------------------------------------------------------------ імітація

    @Test
    void mockAnswersEveryVariantOnBothProviders() {
        for (LlmClient c : new LlmClient[]{claude, gemini}) {
            Set<String> answersA = new HashSet<>();
            for (Lab1Variant v : Lab1Variant.loadAll()) {
                String a = c.ask(null, v.step6().promptA(), null, 2048).text();
                String b = c.ask("Ти — досвідчений гід-краєзнавець.", v.step6().task(), null, 2048).text();
                String cc = c.ask("Ти — досвідчений гід-краєзнавець.", v.step6().task() + v.step6().example(), null, 2048).text();
                assertTrue(Step6PromptVariants.checkFormat(a, v.step6().format()).startsWith("FAIL"), "A має провалитись: " + v.variant());
                assertTrue(Step6PromptVariants.checkFormat(cc, v.step6().format()).startsWith("PASS"), "C варіанта " + v.variant() + ": " + cc);
                assertFalse(b.isBlank());
                assertTrue(answersA.add(a), "однакова відповідь A у варіанті " + v.variant());
                String s1 = c.ask(v.step1().system(), v.step1().user(), null, 2048).text();
                if (v.variant() > 1) assertTrue(s1.contains(v.city()), s1);
                String cut = c.ask(null, v.step2().user(), null, 25).text();
                assertEquals("max_tokens", c.ask(null, v.step2().user(), null, 25).stopReason(), cut);
            }
        }
    }

    @Test
    void mockStep5AtZeroTemperatureVariesSlightlyForEveryVariant() {
        for (Lab1Variant v : Lab1Variant.loadAll()) {
            Set<String> norm = new HashSet<>(), raw = new HashSet<>();
            for (int i = 0; i < 10; i++) {
                String t = gemini.ask(null, v.step5().user(), 0.0, 2048).text();
                raw.add(t);
                norm.add(Step5NonDeterminism.normalize(t));
            }
            assertTrue(norm.size() >= 2 && norm.size() <= 3, v.variant() + ": " + norm);
            assertTrue(raw.size() > norm.size(), v.variant() + ": " + raw);
        }
    }

    /** Числа токенів (input) різні між варіантами — кожен студент має свої. */
    @Test
    void tokenCountsDifferBetweenVariants() {
        Set<Integer> ua = new HashSet<>();
        Set<Integer> step1 = new HashSet<>();
        for (Lab1Variant v : Lab1Variant.loadAll()) {
            ua.add(gemini.countTokens(null, v.step4().ua()));
            step1.add(gemini.ask(v.step1().system(), v.step1().user(), null, 2048).inputTokens());
        }
        assertTrue(ua.size() >= 5, ua.toString());
        assertTrue(step1.size() >= 5, step1.toString()); // запити кроку 1 різняться лише назвою міста — частина довжин збігається
    }
}
