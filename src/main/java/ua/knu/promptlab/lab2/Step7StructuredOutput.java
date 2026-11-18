package ua.knu.promptlab.lab2;

import ua.knu.promptlab.ClaudeClient;
import ua.knu.promptlab.Config;
import ua.knu.promptlab.Console;
import ua.knu.promptlab.GeminiClient;
import ua.knu.promptlab.LabVariant;
import ua.knu.promptlab.LlmClient;
import ua.knu.promptlab.LlmResponse;
import ua.knu.promptlab.Pricing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Лабораторна 2. Структурований вивід і валідація — шість кроків, як у лекції 3.
 *
 *  7.1 JSON лише промптом (рівень 3): скільки відповідей розбираються з першого разу.
 *  7.2 Схема у запиті (рівень 1): те саме зі схемою — порівняння.
 *  7.3 Ворота валідації (рівень 2): форма → нормалізація → зміст; що саме ловить кожен шар.
 *  7.4 Повтор із реченням-причиною на всіх 10 кейсах; власні p, v і p_eff.
 *  7.5 Пастка обрізання: маленький maxOutputTokens → finishReason.
 *  7.6 Перший eval: PASS/FAIL за очікуваним пріоритетом і категорією.
 *
 * Запити до API: 5 + 5 + ~12 + 1 ≈ 25 на один прогін; Step7 завжди проганяє всі шість кроків.
 * На безкоштовному рівні (≈15 запитів/хв) тримайте у .env LAB_DELAY_MS=4000. Для налагодження без ключа — LAB_MOCK=1.
 *
 * Дані — з вашого варіанта ({@link Lab2Variant}, LAB_VARIANT у .env): домен і системний промпт, 10 повідомлень з очікуваннями
 * і обов'язкове правило для validate(). Без LAB_VARIANT — варіант 1 (= {@link TicketData}, дані до появи варіантів).
 */
public final class Step7StructuredOutput {

    private static final int SAMPLE = 5;      // кейсів у кроках 7.1–7.3 (економимо квоту)
    private static final int MAX_TOKENS = 2048; // з запасом: «міркування» Gemini входить у той самий ліміт
    private static final int MAX_RETRIES = 3;

    private Step7StructuredOutput() {
    }

    public static void main(String[] args) {
        Console.init();
        Lab2Variant variant = Lab2Variant.currentOrExit(); // LAB_VARIANT з .env; не задано — варіант 1
        try (LlmClient client = LlmClient.create()) {
            run(client, variant);
        }
    }

    /** Усі кроки для варіанта з LAB_VARIANT (не задано — варіант 1). */
    public static void run(LlmClient client) {
        run(client, Lab2Variant.current());
    }

    public static void run(LlmClient client, Lab2Variant variant) {
        Console.banner("Лабораторна 2. Структурований вивід і валідація (" + client.provider() + ", " + client.model() + ")");
        if (LabVariant.isSet(Config.setting("LAB_VARIANT"))) { // без LAB_VARIANT — вивід як до появи варіантів (+ одне речення-підказка)
            System.out.println("  Варіант " + variant.variant() + ": " + variant.title() + " «" + variant.product() + "»");
            System.out.println("  Обов'язкове правило вашого варіанта для validate(): " + variant.rule().text());
        }
        // варіант 1 — TicketData.SYSTEM (той самий текст, що у V01.json; так спрацює й опційна правка [S] у TicketData з кроку 6).
        // Інші варіанти — поле "system" у src/main/resources/lab2/variants/V0N.json.
        final String system = variant.variant() == 1 ? TicketData.SYSTEM : variant.system();
        final List<TicketData.Case> cases = variant.ticketCases();
        List<TicketData.Case> sample = cases.subList(0, SAMPLE);

        // ---------------------------------------------------------------- 7.1
        Console.section("7.1 JSON лише промптом (рівень 3): " + SAMPLE + " повідомлень");
        String promptOnlySystem = system + "\n\n" + TicketSchema.asPromptInstructions();
        int strictOk = 0, lenientOk = 0, fenced = 0;
        for (TicketData.Case c : sample) {
            LlmResponse r = client.ask(promptOnlySystem, "Повідомлення клієнта:\n\"\"\"" + c.message() + "\"\"\"", null, MAX_TOKENS);
            boolean strict = parsesStrictly(r.text());
            TicketValidator.Result parsed = TicketValidator.parse(r.text());
            boolean lenient = parsed.ok();
            String body = r.text().strip();
            boolean wrapped = !body.startsWith("{") || !body.endsWith("}");
            strictOk += strict ? 1 : 0;
            lenientOk += lenient ? 1 : 0;
            fenced += wrapped ? 1 : 0;
            String label = strict ? "валідний JSON" : (lenient ? "JSON у обгортці" : "НЕ розібрано");
            System.out.printf("  кейс %2d: %-18s %s%n", c.id(), label, Console.clip(r.text(), 70));
            if (!lenient) System.out.println("           ↳ причина: " + parsed.error());
            pause(client);
        }
        System.out.println("  Три числа: (1) чистий JSON без обгортки; (2) розібрано після зняття обгортки — НАКОПИЧУВАЛЬНО,");
        System.out.println("  тобто (1) + ті, що мали обгортку; (3) скільки відповідей мали текст або ``` навколо JSON.");
        Console.kv("(1) валідний з першого разу", strictOk + " з " + SAMPLE);
        Console.kv("(2) після зняття обгортки", lenientOk + " з " + SAMPLE + "  (разом з (1))");
        Console.kv("(3) з обгорткою/текстом навколо", fenced + " з " + SAMPLE);

        // ---------------------------------------------------------------- 7.2
        Console.section("7.2 Схема у запиті (рівень 1): ті самі " + SAMPLE + " повідомлень");
        System.out.println("  Що ми відправляємо (generationConfig / output_config):");
        System.out.println(Console.indent(Console.pretty(client.buildJsonRequest(system, "…", TicketSchema.json(), MAX_TOKENS)
                .path(client.provider().equals("Gemini") ? "generationConfig" : "output_config"))));
        List<LlmResponse> schemaResponses = new ArrayList<>();
        int schemaOk = 0;
        for (TicketData.Case c : sample) {
            LlmResponse r = client.askJson(system, "Повідомлення клієнта:\n\"\"\"" + c.message() + "\"\"\"", TicketSchema.json(), MAX_TOKENS);
            schemaResponses.add(r);
            boolean strict = parsesStrictly(r.text());
            schemaOk += strict ? 1 : 0;
            System.out.printf("  кейс %2d: %-18s %s%n", c.id(), strict ? "валідний JSON" : "НЕ розібрано (" + r.rawStopReason() + ")",
                    Console.clip(r.text(), 70));
            pause(client);
        }
        Console.kv("розібрано з першого разу", schemaOk + " з " + SAMPLE);
        Console.kv("порівняйте з 7.1", strictOk + " з " + SAMPLE + " без схеми");
        if (client instanceof GeminiClient g) {
            Console.kv("поле схеми в запиті", g.schemaFieldInUse()
                    + (g.schemaFieldInUse().equals("responseJsonSchema") ? "" : "  (API відхилив responseJsonSchema — клієнт перейшов на старіше поле)"));
        } else if (client instanceof ClaudeClient) {
            System.out.println("  Claude: клієнт прибрав зі схеми ключі, яких structured outputs не приймає (maxItems, minimum/maximum,");
            System.out.println("  minLength/maxLength, minItems > 1) — див. ClaudeClient.toClaudeSchema. Ці правила перевіряє validate().");
        }

        // ---------------------------------------------------------------- 7.3
        Console.section("7.3 Ворота валідації (рівень 2): форма → нормалізація → зміст");
        int formFail = 0, normFail = 0, contentFail = 0;
        for (int i = 0; i < schemaResponses.size(); i++) {
            String text = schemaResponses.get(i).text();
            TicketValidator.Result p = TicketValidator.parse(text);
            String verdict;
            if (!p.ok()) { formFail++; verdict = "ФОРМА: " + p.error(); }
            else {
                TicketValidator.Result n = TicketValidator.normalize(p.ticket());
                if (!n.ok()) { normFail++; verdict = "МІТКИ: " + n.error(); }
                else {
                    var problem = TicketValidator.validate(n.ticket());
                    if (problem.isPresent()) { contentFail++; verdict = "ЗМІСТ: " + problem.get(); }
                    else verdict = "ok  → " + n.ticket().category() + " / " + n.ticket().priority() + " / " + n.ticket().sentiment()
                            + " / кроків: " + n.ticket().steps().size();
                }
            }
            System.out.printf("  кейс %2d: %s%n", sample.get(i).id(), verdict);
        }
        Console.kv("зловила форма / мітки / зміст", formFail + " / " + normFail + " / " + contentFail);
        System.out.println("  Схема гарантує форму; мітки й зміст — тільки ваш код. Додайте у TicketValidator.validate() обов'язкове правило");
        System.out.println("  вашого варіанта («" + variant.rule().text() + "») і три власні.");

        // ---------------------------------------------------------------- 7.4
        Console.section("7.4 Повтор із причиною на всіх " + cases.size() + " кейсах (maxRetries = " + MAX_RETRIES + ")");
        JsonGate gate = new JsonGate(client, MAX_RETRIES, MAX_TOKENS);
        List<JsonGate.Outcome> outcomes = new ArrayList<>();
        int firstTryOk = 0, fixedByRetry = 0, failed = 0, truncated = 0, totalIn = 0, totalOut = 0;
        System.out.printf("  %-8s %-17s %6s %6s  %s%n", "кейс", "результат", "in", "out", "тікет / причина");
        for (TicketData.Case c : cases) {
            JsonGate.Outcome o = gate.run(system, c.message());
            outcomes.add(o);
            totalIn += o.inputTokens();
            totalOut += o.outputTokens();
            if (o.ok() && o.attempts() == 1) firstTryOk++;
            else if (o.ok()) fixedByRetry++;
            else failed++;
            if (o.truncated()) truncated++;
            String status = o.ok() ? (o.attempts() == 1 ? "ok з 1 спроби" : "ok з " + o.attempts() + " спроб")
                    : (o.truncated() ? "ОБРІЗАНО (спроба " + o.attempts() + ")" : "ПРОВАЛ після " + o.attempts());
            System.out.printf("  кейс %2d: %-17s %6d %6d  %s%n", c.id(), status, o.inputTokens(), o.outputTokens(),
                    o.ok() ? o.ticket().category() + " / " + o.ticket().priority() : "причина: " + o.lastError());
            for (int k = 0; k < o.errors().size(); k++) {
                // причину повертаємо моделі лише якщо після неї був ще один запит
                boolean returned = o.ok() || k < o.errors().size() - 1 && !o.truncated();
                System.out.println("           ↳ спроба " + (k + 1) + (returned ? ", повернуто моделі: " : ", остаточна причина: ") + o.errors().get(k));
            }
            pause(client);
        }
        int n = cases.size();
        double p = (double) firstTryOk / n;
        int firstTryFail = n - firstTryOk;
        // обрізання — помилка конфігурації (ліміт токенів), повтор її не лікує, тому в знаменник v не входить
        int retryable = firstTryFail - truncated;
        Console.kv("з першого разу (p)", String.format(Locale.ROOT, "%d з %d = %.2f", firstTryOk, n, p));
        double pEff;
        if (retryable <= 0) {
            pEff = p;
            Console.kv("виправлено повтором (v)", firstTryFail == 0
                    ? "v не визначено (невдач з першої спроби не було); p_eff = p"
                    : "v не визначено (усі невдачі — обрізання лімітом, повтор не робився); p_eff = p");
        } else {
            double v = (double) fixedByRetry / retryable;
            pEff = p + (1 - p) * v;
            Console.kv("виправлено повтором (v)", String.format(Locale.ROOT, "%d з %d невдалих = %.2f", fixedByRetry, retryable, v));
        }
        Console.kv("обірвано лімітом токенів", truncated + (truncated == 0 ? "" : "  (не входять у знаменник v — це конфігурація, а не модель)"));
        Console.kv("провалено остаточно", failed + " з " + n + (truncated == 0 ? "" : "  (включно з обрізаними)"));
        Console.kv("p_eff = p + (1 − p)·v", String.format(Locale.ROOT, "%.3f", pEff));
        if (truncated > 0) System.out.println("  Увага: обрізані кейси повтор не лікує — спершу збільште MAX_TOKENS і перезапустіть, інакше p_eff завищене.");
        Console.kv("10 кроків без воріт / з воротами", String.format(Locale.ROOT, "%.1f%% / %.1f%%", Math.pow(p, 10) * 100, Math.pow(pEff, 10) * 100));
        Console.kv("токенів input / output", totalIn + " / " + totalOut);
        final int in = totalIn, out = totalOut;
        Pricing.forModel(client.model()).ifPresent(pr -> Console.kv("вартість за платним тарифом",
                Console.usd(pr.cost(in, out, 0, 0)) + (client.hasFreeTier() ? "  (на безкоштовному рівні — $0)" : "")));

        // ---------------------------------------------------------------- 7.5
        Console.section("7.5 Пастка обрізання: maxOutputTokens = 40");
        LlmResponse cut = client.askJson(system, "Повідомлення клієнта:\n\"\"\"" + cases.get(0).message() + "\"\"\"",
                TicketSchema.json(), 40);
        Console.kv("причина зупинки", cut.rawStopReason() + " (" + cut.stopReason() + ")");
        Console.kv("текст", Console.clip(cut.text(), 90));
        Console.kv("розбирається?", parsesStrictly(cut.text()) ? "так" : "ні — перевіряйте finishReason ДО парсингу");
        if (cut.thinkingTokens() > 0) Console.kv("токенів міркування", cut.thinkingTokens() + " — вони теж входять у ліміт");

        // ---------------------------------------------------------------- 7.6
        Console.section("7.6 Перший eval: очікуване vs отримане");
        int passPriority = 0, passCategory = 0;
        System.out.printf("  %-5s %-34s %-7s %-7s %s%n", "кейс", "категорія (очік.)", "пріор.", "(очік.)", "вердикт");
        for (int i = 0; i < n; i++) {
            TicketData.Case c = cases.get(i);
            JsonGate.Outcome o = outcomes.get(i);
            String cat = o.ok() ? o.ticket().category() : "—";
            String pr = o.ok() ? o.ticket().priority() : "—";
            boolean okCat = cat.equals(c.expectedCategory());
            boolean okPr = pr.equals(c.expectedPriority());
            passCategory += okCat ? 1 : 0;
            passPriority += okPr ? 1 : 0;
            System.out.printf("  %-5d %-34s %-7s %-7s %s%n", c.id(), cat + " (" + c.expectedCategory() + ")", pr, "(" + c.expectedPriority() + ")",
                    (okCat && okPr) ? "PASS" : "FAIL" + (okCat ? "" : " категорія") + (okPr ? "" : " пріоритет"));
        }
        Console.kv("категорія збіглась", passCategory + " з " + n);
        Console.kv("пріоритет збігся", passPriority + " з " + n);
        int validButWrong = 0;
        for (int i = 0; i < n; i++) {
            JsonGate.Outcome o = outcomes.get(i);
            if (o.ok() && !o.ticket().priority().equals(cases.get(i).expectedPriority())) validButWrong++;
        }
        System.out.println("  Пройшли ворота (валідний тікет): " + (n - failed) + " з " + n + "; з них з НЕправильним пріоритетом: " + validButWrong
                + ". Форму гарантує схема, правильність — тільки eval.");

        Console.discuss(
                "Скільки кейсів 7.1 провалились через обгортку, а скільки через справді зламаний JSON? Що з цього вилікувала схема?",
                "Чи спрацювало обов'язкове правило вашого варіанта? Яке з трьох ваших правил у validate() спрацювало хоч раз? Якщо жодне — чи потрібне воно?",
                "Кейси з FAIL у 7.6: модель помилилась чи ваш очікуваний пріоритет спірний? Як би ви уточнили системний промпт?");
    }

    /**
     * Строгий розбір: без зняття обгортки, текст після JSON — помилка, усі п'ять полів обов'язкові —
     * ті самі правила, що й у TicketValidator.parse, лише без зняття обгортки. Так чесно рахуємо збої рівня 3.
     */
    static boolean parsesStrictly(String text) {
        return TicketValidator.parseExact(text).ok();
    }

    private static void pause(LlmClient client) {
        long ms = client.requestDelayMs();
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
