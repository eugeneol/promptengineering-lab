package ua.knu.promptlab.steps;

import ua.knu.promptlab.Console;
import ua.knu.promptlab.LlmClient;
import ua.knu.promptlab.LlmResponse;
import ua.knu.promptlab.Pricing;

/** Крок 3. Токеноміка: вартість одного запиту, різниця input/output та «ефект агента». */
public final class Step3UsageAndCost {

    private Step3UsageAndCost() {
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
        Console.banner("Крок 3. Токени → гроші");

        Pricing price = Pricing.forModel(client.model()).orElse(null);
        if (price == null) {
            System.out.println("  Для моделі " + client.model() + " у Pricing.java немає цін — додайте їх.");
            return;
        }

        LlmResponse r = client.ask(variant.step3().system(), variant.step3().user(), // з даних варіанта
                0.8, 2048); // 2048: «міркування» Gemini входить у ліміт вихідних токенів

        Console.section("Реальний запит");
        Console.kv("response", r.text());
        Console.kv("input токенів", r.inputTokens());
        Console.kv("output токенів", r.outputTokens()
                + (r.thinkingTokens() > 0 ? "   (з них «міркування»: " + r.thinkingTokens() + ", вони тарифікуються як output)" : ""));
        Console.kv("ціна input / 1M", "$" + price.inputPerMTok());
        Console.kv("ціна output / 1M", "$" + price.outputPerMTok() + "  (у " + String.format("%.1f", price.outputToInputRatio()) + "× дорожче)");
        double cost = price.cost(r);
        if (client.hasFreeTier()) {
            System.out.println("  Безкоштовний рівень: реальна вартість $0. Нижче — ЕКВІВАЛЕНТ за платним тарифом,");
            System.out.println("  щоб навчитися рахувати, скільки цей запит коштував би в продакшні.");
        }
        Console.kv("вартість запиту", Console.usd(cost));
        Console.kv("× 1000 запитів", Console.usd(cost * 1000));

        Console.section("Модельний розрахунок: агент на 10 кроків (це НЕ реальні виклики)");
        System.out.println("  Припущення: стартовий контекст 2000 токенів (system + опис інструментів),");
        System.out.println("  на кожному кроці модель генерує 300 токенів і інструмент повертає 500 токенів.");
        System.out.println("  Увесь накопичений контекст надсилається знову на КОЖНОМУ кроці.\n");
        simulateAgent(price, 2000, 300, 500, 10);

        Console.discuss(
                "Чому output дорожчий за input? (підказка: токени генеруються по одному, а input обробляється паралельно)",
                "Чому вартість агента росте швидше, ніж лінійно з кількістю кроків?",
                "Що дає prompt caching і коли він НЕ допоможе?");
    }

    private static void simulateAgent(Pricing p, int base, int out, int tool, int steps) {
        long inputTotal = 0;
        long outputTotal = 0;
        // з кешуванням: усе, що вже було у попередньому запиті, читаємо з кешу, нове — пишемо в кеш
        long cachedRead = 0;
        long cachedWrite = 0;
        long context = base;

        System.out.printf("  %-6s %-16s %-16s%n", "крок", "input (контекст)", "накопичено input");
        for (int i = 1; i <= steps; i++) {
            inputTotal += context;
            outputTotal += out;
            if (i == 1) {
                cachedWrite += context;
            } else {
                long previous = context - (out + tool);
                cachedRead += previous;
                cachedWrite += (out + tool);
            }
            System.out.printf("  %-6d %-16d %-16d%n", i, context, inputTotal);
            context += out + tool;
        }

        System.out.println();
        Console.kv("усього input токенів", inputTotal + "  (у " + (inputTotal / base) + "× більше за стартовий контекст)");
        Console.kv("усього output токенів", outputTotal);
        Console.kv("вартість без кешу", Console.usd(p.cost(inputTotal, outputTotal, 0, 0)));
        if (p.hasCachePrices()) {
            Console.kv("вартість з кешем", Console.usd(p.cost(0, outputTotal, cachedRead, cachedWrite)));
        } else {
            Console.kv("вартість з кешем", "— (для цієї моделі ціни кешу не задані в Pricing.java)");
        }
    }
}
