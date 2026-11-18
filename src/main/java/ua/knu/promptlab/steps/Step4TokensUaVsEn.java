package ua.knu.promptlab.steps;

import ua.knu.promptlab.Console;
import ua.knu.promptlab.LlmClient;
import ua.knu.promptlab.Pricing;

import java.util.LinkedHashMap;
import java.util.Map;

/** Крок 4. Один і той самий зміст у різних мовах → різна кількість токенів → різна ціна. */
public final class Step4TokensUaVsEn {

    private Step4TokensUaVsEn() {
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
        Console.banner("Крок 4. Токени: українська vs англійська");

        Map<String, String> samples = new LinkedHashMap<>();
        // EN і UA — той самий зміст двома мовами, свій для кожного варіанта (lab1/variants/V0N.json → step4)
        samples.put("EN", variant.step4().en());
        samples.put("UA", variant.step4().ua());
        samples.put("CODE", "for (int i = 0; i < 10; i++) { System.out.println(\"i = \" + i); }");
        samples.put("NUM", "3141592653589793238462643383279");

        Pricing price = Pricing.forModel(client.model()).orElse(null);
        System.out.printf("  %-6s %8s %8s %12s %12s%n", "текст", "символів", "токенів", "симв./токен", "× до EN");

        int enTokens = 0;
        int uaTokens = 0;
        for (var e : samples.entrySet()) {
            String text = e.getValue();
            int tokens = client.countTokens(null, text);
            if (e.getKey().equals("EN")) enTokens = tokens;
            if (e.getKey().equals("UA")) uaTokens = tokens;
            double ratio = enTokens == 0 ? 1.0 : (double) tokens / enTokens;
            String ratioText = e.getKey().equals("EN") ? "—" : String.format("%.2f×", ratio);
            System.out.printf("  %-6s %8d %8d %12.2f %12s%n",
                    e.getKey(), text.length(), tokens, (double) text.length() / tokens, ratioText);
        }

        System.out.println("\n  (у число можуть входити службові токени; порівнюйте співвідношення, а не абсолютні числа)");

        if (price != null) {
            Console.section("Скільки коштує надіслати цей абзац 1 000 000 разів (лише input"
                    + (client.hasFreeTier() ? ", еквівалент за платним тарифом" : "") + ")");
            Console.kv("EN", Console.usd(price.inputCost((long) enTokens * 1_000_000)));
            Console.kv("UA", Console.usd(price.inputCost((long) uaTokens * 1_000_000)));
        }

        Console.discuss(
                "Чому українська дорожча? (токенізатор навчали переважно на англійських текстах)",
                "Чи варто писати промпти англійською, щоб заощадити? Що ми можемо втратити?",
                "Чому числа й код токенізуються «по-своєму»? Як це пов'язано з помилками в арифметиці?");
    }
}
