package ua.knu.promptlab;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Розібрана відповідь моделі, однакова для всіх провайдерів.
 *
 * <ul>
 *   <li>{@code stopReason} — нормалізований: end_turn | max_tokens | інше;</li>
 *   <li>{@code rawStopReason} — як воно називається в самому API (Claude: stop_reason, Gemini: finishReason);</li>
 *   <li>{@code outputTokens} — усе, що модель згенерувала й за що беруть гроші як за вихідні,
 *       ВКЛЮЧНО з токенами «міркування» (Gemini);</li>
 *   <li>{@code thinkingTokens} — скільки з outputTokens припало на міркування (інформаційно);</li>
 *   <li>{@code inputTokens} — без токенів, прочитаних із кешу: вони рахуються окремо.</li>
 * </ul>
 */
public record LlmResponse(
        String id,
        String model,
        String text,
        String stopReason,
        String rawStopReason,
        int inputTokens,
        int outputTokens,
        int thinkingTokens,
        int cacheReadTokens,
        int cacheCreationTokens,
        long latencyMs,
        JsonNode raw) {

    public int totalTokens() {
        return inputTokens + outputTokens + cacheReadTokens + cacheCreationTokens;
    }
}
