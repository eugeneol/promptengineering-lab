package ua.knu.promptlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ua.knu.promptlab.lab2.JsonGate;
import ua.knu.promptlab.lab2.Ticket;
import ua.knu.promptlab.lab2.TicketData;
import ua.knu.promptlab.lab2.TicketSchema;
import ua.knu.promptlab.lab2.TicketValidator;
import ua.knu.promptlab.mock.FakeLlmServer;
import ua.knu.promptlab.steps.Step5NonDeterminism;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Перевіряє «водопровід» обох клієнтів на локальній імітації API (реальних запитів немає). */
class ClientsTest {

    private static String baseUrl;
    private static LlmClient claude;
    private static LlmClient gemini;

    @BeforeAll
    static void start() {
        baseUrl = FakeLlmServer.startOnce().baseUrl();
        claude = new ClaudeClient(cfg(Config.Provider.CLAUDE, "test-key"), baseUrl);
        gemini = new GeminiClient(cfg(Config.Provider.GEMINI, "test-key"), baseUrl);
    }

    @AfterAll
    static void stop() {
        FakeLlmServer.stop();
    }

    private static Config cfg(Config.Provider p, String key) {
        return new Config(p, key, p.defaultModel, baseUrl, true, 0, null);
    }

    // ------------------------------------------------------------------ спільна поведінка

    @Test
    void bothProvidersParseTextAndUsage() {
        for (LlmClient c : new LlmClient[]{claude, gemini}) {
            LlmResponse r = c.ask("Відповідай коротко.", "Що таке токен?", null, 300);
            assertFalse(r.text().isBlank(), c.provider());
            assertEquals("end_turn", r.stopReason(), c.provider());
            assertTrue(r.inputTokens() > 0, c.provider());
            assertTrue(r.outputTokens() > 0, c.provider());
        }
    }

    @Test
    void smallLimitTruncatesAnswerForBoth() {
        for (LlmClient c : new LlmClient[]{claude, gemini}) {
            LlmResponse r = c.ask(null, "Що таке токен?", null, 10);
            assertEquals("max_tokens", r.stopReason(), c.provider());
            assertEquals(10, r.outputTokens(), c.provider());
        }
    }

    @Test
    void countTokensWorksForBoth() {
        assertTrue(claude.countTokens(null, "Hello, world") > 0);
        assertTrue(gemini.countTokens(null, "Hello, world") > 0);
        assertTrue(gemini.countTokens("Ти асистент.", "Hello, world") > gemini.countTokens(null, "Hello, world"));
    }

    // ------------------------------------------------------------------ помилки автентифікації

    @Test
    void invalidClaudeKeyIs401() {
        LlmClient bad = new ClaudeClient(cfg(Config.Provider.CLAUDE, "invalid"), baseUrl);
        ApiException e = assertThrows(ApiException.class, () -> bad.ask(null, "hi", null, 10));
        assertEquals(401, e.status());
    }

    @Test
    void invalidGeminiKeyIs400() {
        LlmClient bad = new GeminiClient(cfg(Config.Provider.GEMINI, "invalid"), baseUrl);
        ApiException e = assertThrows(ApiException.class, () -> bad.ask(null, "hi", null, 10));
        assertEquals(400, e.status());
        assertTrue(e.getMessage().contains("API key"));
    }

    // ------------------------------------------------------------------ форма запитів

    @Test
    void claudeRequestShape() {
        var body = claude.buildRequest(null, "hi", null, 50);
        assertEquals(50, body.get("max_tokens").asInt());
        assertFalse(body.has("system"));
        assertFalse(body.has("temperature"));
        assertEquals("user", body.get("messages").get(0).get("role").asText());
    }

    @Test
    void geminiRequestShape() {
        var body = gemini.buildRequest("Ти асистент.", "hi", 0.0, 50);
        assertEquals("Ти асистент.", body.get("systemInstruction").get("parts").get(0).get("text").asText());
        assertEquals("hi", body.get("contents").get(0).get("parts").get(0).get("text").asText());
        assertEquals(50, body.get("generationConfig").get("maxOutputTokens").asInt());
        assertEquals(0.0, body.get("generationConfig").get("temperature").asDouble());
        assertFalse(body.get("generationConfig").has("thinkingConfig"));
        assertFalse(gemini.buildRequest(null, "hi", null, 50).has("systemInstruction"));
    }

    // ------------------------------------------------------------------ розбір відповіді Gemini (без мережі)

    @Test
    void geminiParseCountsThinkingAsOutputAndHandlesEmptyText() throws Exception {
        // MAX_TOKENS, коли весь ліміт з'їло «міркування»: тексту немає, але токени тарифікуються
        String json = """
                {"candidates":[{"content":{"role":"model"},"finishReason":"MAX_TOKENS"}],
                 "usageMetadata":{"promptTokenCount":12,"thoughtsTokenCount":25,"totalTokenCount":37},
                 "modelVersion":"gemini-3.5-flash-lite","responseId":"r1"}""";
        LlmResponse r = GeminiClient.parse(new ObjectMapper().readTree(json), "x", 5);
        assertEquals("", r.text());
        assertEquals("max_tokens", r.stopReason());
        assertEquals("MAX_TOKENS", r.rawStopReason());
        assertEquals(12, r.inputTokens());
        assertEquals(25, r.thinkingTokens());
        assertEquals(25, r.outputTokens()); // 0 токенів тексту + 25 міркування
    }

    @Test
    void geminiParseSkipsThoughtPartsAndSubtractsCachedFromInput() throws Exception {
        String json = """
                {"candidates":[{"content":{"parts":[{"text":"план","thought":true},{"text":"Відповідь"}]},"finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":100,"cachedContentTokenCount":40,"candidatesTokenCount":7,"thoughtsTokenCount":3}}""";
        LlmResponse r = GeminiClient.parse(new ObjectMapper().readTree(json), "m", 1);
        assertEquals("Відповідь", r.text());
        assertEquals("end_turn", r.stopReason());
        assertEquals(60, r.inputTokens());
        assertEquals(40, r.cacheReadTokens());
        assertEquals(10, r.outputTokens());
    }

    @Test
    void geminiParseReportsBlockedPrompt() throws Exception {
        JsonNode resp = new ObjectMapper().readTree("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}");
        LlmResponse r = GeminiClient.parse(resp, "m", 1);
        assertEquals("BLOCKED:SAFETY", r.rawStopReason());
        assertEquals("", r.text());
    }

    // ------------------------------------------------------------------ ціни та конфіг

    @Test
    void pricingMath() {
        Pricing sonnet = Pricing.forModel("claude-sonnet-5-5").orElseThrow();
        assertEquals(12.0, sonnet.cost(1_000_000, 1_000_000, 0, 0), 1e-9);
        assertEquals(5.0, sonnet.outputToInputRatio(), 1e-9);
        assertTrue(sonnet.hasCachePrices());

        Pricing g = Pricing.forModel("gemini-3.5-flash-lite").orElseThrow();
        assertEquals(2.8, g.cost(1_000_000, 1_000_000, 0, 0), 1e-9);
        assertFalse(g.hasCachePrices());
        assertTrue(Pricing.forModel("some-future-model").isEmpty());
    }

    @Test
    void configChoosesProviderAndRequiresKey() {
        Path none = Path.of("no-such.env");
        assertEquals(Config.Provider.GEMINI, Config.load(none, Map.of("GEMINI_API_KEY", "k")).provider());
        assertEquals(Config.Provider.CLAUDE, Config.load(none, Map.of("ANTHROPIC_API_KEY", "k")).provider());
        assertEquals(Config.Provider.CLAUDE,
                Config.load(none, Map.of("ANTHROPIC_API_KEY", "a", "GEMINI_API_KEY", "g")).provider());
        assertEquals(Config.Provider.GEMINI,
                Config.load(none, Map.of("ANTHROPIC_API_KEY", "a", "GEMINI_API_KEY", "g", "LAB_PROVIDER", "gemini")).provider());
        assertEquals("gemini-3.5-flash-lite", Config.load(none, Map.of("GEMINI_API_KEY", "k")).model());
        assertTrue(Config.load(none, Map.of("LAB_MOCK", "1")).mock());

        assertThrows(IllegalStateException.class, () -> Config.load(none, Map.of()));
        assertThrows(IllegalStateException.class, () -> Config.load(none, Map.of("LAB_PROVIDER", "foo")));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Config.load(none, Map.of("LAB_PROVIDER", "gemini")));
        assertTrue(e.getMessage().contains("GEMINI_API_KEY"));
    }

    // ------------------------------------------------------------------ лабораторна 2 на імітації

    @Test
    void geminiFallsBackToLegacySchemaField() {
        FakeLlmServer.rejectResponseJsonSchema = true;
        try {
            GeminiClient g = new GeminiClient(cfg(Config.Provider.GEMINI, "test-key"), baseUrl);
            assertEquals("responseJsonSchema", g.schemaFieldInUse());
            LlmResponse r = g.askJson(TicketData.SYSTEM, "Повідомлення клієнта: не можу увійти", TicketSchema.json(), 2048);
            assertTrue(TicketValidator.parseExact(r.text()).ok(), r.text());
            assertEquals("responseSchema", g.schemaFieldInUse());
            assertTrue(g.buildJsonRequest("s", "u", TicketSchema.json(), 10).path("generationConfig").has("responseSchema"));
        } finally {
            FakeLlmServer.rejectResponseJsonSchema = false;
        }
    }

    @Test
    void claudeStructuredRequestIsAcceptedByMock() {
        // імітація, як і справжній Claude, відповідає 400 на maxItems — отже клієнт мусить його прибрати
        var body = claude.buildJsonRequest("s", "u", TicketSchema.json(), 100);
        assertFalse(body.path("output_config").path("format").path("schema").path("properties").path("steps").has("maxItems"));
        LlmResponse r = claude.askJson(TicketData.SYSTEM, "Повідомлення клієнта: не можу увійти", TicketSchema.json(), 2048);
        assertTrue(TicketValidator.parseExact(r.text()).ok(), r.text());
    }

    @Test
    void mockWithSchemaNeverBreaksSchema() {
        for (LlmClient c : new LlmClient[]{claude, gemini}) {
            for (TicketData.Case k : TicketData.CASES) {
                LlmResponse r = c.askJson(TicketData.SYSTEM, "Повідомлення клієнта:\n\"\"\"" + k.message() + "\"\"\"", TicketSchema.json(), 2048);
                TicketValidator.Result p = TicketValidator.parseExact(r.text());
                assertTrue(p.ok(), c.provider() + ": " + r.text());
                assertTrue(Ticket.PRIORITIES.contains(p.ticket().priority()), r.text());
                assertTrue(Ticket.CATEGORIES.contains(p.ticket().category()), r.text());
                assertTrue(p.ticket().steps().size() >= 1 && p.ticket().steps().size() <= 5, r.text());
            }
        }
    }

    @Test
    void mockGateGivesMeaningfulNumbers() {
        for (LlmClient c : new LlmClient[]{claude, gemini}) {
            JsonGate gate = new JsonGate(c, 3, 2048);
            int first = 0, fixed = 0, failed = 0;
            for (TicketData.Case k : TicketData.CASES) {
                JsonGate.Outcome o = gate.run(TicketData.SYSTEM, k.message());
                if (o.ok() && o.attempts() == 1) first++;
                else if (o.ok()) fixed++;
                else failed++;
                assertFalse(o.truncated());
            }
            assertEquals(7, first, c.provider());  // p = 0.7 < 1
            assertEquals(2, fixed, c.provider());  // v = 2/3 — визначене
            assertEquals(1, failed, c.provider()); // один кейс провалює всі повтори
        }
    }

    @Test
    void truncatedJsonIsNotRetried() {
        JsonGate.Outcome o = new JsonGate(gemini, 3, 40).run(TicketData.SYSTEM, TicketData.CASES.get(0).message());
        assertFalse(o.ok());
        assertTrue(o.truncated());
        assertEquals(1, o.attempts());
    }

    // ------------------------------------------------------------------ лабораторна 1, крок 5

    @Test
    void answersAreNormalizedBeforeCounting() {
        assertEquals("кава на паузі", Step5NonDeterminism.normalize("«Кава на паузі»"));
        assertEquals("кава на паузі", Step5NonDeterminism.normalize("  **Кава на паузі**.\n"));
        assertEquals("кава на паузі", Step5NonDeterminism.normalize("\"Кава на паузі!\""));
        assertEquals("кав'ярня лекторій", Step5NonDeterminism.normalize("Кав'ярня «Лекторій»"));
        assertEquals("кава на паузі", Step5NonDeterminism.normalize("'Кава на паузі'."));
    }

    @Test
    void mockAtZeroTemperatureIsNotPerfectlyDeterministic() {
        java.util.Set<String> raw = new java.util.HashSet<>(), norm = new java.util.HashSet<>();
        for (int i = 0; i < 10; i++) {
            String t = gemini.ask(null, "Придумай одну назву для кав'ярні біля університету.", 0.0, 2048).text();
            raw.add(t);
            norm.add(Step5NonDeterminism.normalize(t));
        }
        assertTrue(norm.size() >= 2 && norm.size() <= 3, norm.toString());
        assertTrue(raw.size() > norm.size(), raw.toString());
    }
}
