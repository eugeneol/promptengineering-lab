package ua.knu.promptlab.lab4;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Лабораторна 4. Текст із PDF положення (бібліотека PDFBox) і прибирання колонтитулів.
 *
 * <p>Кожна сторінка положень КНУ починається однаковим блоком (колонтитулом):
 * <pre>
 * ЕЛЕКТРОННИЙ ФОНД ЛОКАЛЬНИХ
 * НОРМАТИВНО-ПРАВОВИХ ДОКУМЕНТІВ
 * КАРПАТСЬКОГО НАЦІОНАЛЬНОГО УНІВЕРСИТЕТУ
 * ІМЕНІ ВАСИЛЯ СТЕФАНИКА
 * 02-07.45:2026
 * НАВЧАЛЬНО-МЕТОДИЧНИЙ ВІДДІЛ Редакція 11
 * </pre>
 * а закінчується номером сторінки. Якщо їх не прибрати, колонтитул потрапить у кожен шматок (і в ембединг), а номер сторінки
 * розірве речення. Правило прибирання ({@link #clean}): на початку сторінки (перші {@link #HEAD_ZONE} непорожніх рядків)
 * прибираємо рядки, що (а) повторюються на початку щонайменше половини сторінок документа, або (б) схожі на колонтитул
 * (великі літери «ФОНД», «УНІВЕРСИТЕТУ», «СТЕФАНИКА», шифр «02-07.45:2026», «… Редакція N»), або (в) складаються лише з цифр
 * (номер сторінки); у кінці сторінки — рядки лише з цифр і рядки, що повторюються в кінці щонайменше половини сторінок.
 */
public final class PdfText {

    private PdfText() {
    }

    /** Скільки перших непорожніх рядків сторінки перевіряємо на колонтитул. */
    public static final int HEAD_ZONE = 9;
    private static final int TAIL_ZONE = 3;

    private static final Pattern PAGE_NUMBER = Pattern.compile("\\d{1,3}");
    private static final Pattern DOC_CODE = Pattern.compile("[\\p{Lu}\\d][\\p{Lu}\\d.\\-/]*:\\d{4}");
    private static final Pattern HEADER_WORDS = Pattern.compile(
            ".*(ЕЛЕКТРОННИЙ ФОНД|ЛОКАЛЬНИХ|НОРМАТИВНО-|ПРАВОВИХ|ДОКУМЕНТІВ|НАЦІОНАЛЬНОГО УНІВЕРСИТЕТУ|ВАСИЛЯ СТЕФАНИКА|ІМЕНІ).*");
    private static final Pattern EDITION = Pattern.compile(".*\\p{Lu}{3,}.*Редакція\\s+\\d+");

    /** Текст кожної сторінки PDF (PDFBox 3: Loader.loadPDF + PDFTextStripper по сторінці). */
    public static List<String> pages(byte[] pdf) throws IOException {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            PDFTextStripper s = new PDFTextStripper();
            List<String> out = new ArrayList<>();
            for (int p = 1; p <= d.getNumberOfPages(); p++) {
                s.setStartPage(p);
                s.setEndPage(p);
                out.add(s.getText(d));
            }
            return out;
        }
    }

    /** Рядок, схожий на колонтитул: великими літерами, зі словами фонду/університету, шифр документа або «… Редакція N». */
    static boolean looksLikeHeader(String line) {
        String l = line.strip();
        if (l.isEmpty()) return false;
        if (DOC_CODE.matcher(l).matches() || EDITION.matcher(l).matches()) return true;
        return isUpper(l) && HEADER_WORDS.matcher(l).matches();
    }

    private static boolean isUpper(String s) {
        int up = 0, letters = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) up++;
            }
        }
        return letters > 0 && up >= letters * 0.8;
    }

    private static String key(String line) {
        return line.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    /**
     * Прибирає колонтитули й номери сторінок. Вхід — сирий текст сторінок, вихід — сторінки з непорожніх рядків без пробілів
     * у кінці (порожні рядки PDF теж прибираються: абзаців PDF однаково не позначає).
     */
    public static List<String> clean(List<String> rawPages) {
        List<List<String>> pages = new ArrayList<>();
        for (String raw : rawPages) {
            List<String> lines = new ArrayList<>();
            for (String l : raw.split("\\r?\\n")) {
                String t = l.stripTrailing();
                if (!t.isBlank()) lines.add(t.strip());
            }
            pages.add(lines);
        }
        Map<String, Integer> headFreq = new HashMap<>(), tailFreq = new HashMap<>();
        for (List<String> lines : pages) {
            lines.subList(0, Math.min(HEAD_ZONE, lines.size())).stream().map(PdfText::key).distinct().forEach(k -> headFreq.merge(k, 1, Integer::sum));
            lines.subList(Math.max(0, lines.size() - TAIL_ZONE), lines.size()).stream().map(PdfText::key).distinct().forEach(k -> tailFreq.merge(k, 1, Integer::sum));
        }
        int n = pages.size();
        int need = Math.max(2, (n + 1) / 2);
        List<String> out = new ArrayList<>();
        for (List<String> lines : pages) {
            int from = 0, to = lines.size();
            while (from < to && from < HEAD_ZONE) {
                String l = lines.get(from);
                boolean frequent = n >= 3 && headFreq.getOrDefault(key(l), 0) >= need && !PAGE_NUMBER.matcher(l.strip()).matches();
                if (frequent || looksLikeHeader(l) || PAGE_NUMBER.matcher(l.strip()).matches()) from++;
                else break;
            }
            while (to > from && to > lines.size() - TAIL_ZONE) {
                String l = lines.get(to - 1);
                boolean frequent = n >= 3 && tailFreq.getOrDefault(key(l), 0) >= need;
                if (PAGE_NUMBER.matcher(l.strip()).matches() || frequent) to--;
                else break;
            }
            out.add(String.join("\n", lines.subList(from, to)));
        }
        return out;
    }
}
