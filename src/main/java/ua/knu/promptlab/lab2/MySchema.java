package ua.knu.promptlab.lab2;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;

/**
 * Лабораторна 2, розділ 7 звіту (домашнє завдання з лекції 3) — <b>ВАШ ФАЙЛ</b>: JSON-схема для власної задачі.
 *
 * <p>Що зробити:
 * <ol>
 *   <li>Замініть приклад нижче (відгук студента про курс) на <b>свою задачу</b>: TASK, SYSTEM, SCHEMA.
 *       У схемі мають бути хоча б одне поле з {@code enum} і масив з {@code maxItems}.</li>
 *   <li>Впишіть три входи (INPUTS) — тексти, на яких перевірите схему; хоча б один — незручний (двозначний, дуже довгий, не на тему).</li>
 *   <li>Допишіть у {@link #validate(JsonNode)} <b>три правила змісту</b>, яких схема не перевірить (зв'язок полів, слова в тексті,
 *       довжина у словах тощо). Кожне правило повертає одне речення-причину — як у TicketValidator.validate().</li>
 *   <li>Запустіть конфігурацію «Лаба 2 · 8 My schema» (3 запити) і перенесіть у звіт результат кожного входу.
 *       Без ключа перевірити запуск можна конфігурацією «Лаба 2 · 8 My schema (MOCK)».</li>
 * </ol>
 *
 * <p>Цей файл викладач після публікації лаби не змінює: правте його спокійно, Git → Pull пройде без конфліктів.
 */
public final class MySchema {

    private MySchema() {
    }

    /** Задача одним реченням (консоль друкує її першим рядком). */
    static final String TASK = "Розібрати відгук студента про навчальний курс: тон, теми, коротке резюме.";

    /** Системний промпт для вашої задачі. */
    static final String SYSTEM = "Ти аналізуєш відгуки студентів про навчальний курс університету. "
            + "Визнач тон відгуку, до трьох тем, яких він стосується, і напиши резюме одним реченням українською.";

    /** JSON Schema (як у TicketSchema): enum, масив з maxItems, required. Перевірте, що це валідний JSON — консоль скаже, якщо ні. */
    static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "tone": {"type": "string", "enum": ["positive", "neutral", "negative", "mixed"],
                         "description": "загальний тон відгуку"},
                "topics": {"type": "array", "minItems": 1, "maxItems": 3,
                           "items": {"type": "string", "enum": ["lectures", "labs", "grading", "workload", "teacher", "materials", "other"]},
                           "description": "до трьох тем, яких стосується відгук"},
                "summary": {"type": "string", "description": "резюме відгуку одним реченням українською"}
              },
              "required": ["tone", "topics", "summary"]
            }""";

    /** Три входи для перевірки (вигадані). */
    static final List<String> INPUTS = List.of(
            "Лекції цікаві, але лабораторні забирають по шість годин на тиждень, а бали за них ставлять із запізненням.",
            "Все норм.",
            "Викладач пояснює чудово, матеріали в Classroom зручні. Єдине — хотілося б більше прикладів на Java.");

    /**
     * Правила змісту, яких схема не виразить. Повертає речення-причину для моделі або Optional.empty(), якщо все гаразд.
     * Нижче — одне правило-приклад; замініть або доповніть до ТРЬОХ власних.
     */
    static Optional<String> validate(JsonNode answer) {
        String summary = answer.path("summary").asText("");
        int words = summary.isBlank() ? 0 : summary.strip().split("\\s+").length;
        // Правило 1 (приклад): резюме — одне речення, не довше 25 слів
        if (words > 25) {
            return Optional.of("поле summary задовге (" + words + " слів), потрібно не більше 25");
        }
        // Правило 2: ...
        // Правило 3: ...
        return Optional.empty();
    }
}
