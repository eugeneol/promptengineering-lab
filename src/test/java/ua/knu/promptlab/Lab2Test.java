package ua.knu.promptlab;

import org.junit.jupiter.api.Test;
import ua.knu.promptlab.lab2.Ticket;
import ua.knu.promptlab.lab2.TicketSchema;
import ua.knu.promptlab.lab2.TicketValidator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Лабораторна 2: ворота валідації тестуються без мережі, на «підроблених» відповідях —
 * так само, як validate_response у книжці Phoenix і Taylor.
 * СТУДЕНТАМ: додайте по тесту на кожне своє правило у TicketValidator.validate().
 */
class Lab2Test {

    private static final String GOOD = """
            {"category":"login","priority":"p0","sentiment":"angry",
             "summary":"Клієнт не може увійти перед демо","steps":["скинути сесію","перевірити логи"]}""";

    @Test
    void goodTicketPasses() {
        TicketValidator.Result r = TicketValidator.check(GOOD);
        assertTrue(r.ok(), r.error());
        assertEquals("login", r.ticket().category());
        assertEquals(2, r.ticket().steps().size());
    }

    @Test
    void markdownFencesAreStripped() {
        String fenced = "Ось ваш JSON:\n```json\n" + GOOD + "\n```";
        assertTrue(TicketValidator.parse(fenced).ok());
    }

    @Test
    void brokenJsonIsReportedAsForm() {
        TicketValidator.Result r = TicketValidator.check("{\"category\": \"login\", \"priority\":}");
        assertFalse(r.ok());
        assertTrue(r.error().contains("валідним JSON"), r.error());
    }

    @Test
    void emptyResponseIsReported() {
        assertFalse(TicketValidator.check("   ").ok());
    }

    @Test
    void priorityDriftIsNormalized() {
        Ticket t = new Ticket("authentication", "critical", "ANGRY", "x", List.of("a"));
        TicketValidator.Result r = TicketValidator.normalize(t);
        assertTrue(r.ok(), r.error());
        assertEquals("p0", r.ticket().priority());
        assertEquals("login", r.ticket().category());
        assertEquals("angry", r.ticket().sentiment());
    }

    @Test
    void unknownPriorityIsStrict() {
        Ticket t = new Ticket("bug", "p9", "calm", "x", List.of("a"));
        TicketValidator.Result r = TicketValidator.normalize(t);
        assertFalse(r.ok());
        assertTrue(r.error().contains("priority"));
    }

    @Test
    void unknownSentimentGetsDefault() {
        Ticket t = new Ticket("bug", "p2", "furious", "x", List.of("a"));
        assertEquals("frustrated", TicketValidator.normalize(t).ticket().sentiment());
    }

    @Test
    void tooManyStepsIsContentError() {
        Ticket t = new Ticket("bug", "p2", "calm", "x", List.of("1", "2", "3", "4", "5", "6"));
        assertTrue(TicketValidator.validate(t).orElse("").contains("steps"));
    }

    @Test
    void featureRequestCannotBeP0() {
        Ticket t = new Ticket("feature_request", "p0", "calm", "x", List.of("1"));
        assertTrue(TicketValidator.validate(t).isPresent());
    }

    @Test
    void schemaHasRequiredAndEnums() {
        var schema = TicketSchema.json();
        assertEquals("object", schema.path("type").asText());
        assertEquals(5, schema.path("required").size());
        assertEquals(4, schema.path("properties").path("priority").path("enum").size());
        assertFalse(schema.path("additionalProperties").asBoolean(true));
    }

    @Test
    void legacySchemaDropsUnsupportedKeys() {
        var legacy = GeminiClient.toLegacySchema(TicketSchema.json());
        assertFalse(legacy.has("additionalProperties"));
        assertTrue(legacy.path("properties").has("steps"));
    }

    // ------------------------------------------------------------------ строгий розбір (форма)

    @Test
    void fenceWithoutPreambleIsStripped() {
        assertTrue(TicketValidator.parse("```json\n" + GOOD + "\n```").ok());
    }

    @Test
    void textAfterJsonIsFormError() {
        TicketValidator.Result r = TicketValidator.parse(GOOD + "\n\nЯкщо потрібно, можу додати ще кроки.");
        assertFalse(r.ok());
        assertTrue(r.error().contains("зайвий текст"), r.error());
        // і після закривної огорожі теж
        assertFalse(TicketValidator.parse("```json\n" + GOOD + "\n```\nСподіваюсь, допоможе!").ok());
    }

    @Test
    void secondJsonObjectIsFormError() {
        assertFalse(TicketValidator.parse(GOOD + " " + GOOD).ok());
    }

    @Test
    void missingFieldIsNamed() {
        String noSteps = "{\"category\":\"bug\",\"priority\":\"p2\",\"sentiment\":\"calm\",\"summary\":\"x\"}";
        TicketValidator.Result r = TicketValidator.parse(noSteps);
        assertFalse(r.ok());
        assertEquals("бракує поля steps", r.error());
        TicketValidator.Result two = TicketValidator.parse("{\"category\":\"bug\",\"priority\":\"p2\",\"sentiment\":\"calm\"}");
        assertEquals("бракує полів summary, steps", two.error());
    }

    @Test
    void nullFieldCountsAsMissing() {
        String nullPriority = "{\"category\":\"bug\",\"priority\":null,\"sentiment\":\"calm\",\"summary\":\"x\",\"steps\":[\"a\"]}";
        assertEquals("бракує поля priority", TicketValidator.parse(nullPriority).error());
    }

    @Test
    void arrayInsteadOfObjectIsFormError() {
        assertFalse(TicketValidator.parse("[" + GOOD + "]").ok());
    }

    @Test
    void parseExactDoesNotStripWrappers() {
        assertTrue(TicketValidator.parseExact("  " + GOOD + "\n").ok());
        assertFalse(TicketValidator.parseExact("```json\n" + GOOD + "\n```").ok());
        assertFalse(TicketValidator.parseExact("Ось ваш JSON: " + GOOD).ok());
    }

    // ------------------------------------------------------------------ validate без NPE

    @Test
    void validateToleratesNullLabels() {
        Ticket t = new Ticket(null, null, null, "Клієнт не може увійти", List.of("перевірити логи"));
        assertTrue(TicketValidator.validate(t).isEmpty());
        assertTrue(TicketValidator.validate(new Ticket("bug", "p2", "calm", null, null)).isPresent());
    }

    // ------------------------------------------------------------------ схема для Claude

    @Test
    void claudeSchemaDropsUnsupportedKeywords() {
        var claude = ClaudeClient.toClaudeSchema(TicketSchema.json());
        var steps = claude.path("properties").path("steps");
        assertFalse(steps.has("maxItems"));
        assertEquals(1, steps.path("minItems").asInt()); // minItems 0/1 Claude приймає
        assertEquals(4, claude.path("properties").path("priority").path("enum").size());
        assertFalse(claude.path("additionalProperties").asBoolean(true));
        assertEquals(5, claude.path("required").size());
        // оригінал не зіпсовано — ворота й Gemini й далі бачать maxItems
        assertEquals(5, TicketSchema.json().path("properties").path("steps").path("maxItems").asInt());
    }

    @Test
    void claudeSchemaStripsNestedAndKeepsPropertyNames() throws Exception {
        var schema = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {"type":"object","properties":{
                  "maximum":{"type":"integer","minimum":0,"maximum":10},
                  "tags":{"type":"array","minItems":2,"maxItems":4,"items":{"type":"string","minLength":1,"maxLength":20}}},
                 "required":["maximum","tags"],"additionalProperties":false}""");
        var c = ClaudeClient.toClaudeSchema(schema);
        assertTrue(c.path("properties").has("maximum"));              // назва поля, а не ключове слово
        assertFalse(c.path("properties").path("maximum").has("minimum"));
        assertFalse(c.path("properties").path("maximum").has("maximum"));
        assertFalse(c.path("properties").path("tags").has("minItems")); // > 1 — прибрано
        assertFalse(c.path("properties").path("tags").has("maxItems"));
        assertFalse(c.path("properties").path("tags").path("items").has("minLength"));
        assertFalse(c.path("properties").path("tags").path("items").has("maxLength"));
    }

    @Test
    void geminiSchemaFallbackTriggersOnDifferentMessages() {
        assertTrue(GeminiClient.isSchemaFieldRejected(new ApiException(400,
                "Invalid JSON payload received. Unknown name \"responseJsonSchema\" at 'generation_config': Cannot find field.")));
        assertTrue(GeminiClient.isSchemaFieldRejected(new ApiException(400, "RESPONSEJSONSCHEMA is not supported")));
        assertTrue(GeminiClient.isSchemaFieldRejected(new ApiException(400, "Unsupported JSON_SCHEMA keyword")));
        assertFalse(GeminiClient.isSchemaFieldRejected(new ApiException(400, "API key not valid. Please pass a valid API key.")));
        assertFalse(GeminiClient.isSchemaFieldRejected(new ApiException(429, "responseJsonSchema quota")));
    }
}
