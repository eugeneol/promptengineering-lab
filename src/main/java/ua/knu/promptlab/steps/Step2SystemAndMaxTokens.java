package ua.knu.promptlab.steps;

import ua.knu.promptlab.Console;
import ua.knu.promptlab.LlmClient;
import ua.knu.promptlab.LlmResponse;

/** Крок 2. Роль system prompt і ліміт вихідних токенів (обрізання відповіді). */
public final class Step2SystemAndMaxTokens {

    private Step2SystemAndMaxTokens() {
    }

    public static void main(String[] args) {
        Console.init();
        Lab1Variant variant = Lab1Variant.currentOrExit();
        try (LlmClient client = LlmClient.create()) {
            run(client, variant);
        }
    }

    /** Крок для варіанта з LAB_VARIANT (не задано — варіант 1). */
    public static void run(LlmClient client) {
        run(client, Lab1Variant.current());
    }

    public static void run(LlmClient client, Lab1Variant variant) {
        Console.banner("Крок 2. System prompt і ліміт вихідних токенів");

        String user = variant.step2().user(); // з даних варіанта (lab1/variants/V0N.json)

        Console.section("2а. Той самий запит із різними system prompt");
        // 2048: у 2а ліміт не має заважати (у Gemini «міркування» входить у ліміт); малий ліміт — лише у 2б
        LlmResponse plain = client.ask(null, user, null, 2048);
        System.out.println("  [без system]");
        System.out.println(Console.indent(plain.text()));
        printTokens(plain);

        LlmResponse pirate = client.ask("Ти піратський капітан. Відповідай у піратському стилі.", user, null, 2048);
        System.out.println("\n  [system = «піратський капітан»]");
        System.out.println(Console.indent(pirate.text()));
        printTokens(pirate);
        System.out.println("  Порівняйте input: system prompt теж тарифікується — він іде в кожному запиті.");

        Console.section("2б. Ліміт вихідних токенів = 25: відповідь обривається (малий ліміт — навмисно)");
        LlmResponse cut = client.ask(null, user, null, 25);
        if (cut.text().isBlank()) {
            System.out.println("    (текст порожній!)");
        } else {
            System.out.println(Console.indent(cut.text()));
        }
        Console.kv("причина зупинки", cut.stopReason() + "   (у відповіді API: " + cut.rawStopReason() + ")");
        Console.kv("output токенів", cut.outputTokens());
        Console.kv("з них «міркування»", cut.thinkingTokens());
        if (cut.thinkingTokens() > 0) {
            System.out.println("  Увага: частину ліміту з'їло «міркування» моделі — вона платить токенами ще до того,");
            System.out.println("  як почне писати відповідь. Через це при малому ліміті текст може бути коротким або порожнім.");
        }

        Console.discuss(
                "Чи платимо ми за відповідь, яку обрізали? (так — за кожен згенерований токен, включно з «міркуванням»)",
                "Як у коді відрізнити «закінчила сама» від «обрізали»? Чому це важливо для JSON-відповідей?",
                "Чи лежить system prompt у «пам'яті» моделі між запитами? (ні — він іде у кожному запиті й тарифікується)");
    }

    private static void printTokens(LlmResponse r) {
        Console.kv("input / output токенів", r.inputTokens() + " / " + r.outputTokens()
                + (r.thinkingTokens() > 0 ? "   (з них «міркування»: " + r.thinkingTokens() + ")" : ""));
        Console.kv("причина зупинки", r.stopReason() + "   (у відповіді API: " + r.rawStopReason() + ")");
    }
}
