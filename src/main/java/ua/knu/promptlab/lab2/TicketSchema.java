package ua.knu.promptlab.lab2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON Schema для {@link Ticket} — те, що йде в запит до моделі.
 *
 * Gemini: generationConfig.responseMimeType = application/json + responseJsonSchema.
 * Claude: output_config.format = {type: json_schema, schema}.
 *
 * Провайдери підтримують лише підмножину JSON Schema, і по-різному реагують на решту (станом на 11.2026 — звіряйте документацію):
 * <ul>
 *   <li><b>Gemini</b> приймає type, properties, required, enum, items, minItems, maxItems, minimum, maximum,
 *       additionalProperties, anyOf; ключі на кшталт minLength чи pattern може ІГНОРУВАТИ мовчки;</li>
 *   <li><b>Claude</b> частину ключів ВІДХИЛЯЄ з HTTP 400 (maxItems, minItems &gt; 1, minimum/maximum, minLength/maxLength),
 *       тому {@code ClaudeClient.toClaudeSchema} прибирає їх перед відправкою.</li>
 * </ul>
 * В обох випадках такі правила живуть у {@link TicketValidator#validate(Ticket)} — це рівень 2, а не 1.
 */
public final class TicketSchema {

    private static final ObjectMapper JSON = new ObjectMapper();

    private TicketSchema() {
    }

    public static JsonNode json() {
        ObjectNode root = JSON.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");

        enumString(props.putObject("category"), Ticket.CATEGORIES, "Тип звернення");
        enumString(props.putObject("priority"), Ticket.PRIORITIES, "p0 — клієнт заблокований і час критичний; p3 — побажання");
        enumString(props.putObject("sentiment"), Ticket.SENTIMENTS, "Емоційний тон клієнта");
        props.putObject("summary").put("type", "string").put("description", "Одне речення українською для менеджера");
        ObjectNode steps = props.putObject("steps");
        steps.put("type", "array").put("minItems", 1).put("maxItems", 5).put("description", "Від 1 до 5 кроків для інженера підтримки");
        steps.putObject("items").put("type", "string");

        ArrayNode required = root.putArray("required");
        for (String f : new String[]{"category", "priority", "sentiment", "summary", "steps"}) required.add(f);
        root.put("additionalProperties", false); // обов'язково для Claude; Gemini теж приймає
        return root;
    }

    private static void enumString(ObjectNode node, Iterable<String> values, String description) {
        node.put("type", "string").put("description", description);
        ArrayNode e = node.putArray("enum");
        values.forEach(e::add);
    }

    /** Той самий зміст словами — для кроку 1, де схеми в запиті ще немає (рівень 3). */
    public static String asPromptInstructions() {
        return """
                Поверни тікет у форматі JSON з полями:
                  category: один з login, billing, bug, feature_request, other
                  priority: один з p0, p1, p2, p3 (p0 — клієнт заблокований і час критичний; p3 — побажання)
                  sentiment: один з calm, frustrated, angry
                  summary: одне речення українською для менеджера
                  steps: масив з 1–5 рядків — кроки для інженера підтримки
                Правила:
                  * Повертай лише валідний JSON.
                  * Ніколи не додавай символи ` (бектики) і текст навколо JSON.
                  * Відповідь буде розібрана програмою (Jackson), тому вона мусить бути валідним JSON.""";
    }
}
