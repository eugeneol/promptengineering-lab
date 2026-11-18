package ua.knu.promptlab.lab4;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Лабораторна 4. Кеш ембедингів викладача — ресурс {@code lab4/embeddings-cache.bin.gz} (у MOCK — {@code lab4/mock/embeddings-cache.bin.gz}).
 *
 * <p>Ембединги шматків не залежать від варіанта, тож їх рахує один раз викладач (клас
 * {@link TeacherIndex}, запуск його main), а студенти беруть готові. Тексту положень у файлі немає — лише ключі (SHA-256 від «модель | розмірність |
 * текст з префіксом», як у {@link EmbeddingCache}) і числа. Якщо положення оновилось, ключ нового шматка не знайдеться, і код порахує
 * його сам.
 *
 * <p>Формат (gzip): рядок {@code LAB4EMB1}, рядок JSON {"model", "dim", "count", "created", "scale"}, далі {@code count} записів:
 * 32 байти ключа і {@code dim} чисел int16 (значення × 32767; вектори gemini-embedding-2 розмірності 768 нормалізовані, |x| ≤ 1,
 * тож точність ≈ 3·10⁻⁵ — на косинус і місця в пошуку це не впливає).
 */
public final class EmbeddingBundle {

    public static final String MAGIC = "LAB4EMB1";
    public static final String RESOURCE = "/lab4/embeddings-cache.bin.gz";
    public static final String MOCK_RESOURCE = "/lab4/mock/embeddings-cache.bin.gz";
    private static final float SCALE = 32767f;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String model;
    private final int dim;
    private final String created;
    private final Map<String, float[]> vectors;

    private EmbeddingBundle(String model, int dim, String created, Map<String, float[]> vectors) {
        this.model = model;
        this.dim = dim;
        this.created = created;
        this.vectors = vectors;
    }

    public String model() {
        return model;
    }

    public int dim() {
        return dim;
    }

    public String created() {
        return created;
    }

    public int size() {
        return vectors.size();
    }

    public float[] get(String key) {
        return vectors.get(key);
    }

    /** Кеш викладача з ресурсів або null, якщо його немає (викладач ще не згенерував) чи він для іншої моделі / розмірності. */
    public static EmbeddingBundle fromResources(boolean mock, String model, int dim) {
        try (InputStream in = EmbeddingBundle.class.getResourceAsStream(mock ? MOCK_RESOURCE : RESOURCE)) {
            if (in == null) return null;
            EmbeddingBundle b = read(in);
            return b.model.equals(model) && b.dim == dim ? b : null;
        } catch (IOException | RuntimeException e) {
            System.err.println("  [кеш ембедингів викладача не прочитався: " + e.getMessage() + " — рахуємо самі]");
            return null;
        }
    }

    public static EmbeddingBundle read(InputStream raw) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)))) {
            if (!MAGIC.equals(readLine(in))) throw new IOException("не той формат файлу");
            JsonNode meta = JSON.readTree(readLine(in));
            int dim = meta.path("dim").asInt();
            int count = meta.path("count").asInt();
            Map<String, float[]> map = new HashMap<>(count * 2);
            byte[] key = new byte[32];
            for (int i = 0; i < count; i++) {
                in.readFully(key);
                float[] v = new float[dim];
                for (int j = 0; j < dim; j++) v[j] = in.readShort() / SCALE;
                map.put(HexFormat.of().formatHex(key), v);
            }
            return new EmbeddingBundle(meta.path("model").asText(), dim, meta.path("created").asText(""), map);
        }
    }

    /** Записує вектори (ключ — hex SHA-256) у файл; порядок ключів — за абеткою, тож однакові дані дають однаковий файл. */
    public static void write(Path file, String model, int dim, String created, Map<String, float[]> vectors) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        ObjectNode meta = JSON.createObjectNode();
        meta.put("model", model).put("dim", dim).put("count", vectors.size()).put("created", created).put("scale", (int) SCALE);
        try (OutputStream os = Files.newOutputStream(file);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(os)))) {
            out.write((MAGIC + "\n" + meta + "\n").getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, float[]> e : new TreeMap<>(vectors).entrySet()) {
                float[] v = e.getValue();
                if (v.length != dim) throw new IOException("вектор " + e.getKey() + " має " + v.length + " чисел, а не " + dim);
                double n = 0;
                for (float x : v) n += (double) x * x;
                double k = n > 0 ? 1 / Math.sqrt(n) : 1; // косинус не залежить від довжини — зберігаємо нормалізований вектор
                out.write(HexFormat.of().parseHex(e.getKey()));
                for (float x : v) out.writeShort((int) Math.round(Math.max(-1.0, Math.min(1.0, x * k)) * SCALE));
            }
        }
    }

    private static String readLine(DataInputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b < 0) throw new EOFException();
            if (b == '\n') break;
            sb.append((char) b);
        }
        return new String(sb.toString().getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }
}
