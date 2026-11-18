package ua.knu.promptlab.lab4;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Лабораторна 4, крок 5. Розібрана відповідь {answer, cited_ids, answerable} і валідатор (правило з Л3 + нові правила слайда 16):
 * <ol>
 *   <li>відповідь — JSON з усіма трьома полями правильних типів;</li>
 *   <li>кожен id у cited_ids — серед id шматків, які ми передали в промпт (так ловимо вигадані посилання);</li>
 *   <li>answerable = true ⇒ cited_ids не порожній;</li>
 *   <li>answerable = false ⇒ у відповіді є порада звернутися до деканату.</li>
 * </ol>
 * Валідатор перевіряє, що цитата ІСНУЄ, але не те, що шматок справді підтверджує відповідь — це очима (а в Л6 — модель-суддя).
 */
public record RagAnswer(String answer, List<String> citedIds, boolean answerable) {

    /** Результат перевірки: відповідь або причина (одне речення, яке піде моделі при повторі). */
    public record Check(RagAnswer answer, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Лише розбір (крок 1: схема {answer}). */
    public static Check parseNoRag(String text) {
        try {
            JsonNode n = JSON.readTree(text == null ? "" : text.strip());
            if (n == null || !n.path("answer").isTextual() || n.path("answer").asText().isBlank()) return new Check(null, "немає поля answer з текстом");
            return new Check(new RagAnswer(n.path("answer").asText(), List.of(), true), null);
        } catch (Exception e) {
            return new Check(null, "відповідь не є JSON");
        }
    }

    public static Check validate(String text, Collection<String> givenIds) {
        JsonNode n;
        try {
            n = JSON.readTree(text == null ? "" : text.strip());
        } catch (Exception e) {
            return new Check(null, "відповідь не є JSON за схемою");
        }
        if (n == null || !n.isObject()) return new Check(null, "відповідь не є JSON-об'єктом");
        if (!n.path("answer").isTextual() || n.path("answer").asText().isBlank()) return new Check(null, "поле answer порожнє або не рядок");
        if (!n.path("cited_ids").isArray()) return new Check(null, "поле cited_ids має бути масивом id");
        if (!n.path("answerable").isBoolean()) return new Check(null, "поле answerable має бути true або false");
        List<String> ids = new ArrayList<>();
        for (JsonNode x : n.path("cited_ids")) ids.add(x.asText());
        RagAnswer a = new RagAnswer(n.path("answer").asText(), ids, n.path("answerable").asBoolean());
        List<String> unknown = ids.stream().filter(id -> !givenIds.contains(id)).toList();
        if (!unknown.isEmpty()) {
            return new Check(a, "cited_ids містить id, яких не було серед переданих уривків: " + String.join(", ", unknown)
                    + "; дозволені id: " + String.join(", ", givenIds));
        }
        if (a.answerable() && ids.isEmpty()) return new Check(a, "answerable = true, але cited_ids порожній — вкажи id уривків, на які спирається відповідь");
        if (!a.answerable() && !TextNorm.normalize(a.answer()).contains("деканат")) {
            return new Check(a, "answerable = false, але у відповіді немає поради звернутися до деканату («" + RagPrompts.NOT_FOUND + "»)");
        }
        return new Check(a, null);
    }
}
