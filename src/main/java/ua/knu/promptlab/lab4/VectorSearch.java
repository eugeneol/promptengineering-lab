package ua.knu.promptlab.lab4;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Лабораторна 4, крок 3. Векторний пошук перебором (слайд 12): косинус питання з КОЖНИМ шматком, сортування, перші k.
 * Косинус — повний, з діленням на довжини (слайд 8): не покладаємося на те, що вектори вже нормалізовані.
 */
public final class VectorSearch {

    /** Знайдений шматок і його бал (косинус або бал BM25 / RRF). */
    public record Hit(Chunk chunk, double score) {
    }

    private final List<Chunk> chunks;
    private final List<float[]> vectors;

    public VectorSearch(List<Chunk> chunks, List<float[]> vectors) {
        if (chunks.size() != vectors.size()) throw new IllegalArgumentException("шматків " + chunks.size() + ", векторів " + vectors.size());
        this.chunks = chunks;
        this.vectors = vectors;
    }

    /** cos(a, b) = (сума добутків координат) ÷ (|a| · |b|). Нульовий вектор — 0. */
    public static double cosine(float[] a, float[] b) {
        if (a.length != b.length) throw new IllegalArgumentException("різна розмірність: " + a.length + " і " + b.length);
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    public List<Hit> search(float[] query, int k) {
        List<Hit> all = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) all.add(new Hit(chunks.get(i), cosine(query, vectors.get(i))));
        all.sort(Comparator.comparingDouble(Hit::score).reversed());
        return all.subList(0, Math.min(k, all.size()));
    }

    public int size() {
        return chunks.size();
    }
}
