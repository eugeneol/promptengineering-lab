package ua.knu.promptlab.lab4;

import java.util.List;
import java.util.Map;

/**
 * Лабораторна 4, крок 5. Автоматична перевірка відповіді за еталоном (груба: остаточне слово — ваші очі, а в Л6 — модель-суддя):
 * <ul>
 *   <li><b>answerable збігся</b> — модель правильно визначила, чи є відповідь у положеннях;</li>
 *   <li><b>цитата влучна</b> — серед cited_ids є шматок з еталонним фрагментом (лише для питань з відповіддю; для «двох пунктів» —
 *       цитовані шматки містять усі фрагменти);</li>
 *   <li><b>ключові факти</b> — скільки з 1–3 ключових слів/чисел еталону є у відповіді (після нормалізації);</li>
 *   <li><b>правильна (авто)</b> — answerable збігся, а для питань з відповіддю ще й цитата влучна і всі ключові факти є.</li>
 * </ul>
 */
public record AutoCheck(boolean answered, boolean answerableOk, Boolean citeHit, int factsFound, int factsTotal) {

    public boolean correct() {
        if (!answered || !answerableOk) return false;
        return citeHit == null || citeHit && factsFound == factsTotal;
    }

    public static AutoCheck of(Lab4Variant.Question q, RagAnswer a, Map<String, Chunk> given) {
        if (a == null) return new AutoCheck(false, false, q.answerable() ? false : null, 0, q.keyFacts().size());
        boolean ansOk = a.answerable() == q.answerable();
        Boolean hit = null;
        int facts = 0;
        if (q.answerable()) {
            List<Chunk> cited = new java.util.ArrayList<>();
            for (String id : a.citedIds()) if (given.get(id) != null) cited.add(given.get(id));
            hit = q.coveredBy(cited);
            for (String f : q.keyFacts()) if (TextNorm.hasFact(a.answer(), f)) facts++;
        }
        return new AutoCheck(true, ansOk, hit, facts, q.keyFacts().size());
    }

    /** «так / ні / —». */
    public String citeText() {
        return citeHit == null ? "—" : citeHit ? "так" : "ні";
    }

    public String factsText() {
        return factsTotal == 0 ? "—" : factsFound + "/" + factsTotal;
    }

    public static int correctCount(List<AutoCheck> cs) {
        return (int) cs.stream().filter(AutoCheck::correct).count();
    }
}
