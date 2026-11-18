package ua.knu.promptlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ua.knu.promptlab.lab2.MySchemaRun;
import ua.knu.promptlab.mock.FakeLlmServer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Лабораторна 2, розділ 7: перевірка кодом власної схеми (MySchema + MySchemaRun) на імітації — без мережі.
 * Тест не залежить від того, яку задачу студент вписав у MySchema: він лише перевіряє, що прогін доходить до кінця.
 */
class Lab2MySchemaTest {

    private static String baseUrl;

    @BeforeAll
    static void start() {
        baseUrl = FakeLlmServer.startOnce().baseUrl();
    }

    @AfterAll
    static void stop() {
        FakeLlmServer.stop();
    }

    private static Config cfg(Config.Provider p) {
        return new Config(p, "test-key", p.defaultModel, baseUrl, true, 0, null);
    }

    @Test
    void runsOnMockForBothProviders() {
        for (LlmClient c : new LlmClient[]{new GeminiClient(cfg(Config.Provider.GEMINI), baseUrl),
                new ClaudeClient(cfg(Config.Provider.CLAUDE), baseUrl)}) {
            MySchemaRun.Summary s = MySchemaRun.run(c);
            assertTrue(s.schemaOk(), "SCHEMA у MySchema.java має бути валідним JSON-об'єктом з \"type\": \"object\"");
            assertEquals(3, s.total(), "у MySchema.INPUTS має бути рівно три входи");
            assertEquals(s.total(), s.parsed(), c.provider() + ": імітація повертає JSON за схемою — кожна відповідь має розібратися");
        }
    }

    @Test
    void mockAnswerFollowsSchemaEnumsAndTypes() throws Exception {
        ObjectMapper m = new ObjectMapper();
        JsonNode schema = m.readTree("""
                {"type":"object","properties":{
                  "tone":{"type":"string","enum":["positive","negative"]},
                  "topics":{"type":"array","maxItems":3,"items":{"type":"string","enum":["labs","other"]}},
                  "score":{"type":"integer"},"ok":{"type":"boolean"},"summary":{"type":"string"}},
                 "required":["tone","topics"]}""");
        for (LlmClient c : new LlmClient[]{new GeminiClient(cfg(Config.Provider.GEMINI), baseUrl),
                new ClaudeClient(cfg(Config.Provider.CLAUDE), baseUrl)}) {
            JsonNode a = m.readTree(c.askJson("system", "Лаби важкі", schema, 512).text());
            assertEquals("positive", a.path("tone").asText(), c.provider());
            assertEquals("labs", a.path("topics").get(0).asText(), c.provider());
            assertTrue(a.path("score").isInt() && a.path("ok").isBoolean() && a.path("summary").isTextual(), a.toString());
        }
    }
}
