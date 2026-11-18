package ua.knu.promptlab.lab4;

import ua.knu.promptlab.Config;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Лабораторна 4. Звідки беруться тексти положень (вони належать університету, тому в проєкт не входять):
 * <ol>
 *   <li><b>кеш тексту</b> {@code target/lab4-corpus/<id>.txt} — якщо вже є, нічого не завантажуємо;</li>
 *   <li><b>PDF у папці</b> {@code lab4-pdf/<id>.pdf} (у корені проєкту; інша папка — LAB4_PDF_DIR) — якщо ви завантажили його вручну;</li>
 *   <li><b>завантаження</b> з efund.cnu.edu.ua за адресою з {@code lab4/corpus.json}; файл зберігається в target/lab4-corpus/<id>.pdf.</li>
 * </ol>
 * Текст витягує PDFBox ({@link PdfText#pages}), колонтитули прибирає {@link PdfText#clean}, результат пишеться в кеш тексту.
 * Якщо PDF відрізняється від того, з яким готували еталони (SHA-256 у corpus.json), консоль попереджає: положення могли змінити,
 * і частина еталонних фрагментів може не знайтися.
 *
 * <p>LAB4_OFFLINE=1 — не завантажувати (лише кеш і папка lab4-pdf). У режимі MOCK тексти — з ресурсів (вигадані положення).
 */
public final class CorpusLoader {

    /** Версія очищення тексту: змінили правила колонтитулів — збільшіть, і кеш тексту перебудується з PDF. */
    public static final int CLEAN_VERSION = 1;
    static final String PAGE_BREAK = "\n\f\n";

    /** Скільки документів звідки взято — для консолі. */
    public record Stats(int fromCache, int fromFolder, int downloaded, List<String> changed) {
    }

    private final Path cacheDir;
    private final Path pdfDir;
    private final boolean allowDownload;
    private int fromCache, fromFolder, downloaded;
    private final List<String> changed = new ArrayList<>();

    public CorpusLoader(Path cacheDir, Path pdfDir, boolean allowDownload) {
        this.cacheDir = cacheDir;
        this.pdfDir = pdfDir;
        this.allowDownload = allowDownload;
    }

    /** За налаштуваннями: кеш target/lab4-corpus, папка LAB4_PDF_DIR (або lab4-pdf), LAB4_OFFLINE=1 — без завантаження. */
    public static CorpusLoader fromSettings() {
        String dir = Config.setting("LAB4_PDF_DIR");
        return new CorpusLoader(Path.of("target", "lab4-corpus"), Path.of(dir == null ? "lab4-pdf" : dir), !Config.isTrueSetting("LAB4_OFFLINE"));
    }

    public Path pdfDir() {
        return pdfDir;
    }

    public Stats stats() {
        return new Stats(fromCache, fromFolder, downloaded, List.copyOf(changed));
    }

    /** Усі документи корпусу. Якщо якийсь не вдалося отримати — IllegalStateException з поясненням, що завантажити вручну. */
    public List<Corpus.Text> load(Corpus corpus) {
        List<Corpus.Text> out = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (Corpus.Doc d : corpus.documents()) {
            try {
                out.add(loadOne(d));
            } catch (IOException | RuntimeException e) {
                failed.add(d.id() + ": " + e.getMessage());
            }
        }
        if (!failed.isEmpty()) {
            StringBuilder sb = new StringBuilder("\nНе вдалося отримати " + failed.size() + " з " + corpus.documents().size() + " положень:\n");
            for (String f : failed) sb.append("  ✗ ").append(f).append('\n');
            sb.append("\nЗавантажте PDF вручну за посиланням і покладіть у папку ").append(pdfDir).append("/<id>.pdf (у корені проєкту), наприклад:\n");
            for (Corpus.Doc d : corpus.documents()) {
                if (failed.stream().anyMatch(f -> f.startsWith(d.id() + ":")))
                    sb.append("  ").append(pdfDir.resolve(d.id() + ".pdf")).append("  ←  ").append(d.url()).append('\n');
            }
            sb.append("Потім запустіть ще раз: вже отримані положення візьмуться з кешу target/lab4-corpus.\n");
            throw new IllegalStateException(sb.toString());
        }
        return out;
    }

    Corpus.Text loadOne(Corpus.Doc d) throws IOException {
        Path txt = cacheDir.resolve(d.id() + ".txt");
        if (Files.isRegularFile(txt)) {
            List<String> pages = readCache(txt, d);
            if (pages != null) {
                fromCache++;
                return new Corpus.Text(d, pages);
            }
        }
        byte[] pdf;
        Path manual = pdfDir.resolve(d.id() + ".pdf");
        Path saved = cacheDir.resolve(d.id() + ".pdf");
        if (Files.isRegularFile(manual)) {
            pdf = Files.readAllBytes(manual);
            fromFolder++;
        } else if (Files.isRegularFile(saved)) {
            pdf = Files.readAllBytes(saved);
            fromFolder++;
        } else if (allowDownload) {
            pdf = download(d.url());
            if (!isPdf(pdf)) throw new IOException("сайт повернув не PDF (можливо, сторінку помилки) — файл не збережено");
            Files.createDirectories(cacheDir);
            Files.write(saved, pdf);
            downloaded++;
        } else {
            throw new IOException("немає ні кешу тексту, ні файлу " + manual + " (LAB4_OFFLINE=1 — завантаження вимкнено)");
        }
        if (!isPdf(pdf)) throw new IOException("файл не схожий на PDF (можливо, сайт повернув сторінку помилки)");
        String sha = sha256(pdf);
        if (d.sha256() != null && !d.sha256().equalsIgnoreCase(sha)) changed.add(d.id());
        List<String> pages = PdfText.clean(PdfText.pages(pdf));
        writeCache(txt, d, sha, pages);
        return new Corpus.Text(d, pages);
    }

    /** Чи починається файл з «%PDF» (перевіряємо ДО запису на диск). */
    static boolean isPdf(byte[] b) {
        return b != null && b.length >= 5 && new String(b, 0, 5, StandardCharsets.ISO_8859_1).startsWith("%PDF");
    }

    /** Тексти вигаданих положень MOCK — з ресурсів (сторінки розділено символом \f, з колонтитулами, як у справжніх PDF). */
    public static List<Corpus.Text> loadMock(Corpus corpus) {
        List<Corpus.Text> out = new ArrayList<>();
        for (Corpus.Doc d : corpus.documents()) {
            String path = "/lab4/mock/" + d.resource();
            try (InputStream in = CorpusLoader.class.getResourceAsStream(path)) {
                if (in == null) throw new IllegalStateException("Не знайдено ресурс " + path);
                String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                out.add(new Corpus.Text(d, PdfText.clean(Arrays.asList(raw.split("\f")))));
            } catch (IOException e) {
                throw new IllegalStateException("Не вдалося прочитати " + path + ": " + e.getMessage(), e);
            }
        }
        return out;
    }

    /**
     * User-Agent завантажувача. Лише ASCII: Java HttpClient відкидає заголовок з кирилицею («invalid header value»),
     * і тоді не завантажується жоден PDF (так було в першому справжньому прогоні 12.11.2026). Тест — Lab4Test.httpHeadersAreAscii.
     */
    public static final String USER_AGENT = "Mozilla/5.0 (prompt-lab; educational project)";

    /** Запит на завантаження PDF (окремо — щоб тест перевірив заголовки, не ходячи в мережу). */
    public static HttpRequest downloadRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(90)).header("User-Agent", USER_AGENT).GET().build();
    }

    static byte[] download(String url) throws IOException {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest req = downloadRequest(url);
        try {
            HttpResponse<byte[]> r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " від " + URI.create(url).getHost());
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("завантаження перервано", e);
        } catch (IOException e) {
            throw new IOException("не вдалося завантажити (" + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")", e);
        }
    }

    private static String header(Corpus.Doc d, String sha, int pages) {
        return "# lab4-corpus v" + CLEAN_VERSION + " | " + d.id() + " | pdf-sha256 " + sha + " | pages " + pages;
    }

    private static void writeCache(Path txt, Corpus.Doc d, String sha, List<String> pages) throws IOException {
        Files.createDirectories(txt.toAbsolutePath().getParent());
        Files.writeString(txt, header(d, sha, pages.size()) + "\n" + String.join(PAGE_BREAK, pages), StandardCharsets.UTF_8);
    }

    /** null — кеш старої версії очищення або зіпсований (тоді текст перебудується з PDF). */
    private List<String> readCache(Path txt, Corpus.Doc d) throws IOException {
        String all = Files.readString(txt, StandardCharsets.UTF_8);
        int nl = all.indexOf('\n');
        if (nl < 0 || !all.startsWith("# lab4-corpus v" + CLEAN_VERSION + " | " + d.id() + " |")) return null;
        String head = all.substring(0, nl);
        int i = head.indexOf("pdf-sha256 ");
        if (i >= 0 && d.sha256() != null) {
            String sha = head.substring(i + 11).split(" ")[0];
            if (!sha.equalsIgnoreCase(d.sha256()) && !changed.contains(d.id())) changed.add(d.id());
        }
        return List.of(all.substring(nl + 1).split(PAGE_BREAK, -1));
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
