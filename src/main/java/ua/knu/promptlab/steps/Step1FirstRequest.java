package ua.knu.promptlab.steps;

import com.fasterxml.jackson.databind.node.ObjectNode;
import ua.knu.promptlab.Console;
import ua.knu.promptlab.LlmClient;
import ua.knu.promptlab.LlmResponse;

/** Крок 1. Перший запит: що відправляємо, що повертається, які службові поля є у відповіді. */
public final class Step1FirstRequest {

    static final int MAX_TOKENS = 2048;

    private Step1FirstRequest() {
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
        Console.banner("Крок 1. Перший запит до моделі (" + client.provider() + ", " + client.model() + ")");

        String system = variant.step1().system(); // тексти запиту — з даних варіанта (lab1/variants/V0N.json)
        String user = variant.step1().user();

        // 2048, а не 300: у Gemini «міркування» (thinking) входить у той самий ліміт вихідних токенів
        ObjectNode request = client.buildRequest(system, user, null, MAX_TOKENS);
        Console.section("Що ми відправляємо: " + client.endpointDescription());
        System.out.println(Console.indent(Console.pretty(request)));

        LlmResponse r = client.ask(system, user, null, MAX_TOKENS);

        Console.section("Відповідь моделі");
        System.out.println(Console.indent(r.text()));

        Console.section("Службові поля відповіді");
        Console.kv("model", r.model());
        Console.kv("причина зупинки", r.stopReason() + "   (у відповіді API: " + r.rawStopReason() + ")");
        Console.kv("input токенів", r.inputTokens());
        Console.kv("output токенів", r.outputTokens()
                + (r.thinkingTokens() > 0 ? "   (з них «міркування»: " + r.thinkingTokens() + ")" : ""));
        Console.kv("затримка", r.latencyMs() + " мс");

        Console.discuss(
                "Де в запиті system prompt, а де повідомлення користувача? Чим вони відрізняються для моделі?",
                "Чому у відповіді є лічильник токенів? Для чого він потрібен розробнику?",
                "Що станеться, якщо не задати ліміт вихідних токенів? Чим це ризиковано для вартості й часу відповіді?");
    }
}
