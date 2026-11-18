package ua.knu.promptlab.lab3;

import ua.knu.promptlab.ApiException;
import ua.knu.promptlab.CallOptions;
import ua.knu.promptlab.ClaudeClient;
import ua.knu.promptlab.Config;
import ua.knu.promptlab.Console;
import ua.knu.promptlab.LlmClient;
import ua.knu.promptlab.Pricing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Лабораторна 3. «Від одного вдалого прикладу до виміряної точності»: які прийоми справді підвищують точність і чи більший
 * їхній ефект за шум. Задача — класифікація звернень студентів до бота підтримки факультету (category / urgency / needs_human).
 *
 * <pre>
 *  Крок 1. Zero-shot ДВІЧІ на 15 зверненнях (s1, s2): точність і шум двох однакових прогонів.          30 запитів
 *  Крок 2. Few-shot, k = 3 приклади, на тих самих 15; ефект проти шуму; виправив / зіпсував.           15 запитів
 *  Крок 3. Ваша ітерація: few-shot + ваші приклади чи правило з MyPromptTweaks (0, поки нічого не змінено). 15 запитів
 *  Крок 4. Міркування: дешево чи дорого — на 5 найважчих (обрано за помилками кроків 1–2):
 *          + поле reasoning (дешево), effort low, effort high (дорого).                                      15 запитів
 *  Крок 5. Голосування трьох відповідей (s1 — з кроку 2) і поріг «авто / людина»: усі три пороги.      10 запитів
 *  Крок 6. Бонус: перевірка EmotionPrompt (фраза про кар'єру) проти шуму на тих самих 5.                5 запитів
 * </pre>
 * Перший прогін — 75 запитів (без бонусу 70); після вашої правки в кроці 3 повторний прогін робить лише 15 нових запитів,
 * решта — з кешу target/lab3-cache.json. Результати — у target/lab3-results.md (його додають до звіту).
 *
 * <p>temperature у лабі 3 НЕ надсилається: значення моделі за замовчуванням (новіші моделі її відхиляють, а Google радить
 * для Gemini 3 лишати 1.0). Розкид між однаковими запитами — це і є шум, який ми вимірюємо.
 *
 * <p>Налаштування (.env): LAB_VARIANT=1…8 (обов'язково), LAB_DELAY_MS=4000 (безкоштовний рівень Gemini),
 * LAB_NO_CACHE=1 (без кешу на диску), LAB_SKIP_BONUS=1 (без кроку 6), LAB_THINKING_BUDGET (бюджет effort high для Gemini 2.x
 * і Claude 4.x). Без ключа — LAB_MOCK=1.
 */
public final class Lab3Run {

    static final int SAMPLES = 3;
    public static final int HARD_N = 5;

    /**
     * Рядок підсумкової таблиці. effect — текст «ефект і висновок» (або розкид для базових рядків);
     * price — «ціна приросту» (лише в таблиці «на 5 найважчих»; "" — не рахується).
     */
    public record Row(String technique, Metrics.Score score, Stats stats, String effect, String price, String note) {
        public Row(String technique, Metrics.Score score, Stats stats, String effect, String note) {
            this(technique, score, stats, effect, "", note);
        }
    }

    /** Середні лічильники на одне звернення. thinkingKnown = false — провайдер не повідомляє токени міркування окремо (Claude). */
    public record Stats(double in, double out, double thinking, double latencyMs, boolean thinkingKnown) {
        String tokens() {
            return String.format(Locale.ROOT, "%.0f / %.0f (%s)", in, out, thinkingText());
        }

        String thinkingText() {
            return thinkingKnown ? String.format(Locale.ROOT, "%.0f", thinking) : "н/д";
        }

        String latency() {
            return String.format(Locale.ROOT, "%.0f", latencyMs);
        }
    }

    /**
     * Підсумок прогону (для тестів і документів).
     *
     * @param all15      рядки таблиці «на всіх 15»: zero s1, zero s2, few-shot, ваша ітерація
     * @param hard5      рядки таблиці «на 5 найважчих»: база, +reasoning, effort low, effort high, голосування, EmotionPrompt
     * @param hardest    обрані 5 найважчих і чому
     * @param iteration  висновок кроку 3 (null — ітерації не було)
     * @param noise15    шум zero-shot на 15
     * @param disagree15 у скількох зверненнях zero s1 і s2 дали різні відповіді
     * @param noise5     точність трьох однакових few-shot серій s1, s2, s3 на 5 найважчих
     */
    public record Result(List<Row> all15, List<Row> hard5, List<Metrics.Pick> hardest, Metrics.Verdict fewVerdict,
                         Metrics.Verdict iteration, Metrics.Noise noise15, int disagree15, Metrics.Noise noise5, List<Metrics.Routing> thresholds,
                         int newCalls, int cachedCalls, String markdown) {
    }

    private Lab3Run() {
    }

    public static void main(String[] args) {
        Console.init();
        boolean mock = Config.isTrueSetting("LAB_MOCK");
        String rawVariant = Config.setting("LAB_VARIANT");
        Variant variant;
        try {
            variant = Variant.load(Variant.resolve(rawVariant, mock));
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println(e.getMessage()); // підказка для студента замість стек-трейсу
            System.exit(1);
            return;
        }
        int exit = 0;
        try (LlmClient client = LlmClient.create()) {
            if (mock && (rawVariant == null || rawVariant.isBlank())) {
                System.out.println("  (LAB_VARIANT не задано — у режимі MOCK беремо варіант 1; для справжнього прогону задайте свій у .env)");
            }
            // MOCK пише в окремі файли, щоб імітовані відповіді не змішувались зі справжніми, які здають разом зі звітом
            AnswerCache cache = AnswerCache.fromSettings(Config.isTrueSetting("LAB_NO_CACHE"), client.isMock());
            Path results = Path.of("target", client.isMock() ? "lab3-results-mock.md" : "lab3-results.md");
            run(client, variant, cache, MyPromptTweaks.current(), !Config.isTrueSetting("LAB_SKIP_BONUS"), results);
        } catch (ApiException e) {
            System.out.println("\n  !!! Запит до API завершився помилкою: " + e.getMessage());
            System.out.println("  Усі вже отримані відповіді збережено в target/lab3-cache.json. Зачекайте хвилину й запустіть ще раз —");
            System.out.println("  прогін продовжиться з того самого місця, а готові відповіді не витрачатимуть квоту.");
            System.out.println("  Часті 429 → збільште LAB_DELAY_MS у .env (наприклад, до 6000).");
            exit = 1;
        }
        if (exit != 0) System.exit(exit);
    }

    /** Увесь прогін. */
    public static Result run(LlmClient client, Variant v, AnswerCache cache, Tweaks tweaks, boolean bonus, Path resultsFile) {
        Classifier cls = new Classifier(client, cache);
        List<Variant.Inquiry> all = v.requests();
        int n = all.size();
        boolean thinkKnown = client.reportsThinkingTokens();
        // temperature = null: не надсилаємо, значення моделі за замовчуванням
        CallOptions base = CallOptions.of(CallOptions.Effort.MINIMAL);
        CallOptions low = CallOptions.of(CallOptions.Effort.LOW);
        CallOptions high = CallOptions.of(CallOptions.Effort.HIGH);
        List<Row> rows15 = new ArrayList<>(), rows5 = new ArrayList<>();
        Md md = new Md(client, v);

        Console.banner("Лабораторна 3. Від одного вдалого прикладу до виміряної точності (" + client.provider() + ", " + client.model() + ")");
        Console.kv("варіант", v.variant() + " з " + Variant.COUNT + ": " + v.title());
        Console.kv("пару часто плутають", String.join(" ↔ ", v.pair()) + " — " + v.pairNote());
        Console.kv("звернень", n + " (пасток: " + all.stream().filter(Variant.Inquiry::isTrap).count() + ")");
        Console.kv("кеш відповідей", cache.diskEnabled()
                ? cache.file() + " (уже є відповідей: " + cache.size() + ")" + (cache.hadLoadWarning() ? " — файл не прочитався, починаємо з нуля" : "")
                : "вимкнено (LAB_NO_CACHE=1) — кожен прогін витрачає запити");
        Console.kv("пауза між запитами", client.requestDelayMs() + " мс (LAB_DELAY_MS; лише перед новими запитами, не з кешу)");
        Console.kv("temperature", "не надсилається в жодному кроці — значення моделі за замовчуванням");
        System.out.println("  (новіші моделі відповідають помилкою на будь-яку temperature, а для Gemini 3 Google радить лишати 1.0;");
        System.out.println("  тож розкид між однаковими запитами — це природний шум моделі, його ми й вимірюємо)");
        System.out.println("  Правильна відповідь = збіглися ВСІ ТРИ мітки (категорія / терміновість / хто відповідає). Для кількох спірних");
        System.out.println("  звернень допустимі дві відповіді — їх показано через «|» (наприклад, normal|low).");
        System.out.println("  Позначення: «люд» — потрібна людина (needs_human=true), «бот» — бот відповідає сам; «ok» — усі три мітки збіглися;");
        System.out.println("  «зв.» — звернення; «п.п.» — відсоткові пункти. Повна легенда — на початку target/lab3-results.md.");
        System.out.println("  Прогноз (розділ 1 звіту) записано ДО цього запуску? Якщо ні — запишіть, поки не дивилися на числа нижче.");

        // ---------------------------------------------------------------- крок 1
        Console.section("Крок 1. Zero-shot двічі на " + n + " зверненнях (s1, s2): точність і шум однакових прогонів");
        int c0 = cls.newCalls(), k0 = cls.cachedCalls();
        List<Classifier.Answer> z1 = new ArrayList<>(), z2 = new ArrayList<>();
        for (Variant.Inquiry q : all) z1.add(cls.ask(Lab3Prompts.SYSTEM, q, base, "s1"));
        for (Variant.Inquiry q : all) z2.add(cls.ask(Lab3Prompts.SYSTEM, q, base, "s2"));
        System.out.println("  Мінімальне міркування в кроках 1–3 і в базі кроку 4 (реально надіслано): " + client.effortParameter(CallOptions.Effort.MINIMAL));
        System.out.println("  (мінімальне — не обов'язково нуль: дивіться токени thinking у рядку «на звернення»)");
        System.out.println("  s1 і s2 — два ОДНАКОВІ прогони; «≠» — відповіді на це звернення розійшлися.");
        System.out.printf("  %-4s %-22s %-22s %-24s %-24s %s%n", "№", "пастка", "очікувано", "s1", "s2", "");
        for (int i = 0; i < n; i++) {
            Variant.Inquiry q = all.get(i);
            System.out.printf("  %-4s %-22s %-22s %-24s %-24s %s%n", q.shortId(), trapName(q), expectedText(q), cell(z1.get(i)), cell(z2.get(i)),
                    z1.get(i).labels().key().equals(z2.get(i).labels().key()) ? "" : "≠");
        }
        Metrics.Score s1 = score(z1), s2 = score(z2);
        Console.kv("точність s1 / s2", s1.accuracyText() + "  /  " + s2.accuracyText());
        Console.kv("на пастках s1 / s2", s1.trapsText() + "  /  " + s2.trapsText());
        Console.kv("по мітках (s1)", "category " + s1.categoryOk() + "/" + n + ", urgency " + s1.urgencyOk() + "/" + n + ", needs_human " + s1.humanOk() + "/" + n);
        Metrics.Noise noise = Metrics.noise(n, s1.correct(), s2.correct());
        int disagree = Metrics.disagreements(labels(z1), labels(z2));
        Console.kv("розкид двох прогонів", "|" + s1.correct() + " − " + s2.correct() + "| = " + noise.rawSpread() + " зв. (" + Metrics.pp(noise.rawSpread(), n)
                + "); відповіді розійшлися в " + disagree + " зверненнях з " + n);
        System.out.println("  ПРАВИЛО ДЛЯ ВИСНОВКІВ: ефект техніки = її точність − база (тут — середнє s1 і s2 = " + fmt(noise.mean()) + " зв.).");
        System.out.println("  Якщо |ефект| не більший за розкид двох однакових прогонів (тут " + noise.spread() + " зв.; беремо не менше 1) — висновку немає.");
        System.out.println("  На " + n + " зверненнях одне звернення = " + Metrics.pp(1, n));
        List<Labels> both = new ArrayList<>(labels(z1));
        both.addAll(labels(z2));
        List<Variant.Inquiry> twice = new ArrayList<>(all);
        twice.addAll(all);
        printConfusions(twice, both, "s1 і s2 разом, " + 2 * n + " відповідей");
        Console.kv("помилок на парі варіанта", Metrics.pairErrors(twice, both, v.pair()) + " з " + 2 * n + " відповідей (" + String.join(" ↔ ", v.pair())
                + ") — порівняйте з вашим прогнозом");
        Stats st1 = stats(z1, thinkKnown), st2 = stats(z2, thinkKnown);
        Console.kv("на звернення", "токенів in / out (з них thinking): " + st1.tokens() + "; затримка ≈ " + st1.latency() + " мс");
        calls(cls, c0, k0);
        rows15.add(new Row("zero-shot, прогін s1", s1, st1, "база", ""));
        rows15.add(new Row("zero-shot, прогін s2", s2, st2, "розкид s1/s2: " + noise.rawSpread() + " зв.; різних відповідей " + disagree, ""));

        // ---------------------------------------------------------------- крок 2
        List<Variant.Example> ex = v.fewShot();
        String fewSystem = Lab3Prompts.system(ex);
        Console.section("Крок 2. Few-shot: k = " + ex.size() + " приклади на тих самих " + n + " зверненнях");
        System.out.println("  Приклади дописано в кінець системного промпта; жоден не схожий на тестові звернення:");
        for (Variant.Example e : ex) {
            System.out.printf("    %s  %-28s «%s» → %s%n", e.id().substring(e.id().indexOf('-') + 1),
                    e.pair() == null ? "[правило]" : "[межа " + String.join(" ↔ ", e.pair()) + "]", Console.clip(e.text(), 60), compact(e.labels()));
        }
        c0 = cls.newCalls();
        k0 = cls.cachedCalls();
        List<Classifier.Answer> few = new ArrayList<>();
        for (Variant.Inquiry q : all) few.add(cls.ask(fewSystem, q, base, "s1"));
        System.out.println("  «виправив» — обидва zero-shot помилились, few-shot правильно; «зіпсував» — навпаки.");
        System.out.printf("  %-4s %-22s %-24s %-24s %s%n", "№", "очікувано", "zero-shot s1 / s2", "few-shot", "");
        List<String> fixed = new ArrayList<>(), broken = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Variant.Inquiry q = all.get(i);
            boolean zOk1 = z1.get(i).correct(), zOk2 = z2.get(i).correct(), fOk = few.get(i).correct();
            String mark = "";
            if (!zOk1 && !zOk2 && fOk) {
                fixed.add(q.shortId());
                mark = "виправив";
            } else if (zOk1 && zOk2 && !fOk) {
                broken.add(q.shortId());
                mark = "зіпсував";
            }
            System.out.printf("  %-4s %-22s %-24s %-24s %s%n", q.shortId(), expectedText(q), (zOk1 ? "ok" : "✗") + " / " + (zOk2 ? "ok" : "✗"), cell(few.get(i)), mark);
        }
        Metrics.Score sf = score(few);
        Metrics.Verdict fv = Metrics.compare(sf.correct(), noise.mean(), noise.spread(), n);
        Console.kv("точність few-shot", sf.accuracyText() + ";  на пастках " + sf.trapsText());
        Console.kv("ефект проти zero-shot", fv.text());
        Console.kv("виправив few-shot", (fixed.isEmpty() ? "нічого" : String.join(", ", fixed)) + "  (обидва zero-shot ✗ → few-shot ok)");
        Console.kv("зіпсував few-shot", (broken.isEmpty() ? "нічого" : String.join(", ", broken)) + "  (обидва zero-shot ok → few-shot ✗)");
        printConfusions(all, labels(few), "few-shot");
        Stats stf = stats(few, thinkKnown);
        Console.kv("на звернення", "токенів in / out: " + stf.tokens() + "; затримка ≈ " + stf.latency() + " мс");
        double extraIn = stf.in() - st1.in();
        Console.kv("ціна прикладів", String.format(Locale.ROOT, "+%.0f вхідних токенів на КОЖЕН запит (%+.0f%%)", extraIn, st1.in() == 0 ? 0 : 100 * extraIn / st1.in()));
        Console.kv("на 1000 звернень/день", pricePerDay(client, extraIn));
        calls(cls, c0, k0);
        rows15.add(new Row("few-shot, k = " + ex.size(), sf, stf, fv.text() + " (проти zero-shot)", ""));

        // ---------------------------------------------------------------- крок 3
        Console.section("Крок 3. Ваша ітерація промпта (src/main/java/ua/knu/promptlab/lab3/MyPromptTweaks.java)");
        Metrics.Verdict iv = null;
        List<Classifier.Answer> mine = null;
        List<String> problems = tweaks.problems(v);
        if (tweaks.isEmpty() || !problems.isEmpty()) {
            if (!problems.isEmpty()) {
                System.out.println("  Правку НЕ застосовано — виправте MyPromptTweaks:");
                problems.forEach(pr -> System.out.println("    ! " + pr));
            } else {
                System.out.println("  Ви ще нічого не змінили (MyPromptTweaks порожній) — нових запитів 0.");
            }
            System.out.println("  Помилки few-shot, з яких варто почати (очікувано → отримано):");
            for (int i = 0; i < n; i++) {
                if (few.get(i).correct()) continue;
                Variant.Inquiry q = all.get(i);
                System.out.printf("    %s  %s → %s  [%s]  «%s»%n", q.shortId(), expectedText(q), compact(few.get(i).labels()),
                        Metrics.wrongFields(q, few.get(i).labels()), Console.clip(q.text(), 80));
            }
            System.out.println("  Що зробити: оберіть ОДНУ причину помилок і допишіть у MyPromptTweaks 1–2 СВОЇ приклади на цю межу або одне правило;");
            System.out.println("  запустіть ще раз — нові запити підуть лише для цього кроку (" + n + "), решта — з кешу.");
            rows15.add(new Row("ваша ітерація", null, null, problems.isEmpty() ? "ще не виконано" : "правку не застосовано", ""));
        } else {
            List<Variant.Example> withMine = new ArrayList<>(ex);
            withMine.addAll(tweaks.examples());
            String mySystem = Lab3Prompts.system(withMine, tweaks.rule());
            if (!tweaks.rule().isEmpty()) System.out.println("  Ваше правило: «" + tweaks.rule() + "»");
            for (Variant.Example e : tweaks.examples()) {
                System.out.printf("  Ваш приклад: «%s» → %s  (найсхожіше тестове — %s, схожість %.2f)%n", Console.clip(e.text(), 70), compact(e.labels()),
                        v.mostSimilar(e.text()).map(s -> s.getKey().shortId()).orElse("—"), v.mostSimilar(e.text()).map(Map.Entry::getValue).orElse(0.0));
            }
            if (!tweaks.rule().isEmpty() && !tweaks.examples().isEmpty()) {
                System.out.println("  Увага: змінено і правило, і приклади — якщо результат зміниться, ви не знатимете, що саме спрацювало.");
            }
            c0 = cls.newCalls();
            k0 = cls.cachedCalls();
            mine = new ArrayList<>();
            for (Variant.Inquiry q : all) mine.add(cls.ask(mySystem, q, base, "s1"));
            List<String> fx = new ArrayList<>(), br = new ArrayList<>();
            System.out.printf("  %-4s %-22s %-24s %-24s %s%n", "№", "очікувано", "few-shot", "ваша ітерація", "");
            for (int i = 0; i < n; i++) {
                Variant.Inquiry q = all.get(i);
                boolean a = few.get(i).correct(), b = mine.get(i).correct();
                String mark = !a && b ? "виправили" : a && !b ? "зіпсували" : "";
                if (!a && b) fx.add(q.shortId());
                if (a && !b) br.add(q.shortId());
                System.out.printf("  %-4s %-22s %-24s %-24s %s%n", q.shortId(), expectedText(q), cell(few.get(i)), cell(mine.get(i)), mark);
            }
            Metrics.Score sm = score(mine);
            iv = Metrics.compare(sm.correct(), sf.correct(), noise.spread(), n);
            Console.kv("точність", "few-shot " + sf.accuracyText() + " → ваша " + sm.accuracyText());
            Console.kv("ефект проти few-shot", iv.text() + " (розкид — з кроку 1)");
            Console.kv("виправили / зіпсували", (fx.isEmpty() ? "—" : String.join(", ", fx)) + "  /  " + (br.isEmpty() ? "—" : String.join(", ", br)));
            Stats stm = stats(mine, thinkKnown);
            Console.kv("ціна правки", String.format(Locale.ROOT, "%+.0f вхідних токенів на запит", stm.in() - stf.in()));
            calls(cls, c0, k0);
            rows15.add(new Row("ваша ітерація", sm, stm, iv.text() + " (проти few-shot)", describe(tweaks)));
        }

        // ---------------------------------------------------------------- крок 4
        List<Metrics.Pick> picks = Metrics.pickHardest(all, labels(z1), labels(z2), labels(few), v.hardestFallback(), HARD_N);
        List<Variant.Inquiry> hard = picks.stream().map(Metrics.Pick::inquiry).toList();
        Console.section("Крок 4. Міркування: дешево чи дорого — на " + HARD_N + " найважчих: " + String.join(", ", hard.stream().map(Variant.Inquiry::shortId).toList()));
        System.out.println("  Найважчі обрано за вашими ж результатами кроків 1–2 (помилки zero-shot s1/s2 і few-shot, розбіжність s1 ≠ s2):");
        for (Metrics.Pick p : picks) System.out.println("    " + p.inquiry().shortId() + "  " + p.reason());
        System.out.println("  Порівнюємо чотири способи на тих самих " + HARD_N + " зверненнях (усі — few-shot з кроку 2):");
        System.out.println("    база          — мінімальне міркування; ті самі відповіді, що в кроці 2 (нових запитів 0);");
        System.out.println("    +reasoning    — мінімальне міркування, але в схемі відповіді поле reasoning ПЕРШИМ: модель коротко пише міркування");
        System.out.println("                    текстом, а потім мітки. ДЕШЕВО: кілька десятків вихідних токенів;");
        System.out.println("    effort low    — модель міркує «про себе» перед відповіддю (параметр API), низький рівень зусиль;");
        System.out.println("    effort high   — те саме, високий рівень. ДОРОГО: сотні–тисячі токенів міркування і довша відповідь.");
        System.out.println("  Увага: ці звернення обрано САМЕ за помилками, тож будь-який повтор у середньому виглядатиме краще («регресія до середнього»).");
        System.out.println("  Чесна база — повтори того самого few-shot (s2, s3) у кроці 5; там же порівняння з шумом.");
        List<Classifier.Answer> baseHard = new ArrayList<>();
        for (Variant.Inquiry q : hard) baseHard.add(few.get(all.indexOf(q)));
        c0 = cls.newCalls();
        k0 = cls.cachedCalls();
        List<Classifier.Answer> rsn = new ArrayList<>();
        for (Variant.Inquiry q : hard) rsn.add(cls.ask(fewSystem, q, base.withReasoningField(true), "s1"));
        Attempt lowRun = attempt(cls, fewSystem, hard, low);
        Attempt highRun = attempt(cls, fewSystem, hard, high);
        System.out.println("  Що реально надіслано (параметр API):");
        System.out.println("    база, +reasoning: " + client.effortParameter(CallOptions.Effort.MINIMAL));
        System.out.println("    effort low:       " + client.effortParameter(CallOptions.Effort.LOW));
        System.out.println("    effort high:      " + client.effortParameter(CallOptions.Effort.HIGH));
        System.out.println("  ok — усі три мітки правильні; ✗ cat / urg / hum — не збіглася категорія / терміновість / «хто відповідає».");
        System.out.printf("  %-4s %-22s %-16s %-16s %-16s %-16s%n", "№", "очікувано", "база", "+reasoning", "effort low", "effort high");
        for (int i = 0; i < hard.size(); i++) {
            System.out.printf("  %-4s %-22s %-16s %-16s %-16s %-16s%n", hard.get(i).shortId(), expectedText(hard.get(i)),
                    verdict(baseHard.get(i)), verdict(rsn.get(i)), lowRun.ok() ? verdict(lowRun.answers().get(i)) : "пропущено",
                    highRun.ok() ? verdict(highRun.answers().get(i)) : "пропущено");
        }
        int reasoningFirst = 0;
        System.out.println("  Що написала модель у полі reasoning:");
        for (Classifier.Answer x : rsn) {
            String txt = x.entry().text();
            int ir = txt.indexOf("\"reasoning\""), ic = txt.indexOf("\"category\"");
            if (ir >= 0 && ic >= 0 && ir < ic) reasoningFirst++;
            System.out.println("    " + x.inquiry().shortId() + ": " + Console.clip(x.labels().reasoning() == null ? "(немає)" : x.labels().reasoning(), 110));
        }
        Console.kv("reasoning перед міткою", reasoningFirst + " з " + rsn.size() + " відповідей"
                + (reasoningFirst < rsn.size() ? "  (якщо не всі — модель не дотримала порядку полів, і міркування вже не впливає на мітку)" : ""));
        Metrics.Score sb = score(baseHard), sr = score(rsn);
        Stats stb = stats(baseHard, thinkKnown), str = stats(rsn, thinkKnown);
        Metrics.Score sLow = lowRun.ok() ? score(lowRun.answers()) : null, sHigh = highRun.ok() ? score(highRun.answers()) : null;
        Stats stLow = lowRun.ok() ? stats(lowRun.answers(), thinkKnown) : null, stHigh = highRun.ok() ? stats(highRun.answers(), thinkKnown) : null;
        Metrics.GainPrice pr = price(sb, stb, sr.correct(), str.out());
        Metrics.GainPrice pLow = sLow == null ? null : price(sb, stb, sLow.correct(), stLow.out());
        Metrics.GainPrice pHigh = sHigh == null ? null : price(sb, stb, sHigh.correct(), stHigh.out());
        System.out.println("  Ціна приросту — скільки ДОДАТКОВИХ вихідних токенів (разом на " + HARD_N + " зверненнях, порівняно з базою) коштує");
        System.out.println("  одне додатково правильне звернення. Вихідні токени — найдорожчі; на 1000 звернень на день різниця множиться на 200.");
        System.out.printf("  %-14s %-12s %-30s %-14s %s%n", "", "точність", "токенів in / out (thinking)", "затримка, мс", "ціна приросту");
        System.out.printf("  %-14s %-12s %-30s %-14s %s%n", "база", sb.accuracyText(), stb.tokens(), stb.latency(), "—");
        System.out.printf("  %-14s %-12s %-30s %-14s %s%n", "+reasoning", sr.accuracyText(), str.tokens(), str.latency(), pr.text());
        printEffortRow("effort low", lowRun, sLow, stLow, pLow);
        printEffortRow("effort high", highRun, sHigh, stHigh, pHigh);
        if (!lowRun.ok() || !highRun.ok()) {
            System.out.println("      Найімовірніше, ця модель не підтримує такий рівень міркування (див. рядок «Що реально надіслано»).");
            System.out.println("      Gemini 2.x: LAB_THINKING_BUDGET=1024; Claude: модель з міркуванням. Запишіть це у звіт як результат.");
        }
        if (!thinkKnown) {
            System.out.println("  н/д: " + client.provider() + " не повідомляє токени міркування окремо — вони вже всередині out.");
        } else if (stHigh != null && stHigh.thinking() == 0) {
            System.out.println("  Токенів міркування в effort high 0 — модель проігнорувала параметр або не вміє міркувати; effort high фактично = база.");
        }
        if (stb.thinking() > 0) {
            System.out.println("  Увага: у базі токени міркування не нуль — «мінімальне» міркування в цієї моделі не нульове.");
        }
        calls(cls, c0, k0);

        // ---------------------------------------------------------------- крок 5
        Console.section("Крок 5. Голосування трьох відповідей і поріг «авто / людина» (5 найважчих)");
        System.out.println("  Оцінка впевненості за кількома відповідями — спрощений варіант self-consistency (в оригіналі Wang et al., 2022");
        System.out.println("  голосують ланцюжки міркувань, а тут — готові мітки). s1 — відповідь кроку 2, s2 і s3 — ті самі запити ще двічі");
        System.out.println("  (мінімальне міркування, temperature за замовчуванням).");
        System.out.println("  Згода = скільки з " + SAMPLES + " відповідей збігаються з найчастішою (усі 3 мітки): 3/3 — усі однакові, 2/3 — одна інша,");
        System.out.println("  1/3 — усі різні. Більшість — найчастіша відповідь; «ok» — вона правильна.");
        c0 = cls.newCalls();
        k0 = cls.cachedCalls();
        List<List<Classifier.Answer>> samples = new ArrayList<>();
        samples.add(baseHard);
        for (int s = 2; s <= SAMPLES; s++) {
            List<Classifier.Answer> run = new ArrayList<>();
            for (Variant.Inquiry q : hard) run.add(cls.ask(fewSystem, q, base, "s" + s));
            samples.add(run);
        }
        List<Metrics.Vote> votes = new ArrayList<>();
        System.out.printf("  %-4s %-22s %-23s %-23s %-23s %-6s %s%n", "№", "очікувано", "s1", "s2", "s3", "згода", "більшість");
        for (int i = 0; i < hard.size(); i++) {
            List<Labels> ls = new ArrayList<>();
            for (List<Classifier.Answer> run : samples) ls.add(run.get(i).labels());
            Metrics.Vote vt = Metrics.vote(ls);
            votes.add(vt);
            System.out.printf("  %-4s %-22s %-23s %-23s %-23s %-6s %s%n", hard.get(i).shortId(), expectedText(hard.get(i)), compact(ls.get(0)), compact(ls.get(1)),
                    compact(ls.get(2)), vt.agreementText(), hard.get(i).accepts(vt.majority()) ? "ok" : "✗");
        }
        int[] perSample = new int[SAMPLES];
        for (int s = 0; s < SAMPLES; s++) perSample[s] = score(samples.get(s)).correct();
        Metrics.Noise noise5 = Metrics.noise(HARD_N, perSample);
        int voteOk = 0;
        for (int i = 0; i < votes.size(); i++) voteOk += hard.get(i).accepts(votes.get(i).majority()) ? 1 : 0;
        Console.kv("few-shot s1 / s2 / s3", perSample[0] + " / " + perSample[1] + " / " + perSample[2] + " з " + HARD_N
                + "  → шум на 5: розкид " + noise5.rawSpread() + " (беремо " + noise5.spread() + "), середнє " + fmt(noise5.mean()));
        Console.kv("голосування більшістю", Metrics.frac(voteOk, HARD_N) + "  → " + Metrics.compare(voteOk, noise5.mean(), noise5.spread(), HARD_N).text());
        System.out.println("  Усі три пороги на тих самих відповідях (нових запитів не треба). Поріг t/3: згода ≥ t → автоматично, інакше — людині.");
        System.out.println("  «людині» — частка звернень, які бот віддав би працівнику; «точність авто» — серед тих, що бот обробив сам;");
        System.out.println("  «спіймано» — помилки, які поріг віддав людині; «пропущено» — помилки, що пішли автоматично; «з них needs_human=true» —");
        System.out.println("  пропущені, де потрібна була людина, а бот відповів сам (найдорожча помилка).");
        System.out.printf("    %-18s %-14s %-20s %-18s %-12s %s%n", "поріг", "людині", "точність авто", "помилок спіймано", "пропущено", "з них needs_human=true");
        List<Metrics.Routing> routings = new ArrayList<>();
        for (int t = 1; t <= SAMPLES; t++) {
            Metrics.Routing r = Metrics.route(votes, hard, t);
            routings.add(r);
            System.out.printf("    %-18s %-14s %-20s %-18s %-12s %s%n", t + "/" + SAMPLES + (t == 1 ? " (без порогу)" : ""), r.humanShareText(), r.autoAccuracyText(),
                    r.caughtErrors(), r.missedErrors(), r.missedHuman());
        }
        System.out.println("  Оберіть поріг для бота й обґрунтуйте вартістю помилки. Приклад міркування: «пропущене needs_human=true (студент чекає");
        System.out.println("  на людину, а бот відповів сам) дорожче, ніж зайве звернення до людини».");
        System.out.println("  Повернімося до кроку 4 — тепер є шум на цих 5:");
        Metrics.Verdict rv = Metrics.compare(sr.correct(), noise5.mean(), noise5.spread(), HARD_N);
        Metrics.Verdict lv = sLow == null ? null : Metrics.compare(sLow.correct(), noise5.mean(), noise5.spread(), HARD_N);
        Metrics.Verdict hv = sHigh == null ? null : Metrics.compare(sHigh.correct(), noise5.mean(), noise5.spread(), HARD_N);
        Console.kv("+reasoning проти бази", rv.text());
        Console.kv("effort low проти бази", lv == null ? "пропущено" : lv.text());
        Console.kv("effort high проти бази", hv == null ? "пропущено" : hv.text());
        System.out.println("  На 5 зверненнях одне звернення = " + Metrics.pp(1, HARD_N) + ": тут можна побачити напрям, але не довести ефект.");
        Stats stv = sumOfSamples(samples, thinkKnown);
        calls(cls, c0, k0);
        rows5.add(new Row("база: few-shot, мінімальне міркування (крок 2, s1)", sb, stb,
                "база; шум — повтори s2, s3: " + perSample[1] + " і " + perSample[2] + " з " + HARD_N + ", розкид s1–s3 = " + noise5.rawSpread() + " зв.", "—", ""));
        rows5.add(new Row("+ поле reasoning перед мітками", sr, str, rv.text(), pr.text(), reasoningFirst + "/" + rsn.size() + " reasoning першим"));
        rows5.add(effortRow("effort low", lowRun, sLow, stLow, lv, pLow));
        rows5.add(effortRow("effort high", highRun, sHigh, stHigh, hv, pHigh));
        Metrics.Verdict vv = Metrics.compare(voteOk, noise5.mean(), noise5.spread(), HARD_N);
        Metrics.GainPrice pv = price(sb, stb, voteOk, stv.out());
        rows5.add(new Row("голосування 3× (більшість)", new Metrics.Score(HARD_N, voteOk, -1, -1, -1, -1, -1), stv, vv.text(), pv.text(),
                "токени — сума трьох відповідей; людині: 2/3 → " + routings.get(1).humanShareText() + ", 3/3 → " + routings.get(2).humanShareText()));
        // ---------------------------------------------------------------- крок 6
        if (bonus) {
            Console.section("Крок 6. Бонус: перевірка EmotionPrompt (Li et al., 2023: автори повідомили приріст)");
            System.out.println("  Ті самі запити, що s1, але в кінці системного промпта — рядок «" + Lab3Prompts.CAREER + "».");
            System.out.println("  Порівнюємо з шумом трьох однакових серій s1, s2, s3 з кроку 5.");
            c0 = cls.newCalls();
            k0 = cls.cachedCalls();
            List<Classifier.Answer> career = new ArrayList<>();
            String careerSystem = Lab3Prompts.withCareer(fewSystem);
            for (Variant.Inquiry q : hard) career.add(cls.ask(careerSystem, q, base, "s1"));
            System.out.printf("  %-4s %-6s %-6s %-6s %-10s%n", "№", "s1", "s2", "s3", "EmotionPrompt");
            for (int i = 0; i < hard.size(); i++) {
                System.out.printf("  %-4s %-6s %-6s %-6s %-10s%n", hard.get(i).shortId(), mark(samples.get(0).get(i)), mark(samples.get(1).get(i)),
                        mark(samples.get(2).get(i)), mark(career.get(i)));
            }
            Metrics.Score sk = score(career);
            Metrics.Verdict ev = Metrics.compare(sk.correct(), noise5.mean(), noise5.spread(), HARD_N);
            Console.kv("нейтральні s1 / s2 / s3", perSample[0] + " / " + perSample[1] + " / " + perSample[2] + " з " + HARD_N);
            Console.kv("з фразою", sk.correct() + " з " + HARD_N + "; відповіді відрізняються від s1 у "
                    + Metrics.disagreements(labels(samples.get(0)), labels(career)) + " з " + HARD_N + " (s1 ↔ s2 — у "
                    + Metrics.disagreements(labels(samples.get(0)), labels(samples.get(1))) + ")");
            Console.kv("висновок", ev.text());
            calls(cls, c0, k0);
            Stats stk = stats(career, thinkKnown);
            rows5.add(new Row("EmotionPrompt (бонус)", sk, stk, ev.text(), price(sb, stb, sk.correct(), stk.out()).text(), ""));
        } else {
            Console.section("Крок 6. Бонус пропущено (LAB_SKIP_BONUS=1)");
            rows5.add(new Row("EmotionPrompt (бонус)", null, null, "пропущено (LAB_SKIP_BONUS=1)", "", ""));
        }

        // ---------------------------------------------------------------- підсумок
        Console.section("Підсумок: дві таблиці (повністю, з поясненнями — у " + (resultsFile == null ? "(не зберігається)" : resultsFile) + ")");
        String t15 = table15(rows15), t5 = table5(rows5);
        System.out.println("  Таблиця 1. На всіх " + n + " зверненнях. Що це: " + Explain.WHAT_15);
        System.out.println(t15);
        System.out.println("  Як читати:");
        Explain.HOW_15.forEach(line -> System.out.println("  · " + line));
        System.out.println("  Приклад: " + Explain.example15(rows15, noise));
        System.out.println("\n  Таблиця 2. На " + HARD_N + " найважчих (" + String.join(", ", hard.stream().map(Variant.Inquiry::shortId).toList()) + "). Що це: " + Explain.WHAT_5);
        System.out.println(t5);
        System.out.println("  Як читати:");
        Explain.HOW_5.forEach(line -> System.out.println("  · " + line));
        System.out.println("  Приклад: " + Explain.example5(rows5, noise5));
        System.out.println();
        Console.kv("разом за прогін", "нових запитів: " + cls.newCalls() + ", з кешу: " + cls.cachedCalls());
        if (client.isMock()) System.out.println("  РЕЖИМ MOCK: числа імітовані — у звіт не йдуть.");
        String markdown = md.build(all, z1, z2, few, mine, tweaks, picks, rsn, lowRun, highRun, samples, votes, routings, rows15, rows5, noise, noise5, cls);
        writeResults(resultsFile, markdown);
        if (resultsFile != null) {
            System.out.println(client.isMock() ? "  (MOCK: файли " + resultsFile + " і " + cache.file() + " — лише для перевірки запуску, до звіту не додаються)"
                    : "  До звіту додайте файли " + resultsFile + " і " + cache.file() + ", а також збережіть цей вивід консолі (лог).");
        }
        Console.discuss(
                "Крок 1: який розкид дали два однакові zero-shot прогони? У якому полі (категорія / терміновість / людина) модель помилялась найчастіше — чи збіглося з прогнозом?",
                "Крок 2: чи більший ефект few-shot за розкид? Які звернення виправили приклади, а які зіпсували?",
                "Крок 3: що ви змінили, чому, і чи вийшов результат за межі шуму?",
                "Крок 4: що дешевше дає приріст на найважчих — поле reasoning чи effort? Скільки токенів коштує +1 правильне звернення і чи вийшов приріст за межі шуму?",
                "Крок 5: який поріг «авто / людина» ви обрали і чому?",
                "Бонус: чи вийшов EmotionPrompt за межі шуму на 5 зверненнях? Чого бракує, щоб перевірити твердження авторів?");
        return new Result(rows15, rows5, picks, fv, iv, noise, disagree, noise5, routings, cls.newCalls(), cls.cachedCalls(), markdown);
    }

    // ------------------------------------------------------------------ крок 4: effort low / high

    /** Серія запитів з одним рівнем effort: відповіді або (якщо API відхилив параметр, 400) причина пропуску. */
    record Attempt(List<Classifier.Answer> answers, String skipped) {
        boolean ok() {
            return skipped == null;
        }
    }

    private static Attempt attempt(Classifier cls, String system, List<Variant.Inquiry> hard, CallOptions options) {
        List<Classifier.Answer> out = new ArrayList<>();
        try {
            for (Variant.Inquiry q : hard) out.add(cls.ask(system, q, options, "s1"));
            return new Attempt(out, null);
        } catch (ApiException e) {
            if (e.status() != 400) throw e; // 429, мережа — це не «модель не підтримує», хай обробляє main
            return new Attempt(List.of(), e.getMessage() == null ? "HTTP 400" : e.getMessage());
        }
    }

    private static Metrics.GainPrice price(Metrics.Score base, Stats baseStats, int correct, double outPerInquiry) {
        return Metrics.gainPrice(base.correct(), baseStats.out() * base.n(), correct, outPerInquiry * base.n());
    }

    private static void printEffortRow(String name, Attempt a, Metrics.Score s, Stats st, Metrics.GainPrice p) {
        if (!a.ok()) {
            System.out.printf("  %-14s пропущено: API відхилив параметр — %s%n", name, Console.clip(a.skipped(), 120));
            return;
        }
        System.out.printf("  %-14s %-12s %-30s %-14s %s%n", name, s.accuracyText(), st.tokens(), st.latency(), p.text());
    }

    private static Row effortRow(String name, Attempt a, Metrics.Score s, Stats st, Metrics.Verdict v, Metrics.GainPrice p) {
        if (!a.ok()) return new Row(name, null, null, "пропущено", "", "API відхилив параметр: " + Console.clip(a.skipped(), 80));
        return new Row(name, s, st, v.text(), p.text(), "");
    }

    // ------------------------------------------------------------------ вивід і підрахунки

    private static String trapName(Variant.Inquiry q) {
        return q.isTrap() ? Variant.TRAPS.getOrDefault(q.trap(), q.trap()) : "";
    }

    /** Очікувані мітки з альтернативами: «study/normal|low/люд». */
    static String expectedText(Variant.Inquiry q) {
        return String.join("|", q.accepted("category")) + "/" + String.join("|", q.accepted("urgency")) + "/"
                + String.join("|", q.accepted("needs_human").stream().map(x -> x.equals("true") ? "люд" : "бот").toList());
    }

    static String compact(Labels l) {
        if (!l.parsed()) return "не розібрано";
        return l.category() + "/" + l.urgency() + "/" + (l.needsHuman() ? "люд" : "бот");
    }

    /** Комірка відповіді: «ok» або «✗ мітки». */
    private static String cell(Classifier.Answer x) {
        return x.correct() ? "ok" : Console.clip(compact(x.labels()), 22);
    }

    private static String verdict(Classifier.Answer x) {
        String w = Metrics.wrongFields(x.inquiry(), x.labels());
        return w.isEmpty() ? "ok" : "✗ " + w.replace("category", "cat").replace("urgency", "urg").replace("needs_human", "hum");
    }

    private static String mark(Classifier.Answer x) {
        return x.correct() ? "ok" : "✗";
    }

    private static String fmt(double x) {
        return String.format(Locale.ROOT, "%.1f", x).replace('.', ',');
    }

    private static String describe(Tweaks t) {
        List<String> parts = new ArrayList<>();
        if (!t.rule().isEmpty()) parts.add("правило");
        if (!t.examples().isEmpty()) parts.add(t.examples().size() + " приклад" + (t.examples().size() == 1 ? "" : "и"));
        return String.join(" + ", parts);
    }

    private static String pricePerDay(LlmClient client, double extraIn) {
        Optional<Pricing> p = Pricing.forModel(client.model());
        double mtok = extraIn * 1000 / 1_000_000.0;
        String tokens = String.format(Locale.ROOT, "+%.2f млн вхідних токенів", mtok).replace('.', ',');
        if (p.isEmpty()) return tokens + " (ціни моделі " + client.model() + " немає в Pricing — порахуйте за сторінкою цін провайдера)";
        return tokens + String.format(Locale.ROOT, " × $%.2f за 1 млн = $%.2f на день на платному тарифі", p.get().inputPerMTok(), mtok * p.get().inputPerMTok())
                + (client.hasFreeTier() ? " (на безплатному — $0, але менше запитів на день)" : "");
    }

    private static void printConfusions(List<Variant.Inquiry> qs, List<Labels> got, String what) {
        Map<String, Integer> conf = Metrics.confusions(qs, got);
        if (conf.isEmpty()) {
            Console.kv("сплутані категорії", "немає (" + what + ")");
            return;
        }
        StringBuilder sb = new StringBuilder();
        conf.forEach((pair, c) -> sb.append(sb.isEmpty() ? "" : "; ").append(pair).append(": ").append(c));
        Console.kv("сплутані категорії", sb + "  (" + what + ")");
    }

    private static Metrics.Score score(List<Classifier.Answer> as) {
        return Metrics.score(as.stream().map(Classifier.Answer::inquiry).toList(), labels(as));
    }

    private static List<Labels> labels(List<Classifier.Answer> as) {
        return as.stream().map(Classifier.Answer::labels).toList();
    }

    static Stats stats(List<Classifier.Answer> as, boolean thinkingKnown) {
        double in = 0, out = 0, th = 0, lat = 0;
        for (Classifier.Answer x : as) {
            in += x.entry().inputTokens();
            out += x.entry().outputTokens();
            th += x.entry().thinkingTokens();
            lat += x.entry().latencyMs();
        }
        int n = Math.max(1, as.size());
        return new Stats(in / n, out / n, th / n, lat / n, thinkingKnown);
    }

    /** Для голосування: на одне звернення — СУМА трьох вибірок (саме стільки коштує голосування). */
    private static Stats sumOfSamples(List<List<Classifier.Answer>> samples, boolean thinkingKnown) {
        double in = 0, out = 0, th = 0, lat = 0;
        for (List<Classifier.Answer> run : samples) {
            Stats s = stats(run, thinkingKnown);
            in += s.in();
            out += s.out();
            th += s.thinking();
            lat += s.latencyMs();
        }
        return new Stats(in, out, th, lat, thinkingKnown);
    }

    private static void calls(Classifier cls, int newBefore, int cachedBefore) {
        Console.kv("запитів у кроці", "нових запитів: " + (cls.newCalls() - newBefore) + ", з кешу: " + (cls.cachedCalls() - cachedBefore));
    }

    /** Таблиця «на всіх 15» у Markdown (вставляється у звіт як є). */
    static String table15(List<Row> rows) {
        StringBuilder sb = new StringBuilder("| Техніка | Точність (3 мітки) | Категорія | Пастки | Ефект і висновок | Токени in / out (thinking) на звернення | Затримка, мс |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            if (r.score() == null) {
                sb.append("| ").append(r.technique()).append(" | ").append(r.effect()).append(" | — | — | — | — | — |\n");
                continue;
            }
            Metrics.Score s = r.score();
            sb.append("| ").append(r.technique()).append(r.note().isEmpty() ? "" : " (" + r.note() + ")").append(" | ").append(s.accuracyText())
                    .append(" | ").append(s.categoryOk()).append("/").append(s.n()).append(" | ").append(s.trapsText()).append(" | ").append(r.effect())
                    .append(" | ").append(r.stats().tokens()).append(" | ").append(r.stats().latency()).append(" |\n");
        }
        return sb.toString().stripTrailing();
    }

    /** Таблиця «на 5 найважчих» у Markdown: база, +reasoning, effort low, effort high, голосування, бонус. */
    static String table5(List<Row> rows) {
        StringBuilder sb = new StringBuilder("| Техніка | Точність (3 мітки) | Ефект і висновок (шум s1–s3) | Токени in / out (thinking) на звернення | Затримка, мс | Ціна приросту | Примітка |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            if (r.score() == null) {
                sb.append("| ").append(r.technique()).append(" | — | ").append(r.effect()).append(" | — | — | — | ").append(r.note()).append(" |\n");
                continue;
            }
            sb.append("| ").append(r.technique()).append(" | ").append(r.score().accuracyText()).append(" | ").append(r.effect()).append(" | ")
                    .append(r.stats().tokens()).append(" | ").append(r.stats().latency()).append(" | ").append(r.price().isEmpty() ? "—" : r.price())
                    .append(" | ").append(r.note()).append(" |\n");
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Пояснення до таблиць — однакові в консолі й у target/lab3-results.md: що це за таблиця, на яке питання відповідає,
     * як читати кожну колонку, і приклад тлумачення рядка звичайною мовою (на ваших числах).
     */
    static final class Explain {
        private Explain() {
        }

        static final String LEGEND = String.join("\n",
                "- **Мітки звернення** записано як «категорія/терміновість/хто відповідає», наприклад `study/high/люд`: «люд» — потрібна людина"
                        + " (needs_human=true), «бот» — бот відповідає сам (needs_human=false).",
                "- **ok** — збіглися всі три мітки. Інакше в комірці — що відповіла модель (наприклад, `other/low/бот`) або"
                        + " «✗ cat / urg / hum» — яка мітка не збіглася (категорія / терміновість / хто відповідає).",
                "- **|** у колонці «Очікувано» — допустимі дві відповіді для спірного звернення (наприклад, `normal|low`).",
                "- **s1, s2, s3** — перший запит і два повтори ТОГО САМОГО запиту. Різниця між ними — шум (випадковий розкид моделі).",
                "- **✗ zero s1** у колонці «Чому обрано» — на цьому зверненні помилився zero-shot у прогоні s1; так само «✗ zero s2»,"
                        + " «✗ few-shot»; «s1 ≠ s2» — два однакові прогони дали різні відповіді; «добрано: пастка» — помилок забракло, взято пастку.",
                "- **зв.** — звернення; **п.п.** — відсоткові пункти (на 15 зверненнях одне звернення = 6,7 п.п., на 5 — 20 п.п.).",
                "- **effort** — рівень міркування: скільки зусиль модель вкладає в міркування «про себе» перед відповіддю"
                        + " (мінімальне / low / high). **thinking** — токени, витрачені на це міркування; вони входять у вихідні токени й оплачуються як вихідні.",
                "- **temperature** у лабі 3 не надсилається: усі запити — зі значенням моделі за замовчуванням.");

        static final String WHAT_15 = "точність кожного способу на всіх 15 зверненнях вашого варіанта. Питання: чи допомагають приклади"
                + " (few-shot) і ваша правка більше, ніж випадковий розкид двох однакових прогонів.";

        static final List<String> HOW_15 = List.of(
                "«Точність (3 мітки)» — у скількох зверненнях із 15 збіглися всі три мітки (і у відсотках).",
                "«Категорія» — у скількох збіглася хоча б категорія.",
                "«Пастки» — точність лише на навмисно складних зверненнях (сарказм, дві теми, суржик тощо).",
                "«Ефект і висновок» — різниця з базою у зверненнях і п.п.; «розкид» — на скільки звернень розійшлися два однакові прогони"
                        + " s1 і s2 (беремо не менше 1); «в межах шуму» — |ефект| не більший за розкид, тобто висновку немає; «краще / гірше за шум» —"
                        + " ефект більший за розкид.",
                "«Токени in / out (thinking)» — середні вхідні / вихідні токени на одне звернення; у дужках — скільки з вихідних пішло на"
                        + " міркування (н/д — провайдер не повідомляє окремо).",
                "«Затримка, мс» — середній час відповіді на одне звернення.");

        static final String WHAT_5 = "чотири способи міркування, голосування і бонус на 5 зверненнях, де модель помилялася найчастіше."
                + " Питання: яке міркування дає приріст точності і скільки токенів він коштує.";

        static final List<String> HOW_5 = List.of(
                "«база» — few-shot з кроку 2 з мінімальним міркуванням; з нею порівнюються всі інші рядки.",
                "«+ поле reasoning» — дешеве міркування: модель пише кілька речень пояснення ПЕРЕД мітками (видно у відповіді).",
                "«effort low / high» — дороге міркування: модель міркує «про себе» перед відповіддю (параметр API), низький / високий рівень.",
                "«Ефект і висновок (шум s1–s3)» — різниця з базою; шум тут — розкид трьох однакових few-shot серій s1, s2, s3 на цих 5 зверненнях.",
                "«thinking» у дужках — токени міркування на одне звернення (н/д — провайдер не повідомляє окремо, вони всередині out).",
                "«Ціна приросту» — скільки ДОДАТКОВИХ вихідних токенів (разом на 5 зверненнях, порівняно з базою) припало на одне додатково"
                        + " правильне звернення; «приросту немає» — точність не вища за базу, і додаткові токени витрачено без користі.",
                "«голосування 3×» — більшість із трьох відповідей s1–s3; його токени — сума трьох запитів.");

        /** Приклад тлумачення рядка few-shot на всіх 15. */
        static String example15(List<Row> rows, Metrics.Noise noise) {
            Row few = rows.stream().filter(r -> r.technique().startsWith("few-shot")).findFirst().orElse(null);
            if (few == null || few.score() == null) return "—";
            int c = few.score().correct(), n = few.score().n();
            return "рядок «" + few.technique() + "»: " + c + " з " + n + " звернень правильні, а zero-shot у середньому — "
                    + fmt(noise.mean()) + "; два однакові zero-shot прогони розійшлися на " + noise.rawSpread() + " зв. (беремо "
                    + noise.spread() + "). " + (Math.abs(c - noise.mean()) <= noise.spread() + 1e-9
                    ? "Різниця не більша за цей розкид — тож приклади не довели ні користі, ні шкоди."
                    : c > noise.mean() ? "Різниця більша за розкид — приклади справді допомогли." : "Різниця більша за розкид — приклади справді зашкодили.");
        }

        /** Приклад тлумачення рядка «effort high» (або +reasoning, якщо effort пропущено) на 5 найважчих. */
        static String example5(List<Row> rows, Metrics.Noise noise5) {
            Row base = rows.get(0);
            Row r = rows.stream().filter(x -> x.technique().equals("effort high") && x.score() != null).findFirst()
                    .orElse(rows.get(1));
            if (base.score() == null || r.score() == null) return "—";
            String tokens = String.format(Locale.ROOT, "%.0f", r.stats().out()) + " вихідних токенів на звернення проти "
                    + String.format(Locale.ROOT, "%.0f", base.stats().out()) + " у бази";
            String verdict = r.effect().contains("краще за шум") ? "приріст більший за цей розкид — міркування справді допомогло"
                    : r.effect().contains("гірше за шум") ? "втрата більша за цей розкид — міркування справді зашкодило"
                    : "різниця не більша за цей розкид — висновку немає, хоч токени витрачено";
            return "рядок «" + r.technique() + "»: " + r.score().correct() + " з " + HARD_N + " правильні (база — " + base.score().correct()
                    + "), " + tokens + ". Три однакові серії бази розійшлися на " + noise5.rawSpread() + " зв. (беремо " + noise5.spread() + "): "
                    + verdict + ". Ціна приросту: " + r.price() + ".";
        }

        static final String WHAT_ROUTING = "що буде, якщо бот відповідатиме сам лише тоді, коли три відповіді моделі достатньо згодні,"
                + " а решту віддаватиме людині. Питання: який поріг згоди обрати.";

        static final List<String> HOW_ROUTING = List.of(
                "«Поріг» t/3 — звернення йде автоматично, якщо щонайменше t із 3 відповідей однакові; інакше — людині. 1/3 — без порогу (усе автоматично).",
                "«Людині» — частка звернень, які бот віддав би працівнику.",
                "«Точність авто» — точність серед звернень, які бот обробив сам.",
                "«Помилок спіймано» — помилки більшості, які поріг відправив людині (добре).",
                "«Пропущено» — помилки, які все одно пішли автоматично.",
                "«з них needs_human=true» — з пропущених ті, де потрібна була людина, а бот відповів сам (найдорожча помилка).");

        static String exampleRouting(List<Metrics.Routing> rs) {
            Metrics.Routing r = rs.get(rs.size() - 1);
            return "рядок «" + r.threshold() + "/" + SAMPLES + "»: бот віддав би людині " + r.toHuman() + " з " + r.total() + " звернень; серед решти "
                    + r.autoAccuracyText() + " правильні; помилок спіймано (віддано людині) — " + r.caughtErrors() + ", пропущено — " + r.missedErrors()
                    + (r.missedHuman() > 0 ? "; серед пропущених у " + r.missedHuman() + " студент чекав на людину, а бот відповів сам." : ".");
        }
    }

    /** Файл target/lab3-results.md: усе, що потрібно для звіту, — щоб не переписувати таблиці з консолі. */
    private record Md(LlmClient client, Variant v) {
        String build(List<Variant.Inquiry> all, List<Classifier.Answer> z1, List<Classifier.Answer> z2, List<Classifier.Answer> few,
                     List<Classifier.Answer> mine, Tweaks tweaks, List<Metrics.Pick> picks, List<Classifier.Answer> rsn, Attempt low, Attempt high,
                     List<List<Classifier.Answer>> samples, List<Metrics.Vote> votes, List<Metrics.Routing> routings, List<Row> rows15, List<Row> rows5,
                     Metrics.Noise noise, Metrics.Noise noise5, Classifier cls) {
            StringBuilder sb = new StringBuilder();
            sb.append("# Лабораторна 3 — результати прогону\n\n");
            sb.append("Модель: ").append(client.provider()).append(" ").append(client.model()).append(client.isMock() ? " (MOCK — імітація, у звіт не йде)" : "")
                    .append(" · варіант ").append(v.variant()).append(" (").append(v.title()).append(") · ").append(LocalDate.now())
                    .append(" · запитів у цьому запуску: нових ").append(cls.newCalls()).append(", з кешу ").append(cls.cachedCalls()).append("\n\n");
            sb.append("Параметри міркування, які реально надіслано: мінімальне (кроки 1–3, база й +reasoning) — ")
                    .append(client.effortParameter(CallOptions.Effort.MINIMAL)).append("; effort low — ").append(low.ok() ? client.effortParameter(CallOptions.Effort.LOW) : "пропущено")
                    .append("; effort high — ").append(high.ok() ? client.effortParameter(CallOptions.Effort.HIGH) : "пропущено")
                    .append(". temperature не надсилається.\n\n");
            sb.append("## Як читати цей файл\n\n").append(Explain.LEGEND).append("\n\n");

            sb.append("## 1. Підсумок на всіх ").append(all.size()).append(" зверненнях\n\n");
            sb.append("Що це: ").append(Explain.WHAT_15).append("\n\n").append(table15(rows15)).append("\n\n");
            sb.append("Як читати:\n\n");
            Explain.HOW_15.forEach(l -> sb.append("- ").append(l).append("\n"));
            sb.append("\nПриклад: ").append(Explain.example15(rows15, noise)).append("\n\n");

            sb.append("## 2. Підсумок на ").append(HARD_N).append(" найважчих: міркування дешеве чи дороге\n\n");
            sb.append("Що це: ").append(Explain.WHAT_5).append("\n\n").append(table5(rows5)).append("\n\n");
            sb.append("Як читати:\n\n");
            Explain.HOW_5.forEach(l -> sb.append("- ").append(l).append("\n"));
            sb.append("\nПриклад: ").append(Explain.example5(rows5, noise5)).append("\n\n");

            sb.append("## 3. Кожне звернення на всіх ").append(all.size()).append("\n\n");
            sb.append("Що це: відповідь моделі на кожне звернення в кожному прогоні кроків 1–3. Питання: де саме модель помиляється і чи"
                    + " однакові ці помилки в однакових прогонах.\n\n");
            sb.append("| № | Пастка | Очікувано | zero s1 | zero s2 | few-shot | ваша ітерація |\n|---|---|---|---|---|---|---|\n");
            for (int i = 0; i < all.size(); i++) {
                Variant.Inquiry q = all.get(i);
                sb.append("| ").append(q.shortId()).append(" | ").append(trapName(q)).append(" | ").append(expectedText(q)).append(" | ")
                        .append(cell(z1.get(i))).append(" | ").append(cell(z2.get(i))).append(" | ").append(cell(few.get(i))).append(" | ")
                        .append(mine == null ? "—" : cell(mine.get(i))).append(" |\n");
            }
            sb.append("\nЯк читати: «Пастка» — тип навмисної складності (порожньо — звичайне звернення); «ok» — усі три мітки збіглися,"
                    + " інакше — що відповіла модель; «—» у «ваша ітерація» — крок 3 ще не виконано.\n\n");
            if (!tweaks.isEmpty()) {
                sb.append("Ваша правка (MyPromptTweaks): ");
                if (!tweaks.rule().isEmpty()) sb.append("правило «").append(tweaks.rule()).append("»; ");
                for (Variant.Example e : tweaks.examples()) sb.append("приклад «").append(e.text()).append("» → ").append(compact(e.labels())).append("; ");
                sb.append("\n\n");
            }

            sb.append("## 4. ").append(HARD_N).append(" найважчих: кожне звернення\n\n");
            sb.append("Що це: чому обрано кожне з 5 звернень і що відповіли всі способи кроків 4–5. Питання: які саме звернення виправляє"
                    + " міркування, а які — ні.\n\n");
            sb.append("| № | Чому обрано | Очікувано | база (s1) | s2 | s3 | згода | +reasoning | effort low | effort high |\n|---|---|---|---|---|---|---|---|---|---|\n");
            for (int i = 0; i < picks.size(); i++) {
                Metrics.Pick p = picks.get(i);
                sb.append("| ").append(p.inquiry().shortId()).append(" | ").append(p.reason()).append(" | ").append(expectedText(p.inquiry())).append(" | ")
                        .append(cell(samples.get(0).get(i))).append(" | ").append(cell(samples.get(1).get(i))).append(" | ").append(cell(samples.get(2).get(i)))
                        .append(" | ").append(votes.get(i).agreementText()).append(" | ").append(cell(rsn.get(i))).append(" | ")
                        .append(low.ok() ? cell(low.answers().get(i)) : "пропущено").append(" | ")
                        .append(high.ok() ? cell(high.answers().get(i)) : "пропущено").append(" |\n");
            }
            sb.append("\nЯк читати: «Чому обрано» — див. легенду (✗ zero s1 тощо); «згода 2/3» — дві з трьох однакових відповідей s1–s3"
                    + " збіглися (3/3 — усі однакові, 1/3 — усі різні); решта колонок — «ok» або відповідь моделі.\n\n");

            sb.append("## 5. Поріг «авто / людина»\n\n");
            sb.append("Що це: ").append(Explain.WHAT_ROUTING).append("\n\n");
            sb.append("| Поріг | Людині | Точність авто | Помилок спіймано | Пропущено | з них needs_human=true |\n|---|---|---|---|---|---|\n");
            for (Metrics.Routing r : routings) {
                sb.append("| ").append(r.threshold()).append("/").append(SAMPLES).append(" | ").append(r.humanShareText()).append(" | ").append(r.autoAccuracyText())
                        .append(" | ").append(r.caughtErrors()).append(" | ").append(r.missedErrors()).append(" | ").append(r.missedHuman()).append(" |\n");
            }
            sb.append("\nЯк читати:\n\n");
            Explain.HOW_ROUTING.forEach(l -> sb.append("- ").append(l).append("\n"));
            sb.append("\nПриклад: ").append(Explain.exampleRouting(routings)).append("\n");
            sb.append(client.isMock() ? "\nMOCK — імітація: цей файл до звіту не додається.\n" : "\nДо звіту додайте цей файл, target/lab3-cache.json і лог консолі.\n");
            return sb.toString();
        }
    }

    private static void writeResults(Path p, String md) {
        if (p == null) return;
        try {
            Path parent = p.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(p, md, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("  [не вдалося записати " + p + ": " + e.getMessage() + "]");
        }
    }
}
