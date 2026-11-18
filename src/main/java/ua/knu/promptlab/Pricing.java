package ua.knu.promptlab;

import java.util.Optional;

/**
 * Ціни за 1 млн токенів, USD. Від'ємне значення = «невідомо / не задано».
 *
 * Джерела (текст, стандартний платний тариф):
 *  - Claude: https://platform.claude.com/docs/en/about-claude/pricing  (перевірено 2026-10-07)
 *  - Gemini: https://ai.google.dev/gemini-api/docs/pricing            (перевірено 2026-10-14)
 *
 * Ціни змінюються — перед заняттям звірте з офіційними сторінками.
 * Для Gemini ціна вихідних токенів включає токени «міркування».
 * Безкоштовний рівень Gemini означає фактичну вартість $0, але ці ціни показують,
 * скільки той самий запит коштував би на платному тарифі.
 */
public record Pricing(double inputPerMTok, double outputPerMTok, double cacheReadPerMTok, double cacheWritePerMTok) {

    private static final Pricing SONNET_5_5 = new Pricing(2.00, 10.00, 0.20, 2.50);
    private static final Pricing HAIKU_4_5 = new Pricing(1.00, 5.00, 0.10, 1.25);
    private static final Pricing GEMINI_3_5_FLASH_LITE = new Pricing(0.30, 2.50, -1, -1);
    private static final Pricing GEMINI_3_1_FLASH_LITE = new Pricing(0.25, 1.50, -1, -1);

    public static Optional<Pricing> forModel(String model) {
        if (model == null) return Optional.empty();
        if (model.startsWith("claude-sonnet-5-5")) return Optional.of(SONNET_5_5);
        if (model.startsWith("claude-haiku-4-5")) return Optional.of(HAIKU_4_5);
        if (model.startsWith("gemini-3.5-flash-lite")) return Optional.of(GEMINI_3_5_FLASH_LITE);
        if (model.startsWith("gemini-3.1-flash-lite")) return Optional.of(GEMINI_3_1_FLASH_LITE);
        return Optional.empty();
    }

    public boolean hasCachePrices() {
        return cacheReadPerMTok >= 0 && cacheWritePerMTok >= 0;
    }

    /** Вартість одного запиту у доларах. */
    public double cost(LlmResponse r) {
        return cost(r.inputTokens(), r.outputTokens(), r.cacheReadTokens(), r.cacheCreationTokens());
    }

    public double cost(long input, long output, long cacheRead, long cacheWrite) {
        return (input * inputPerMTok
                + output * outputPerMTok
                + cacheRead * Math.max(0, cacheReadPerMTok)
                + cacheWrite * Math.max(0, cacheWritePerMTok)) / 1_000_000.0;
    }

    public double inputCost(long tokens) {
        return tokens * inputPerMTok / 1_000_000.0;
    }

    /** У скільки разів вихідний токен дорожчий за вхідний. */
    public double outputToInputRatio() {
        return outputPerMTok / inputPerMTok;
    }
}
