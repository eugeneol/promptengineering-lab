package ua.knu.promptlab.lab4;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Лабораторна 4. Промпти й схеми відповіді.
 * <ul>
 *   <li>крок 1 — без документів: {@link #SYSTEM_NO_RAG} і схема {@code {answer}};</li>
 *   <li>кроки 5–6 — відповідь з цитатами (слайд 16): {@link #SYSTEM_RAG}, у повідомленні — знайдені шматки з id у квадратних
 *       дужках і питання; схема {@code {answer, cited_ids, answerable}}.</li>
 * </ul>
 */
public final class RagPrompts {

    private RagPrompts() {
    }

    /** Фраза, яку бот має сказати, якщо відповіді в уривках немає (валідатор шукає в ній слово «деканат»). */
    public static final String NOT_FOUND = "Не знайшов цього в положеннях — зверніться до деканату.";

    public static final String SYSTEM_NO_RAG = """
            Ти — бот підтримки студентів факультету університету. Відповідай коротко (1–3 речення) українською на питання студента \
            про правила навчання в університеті. Поверни JSON за схемою: поле answer — текст відповіді.""";

    public static final String SYSTEM_RAG = """
            Ти — бот підтримки студентів факультету університету. Відповідай ЛИШЕ з уривків положень університету, які дано \
            в повідомленні користувача. Кожен уривок починається з id у квадратних дужках.
            Правила:
            1. Якщо відповідь є в уривках — коротко (1–4 речення) перекажи її українською своїми словами, але з тими самими числами, \
            строками й назвами, що в уривку; answerable = true; у cited_ids — id уривків, на які спираєшся (хоча б один).
            2. Якщо в уривках відповіді немає — не вигадуй: answerable = false, cited_ids = [], answer = «""" + NOT_FOUND + """
            ».
            3. Не додавай правил, яких немає в уривках. Якщо правило стосується лише певної форми навчання чи категорії студентів \
            (наприклад, заочної форми) — так і скажи.
            4. У cited_ids — лише id з квадратних дужок, без змін.""";

    public static String userNoRag(String question) {
        return "Питання студента: " + question;
    }

    /** Повідомлення з шматками: «[id] Документ: …; розділ: …; с. N», текст шматка, порожній рядок; у кінці — питання. */
    public static String userRag(String question, List<Chunk> chunks) {
        StringBuilder sb = new StringBuilder("Уривки з положень:\n\n");
        for (Chunk c : chunks) {
            sb.append('[').append(c.id()).append("] Документ: ").append(c.docTitle());
            if (c.section() != null && !c.section().isBlank()) sb.append("; розділ: ").append(c.section());
            sb.append("; с. ").append(c.page()).append('\n').append(c.text().strip()).append("\n\n");
        }
        sb.append("Питання студента: ").append(question);
        return sb.toString();
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Схема кроку 1: {"answer": "…"}. */
    public static JsonNode schemaNoRag() {
        ObjectNode s = JSON.createObjectNode();
        s.put("type", "object");
        s.putObject("properties").putObject("answer").put("type", "string").put("description", "Відповідь студенту, 1–3 речення");
        s.putArray("required").add("answer");
        s.put("additionalProperties", false);
        return s;
    }

    /** Схема кроку 4б: {"query": "переписане питання"}. */
    public static JsonNode schemaRewrite() {
        ObjectNode s = JSON.createObjectNode();
        s.put("type", "object");
        s.putObject("properties").putObject("query").put("type", "string").put("description", "Питання, переписане мовою положень університету");
        s.putArray("required").add("query");
        s.put("additionalProperties", false);
        return s;
    }

    /** Схема кроків 5–6: answer, cited_ids, answerable (слайд 16). */
    public static JsonNode schemaRag() {
        ObjectNode s = JSON.createObjectNode();
        s.put("type", "object");
        ObjectNode p = s.putObject("properties");
        p.putObject("answer").put("type", "string").put("description", "Відповідь студенту з уривків або фраза «" + NOT_FOUND + "»");
        ObjectNode ids = p.putObject("cited_ids");
        ids.put("type", "array").put("description", "id уривків (з квадратних дужок), на які спирається відповідь");
        ids.putObject("items").put("type", "string");
        p.putObject("answerable").put("type", "boolean").put("description", "true — відповідь є в уривках; false — немає");
        s.putArray("required").add("answer").add("cited_ids").add("answerable");
        s.put("additionalProperties", false);
        return s;
    }
}
