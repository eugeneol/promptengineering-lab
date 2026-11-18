package ua.knu.promptlab;

/**
 * Лабораторна 3. Параметри одного запиту, яких немає в askJson лабораторної 2: рівень міркування (effort),
 * поле reasoning у схемі відповіді і (необов'язково) температура.
 *
 * <p>{@link Effort} — наш, «провайдеро-незалежний» рівень міркування; клієнт сам перекладає його в параметр свого API
 * (станом на 11.2026 — звіряйте документацію):
 * <ul>
 *   <li><b>Gemini 3+</b>: {@code generationConfig.thinkingConfig.thinkingLevel} = minimal / low / medium / high;</li>
 *   <li><b>Gemini 2.x</b> (або якщо задано LAB_THINKING_BUDGET): {@code thinkingBudget} у токенах (MINIMAL → 0);</li>
 *   <li><b>Claude 5.x</b>: {@code thinking: {type: "adaptive"}} + {@code output_config.effort} = low / medium / high;
 *       MINIMAL — найнижче, що дозволяє API: {@code thinking: {type: "between_tools"}} + effort low;</li>
 *   <li><b>Claude 4.x</b> (effort не приймає → 400, клієнт переходить сам): {@code thinking: {type: "enabled", budget_tokens: N}};
 *       MINIMAL — без thinking.</li>
 * </ul>
 *
 * <p><b>Температура</b> необов'язкова: null → значення моделі за замовчуванням (так у лабораторній 3 скрізь). Новіші моделі
 * (наприклад, Claude Sonnet 5.5) відповідають 400 на будь-яку temperature — тоді клієнт повторює запит без неї.
 *
 * @param temperature    null → значення моделі за замовчуванням (параметр не надсилається)
 * @param effort         DEFAULT → як у лабораторних 1–2 (лише LAB_THINKING_LEVEL з .env, якщо задано)
 * @param reasoningField true → у схемі відповіді є поле reasoning ПЕРШИМ (модель спершу пише міркування, потім мітки)
 */
public record CallOptions(Double temperature, Effort effort, boolean reasoningField) {

    /** Рівень міркування (effort — «скільки зусиль» модель вкладає в міркування перед відповіддю). */
    public enum Effort {
        /** Як у лабораторних 1–2: параметр міркування не надсилається. */
        DEFAULT("як у моделі за замовчуванням"),
        /** Найнижчий рівень, який дозволяє модель (не обов'язково нуль токенів міркування). */
        MINIMAL("мінімальне міркування"),
        LOW("effort low"),
        MEDIUM("effort medium"),
        HIGH("effort high");

        public final String title;

        Effort(String title) {
            this.title = title;
        }

        /** true для LOW / MEDIUM / HIGH — тобто міркування увімкнене явно. */
        public boolean thinks() {
            return this == LOW || this == MEDIUM || this == HIGH;
        }

        /** Назва рівня в API (low / medium / high; MINIMAL → minimal). */
        public String apiName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public static final CallOptions DEFAULT = new CallOptions(null, Effort.DEFAULT, false);

    public CallOptions {
        if (effort == null) effort = Effort.DEFAULT;
    }

    /** Без температури і без поля reasoning — лише рівень міркування. */
    public static CallOptions of(Effort effort) {
        return new CallOptions(null, effort, false);
    }

    public CallOptions withEffort(Effort e) {
        return new CallOptions(temperature, e, reasoningField);
    }

    public CallOptions withReasoningField(boolean r) {
        return new CallOptions(temperature, effort, r);
    }

    public CallOptions withTemperature(Double t) {
        return new CallOptions(t, effort, reasoningField);
    }
}
