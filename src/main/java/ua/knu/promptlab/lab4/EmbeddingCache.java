package ua.knu.promptlab.lab4;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Лабораторна 4. Кеш ембедингів на диску: {@code target/lab4-embeddings.tsv} (MOCK — {@code target/lab4-embeddings-mock.tsv}).
 *
 * <p>Ключ = SHA-256 від «модель | розмірність | текст з префіксом». Змінили префікс, модель чи розмірність — новий ключ і новий
 * ембединг; той самий текст — з кешу. Тому повторний прогін (після 429, після правки MyQuestions) не витрачає запитів на індексацію.
 * Рядок файлу: {@code ключ<TAB>768 чисел float32 у Base64}. Нові ембединги дописуються в кінець файлу одразу після кожного пакета.
 * LAB_NO_CACHE=1 — кеш лише в пам'яті (кожен запуск індексує заново).
 */
public final class EmbeddingCache {

    private final Path file;
    private final boolean disk;
    private final Map<String, float[]> entries = new HashMap<>();
    private boolean loadWarning;

    public EmbeddingCache(Path file, boolean disk) {
        this.file = file;
        this.disk = disk;
        if (disk && Files.isRegularFile(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    int tab = line.indexOf('\t');
                    if (tab <= 0) continue;
                    try {
                        entries.put(line.substring(0, tab), decode(line.substring(tab + 1)));
                    } catch (IllegalArgumentException e) {
                        loadWarning = true; // зіпсований рядок (наприклад, прогін упав посеред запису) — пропускаємо
                    }
                }
            } catch (IOException e) {
                loadWarning = true;
            }
        }
    }

    public static EmbeddingCache fromSettings(boolean noCache, boolean mock) {
        return new EmbeddingCache(Path.of("target", mock ? "lab4-embeddings-mock.tsv" : "lab4-embeddings.tsv"), !noCache);
    }

    public static String key(String model, int dim, String textWithPrefix) {
        String raw = model + "|" + dim + "|" + textWithPrefix;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public float[] get(String key) {
        return entries.get(key);
    }

    public int size() {
        return entries.size();
    }

    public Path file() {
        return file;
    }

    public boolean diskEnabled() {
        return disk;
    }

    public boolean hadLoadWarning() {
        return loadWarning;
    }

    /** Додає пакет і одразу дописує його у файл. */
    public void putAll(List<String> keys, List<float[]> vectors) {
        for (int i = 0; i < keys.size(); i++) entries.put(keys.get(i), vectors.get(i));
        if (!disk) return;
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                for (int i = 0; i < keys.size(); i++) w.write(keys.get(i) + "\t" + encode(vectors.get(i)) + "\n");
            }
        } catch (IOException e) {
            System.err.println("  [кеш ембедингів не збережено: " + e.getMessage() + "]");
        }
    }

    static String encode(float[] v) {
        ByteBuffer b = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float x : v) b.putFloat(x);
        return Base64.getEncoder().encodeToString(b.array());
    }

    static float[] decode(String s) {
        byte[] bytes = Base64.getDecoder().decode(s.strip());
        if (bytes.length % 4 != 0 || bytes.length == 0) throw new IllegalArgumentException("довжина");
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] v = new float[bytes.length / 4];
        for (int i = 0; i < v.length; i++) v[i] = b.getFloat();
        return v;
    }
}
