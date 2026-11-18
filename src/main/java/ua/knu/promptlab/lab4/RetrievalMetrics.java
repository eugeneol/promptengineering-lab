package ua.knu.promptlab.lab4;

import java.util.List;
import java.util.Locale;

/**
 * Лабораторна 4, кроки 3–4. Метрики пошуку (слайд 15) — кілька рядків коду, а не бібліотека:
 * <ul>
 *   <li><b>recall@5</b> — частка питань, де серед перших 5 шматків є шматок з еталонним фрагментом (для «двох пунктів» — усі фрагменти);</li>
 *   <li><b>MRR@5</b> — середнє «1 / місце» першого такого шматка (1-ше місце — 1, 2-ге — 1/2, …; не в п'ятірці — 0). Нулі входять
 *       у середнє, тож MRR@5 = 0,5 буває і «усі на 2-му місці», і «половина на 1-му, решта не знайдена» — дивіться разом з recall@5.</li>
 * </ul>
 * Приклад зі слайда: місця 1, 3, не знайдено, 2 → recall@5 = 3/4 = 0,75; MRR@5 = (1 + 1/3 + 0 + 1/2) / 4 ≈ 0,458.
 */
public final class RetrievalMetrics {

    public static final int K = 5;

    private RetrievalMetrics() {
    }

    /**
     * Місце (з 1), на якому серед перших k еталон уперше покрито, або 0 — не знайдено. Звичайне питання — перший шматок з будь-яким
     * еталонним фрагментом; «два пункти» — місце, на якому знайдено ОСТАННІЙ з потрібних фрагментів (усі мають бути в п'ятірці).
     */
    public static int rank(List<VectorSearch.Hit> hits, Lab4Variant.Question q, int k) {
        List<Chunk> seen = new java.util.ArrayList<>();
        for (int i = 0; i < Math.min(k, hits.size()); i++) {
            seen.add(hits.get(i).chunk());
            if (q.coveredBy(seen)) return i + 1;
        }
        return 0;
    }

    public static double recall(List<Integer> ranks) {
        if (ranks.isEmpty()) return 0;
        return ranks.stream().filter(r -> r > 0 && r <= K).count() / (double) ranks.size();
    }

    public static double mrr(List<Integer> ranks) {
        if (ranks.isEmpty()) return 0;
        double s = 0;
        for (int r : ranks) if (r > 0 && r <= K) s += 1.0 / r;
        return s / ranks.size();
    }

    public static int found(List<Integer> ranks) {
        return (int) ranks.stream().filter(r -> r > 0 && r <= K).count();
    }

    /** «7/9 (0,78)». */
    public static String recallText(List<Integer> ranks) {
        return found(ranks) + "/" + ranks.size() + " (" + num(recall(ranks)) + ")";
    }

    public static String num(double x) {
        return String.format(Locale.ROOT, "%.2f", x).replace('.', ',');
    }

    /** «місце 2» / «—». */
    public static String rankText(int r) {
        return r > 0 ? String.valueOf(r) : "—";
    }

    /**
     * Висновок порівняння двох способів за кількістю знайдених питань (з n): різниця в одне питання (≈ 11 п.п. на 9) — у межах шуму.
     */
    public static String verdict(int foundA, int foundB, int n) {
        int d = foundA - foundB;
        String pp = String.format(Locale.ROOT, "%.0f", 100.0 * Math.abs(d) / Math.max(1, n));
        if (Math.abs(d) <= 1) return (d == 0 ? "однаково" : (d > 0 ? "+" : "−") + Math.abs(d) + " пит. (" + pp + " п.п.)") + " → висновку немає (різниця не більша за одне питання)";
        return (d > 0 ? "+" : "−") + Math.abs(d) + " пит. (" + pp + " п.п.) → " + (d > 0 ? "краще" : "гірше") + " (різниця більша за одне питання)";
    }
}
