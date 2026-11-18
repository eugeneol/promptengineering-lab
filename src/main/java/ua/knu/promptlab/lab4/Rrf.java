package ua.knu.promptlab.lab4;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Лабораторна 4, крок 4. Гібридний пошук: злиття двох списків місць методом RRF (reciprocal rank fusion, слайд 14;
 * Cormack, Clarke, Büttcher, 2009). Кожне місце шматка в кожному списку дає 1 / (k + місце) балів, бали додаються; k = 60.
 * Бали косинуса й BM25 не порівнюються — лише місця. З кожного пошуку беремо топ-{@value #DEPTH}.
 */
public final class Rrf {

    public static final int K = 60;
    public static final int DEPTH = 20;

    private Rrf() {
    }

    /** Бал одного місця (місця рахуються з 1). */
    public static double score(int rank) {
        return 1.0 / (K + rank);
    }

    /** Злиття кількох списків (кожен — від найкращого). Повертає шматки за спаданням суми балів; при рівності — за першою появою. */
    public static List<VectorSearch.Hit> fuse(List<List<VectorSearch.Hit>> lists, int k) {
        Map<String, Double> sum = new LinkedHashMap<>();
        Map<String, Chunk> byId = new LinkedHashMap<>();
        for (List<VectorSearch.Hit> list : lists) {
            for (int r = 0; r < Math.min(DEPTH, list.size()); r++) {
                Chunk c = list.get(r).chunk();
                sum.merge(c.id(), score(r + 1), Double::sum);
                byId.putIfAbsent(c.id(), c);
            }
        }
        List<VectorSearch.Hit> out = new ArrayList<>();
        sum.forEach((id, s) -> out.add(new VectorSearch.Hit(byId.get(id), s)));
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return out.subList(0, Math.min(k, out.size()));
    }
}
