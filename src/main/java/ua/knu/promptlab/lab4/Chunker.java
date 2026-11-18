package ua.knu.promptlab.lab4;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Лабораторна 4, крок 2. Три нарізки (слайди 10–11 лекції 4):
 * <ul>
 *   <li>(а) {@link Strategy#FIXED} — фіксована: шматки по {@value #SIZE} знаків, кожен наступний починається на {@value #STEP} знаків
 *       далі, тож сусіди мають {@value #OVERLAP} спільних знаків (перекриття). Префікс документа — {@code title: none};</li>
 *   <li>(б) {@link Strategy#POINTS} — за пунктами: новий шматок починається з рядка «N.», «N.N.» або «N.N.N.» (регулярний вираз
 *       {@link #POINT}); заголовок розділу без тексту приєднується до наступного пункту; пункт довший за {@value #SIZE} знаків
 *       ріжемо на шматки до {@value #SIZE} знаків по межах речень. Префікс — {@code title: none}, лише текст пункту;</li>
 *   <li>(в) {@link Strategy#POINTS_HEADER} — ті самі шматки, що (б), але «з адресою»: у префіксі {@code title:} — назва положення,
 *       а текст шматка починається з назви розділу («Розділ: 13. Особливості … заочній …»).</li>
 * </ul>
 */
public final class Chunker {

    public static final int SIZE = 800;
    public static final int OVERLAP = 100;
    public static final int STEP = SIZE - OVERLAP;

    /** Пункт на початку рядка: «16.», «16.2.», «16.2.1.» (і «16.2 » без крапки, як в одному з положень); далі — велика літера, лапки або дужка. */
    public static final Pattern POINT = Pattern.compile("^(\\d{1,2}(?:\\.\\d{1,2}){0,2})\\.\\s+(?=[\\p{Lu}«\"„(])|^(\\d{1,2}(?:\\.\\d{1,2}){1,2})\\s+(?=[\\p{Lu}«\"„(])");
    /** Заголовок «Розділ II. …». */
    static final Pattern ROZDIL = Pattern.compile("^(?:Розділ|РОЗДІЛ)\\s+([IVXLCІ]+|\\d+)\\.?\\s*(.*)$");
    /** Межа речення: після «.», «;», «!», «?» — але не після номера пункту («16.2.») і поширених скорочень («п.», «ст.», «р.»). */
    private static final Pattern SENTENCE_END = Pattern.compile(
            "(?<=[.;!?])(?<!\\b\\d{1,2}\\.)(?<!\\b\\d{1,2}\\.\\d{1,2}\\.)(?<!\\b\\d{1,2}\\.\\d{1,2}\\.\\d{1,2}\\.)(?<!\\bп\\.)(?<!\\bст\\.)(?<!\\bр\\.)(?<!\\bм\\.)\\s+");
    /** Шматки, коротші за це число знаків (уламки на кшталт «2026 рік» чи номера без тексту), відкидаються. */
    public static final int MIN_CHUNK = 40;
    /** Пункти передмови (не заголовки розділів). */
    private static final Pattern PREFACE_WORDS = Pattern.compile("(?i).*(ВНЕСЕНО|РОЗРОБНИК|РОЗГЛЯНУТО|СХВАЛЕНО|ВВЕДЕНО|УПРОВАДЖЕНО|НА ЗАМІНУ).*");

    public enum Strategy {
        FIXED("а", "фіксована 800 знаків, перекриття 100", false),
        POINTS("б", "за пунктами", false),
        POINTS_HEADER("в", "за пунктами + заголовок", true);

        public final String letter;
        public final String title;
        private final boolean header;

        Strategy(String letter, String title, boolean header) {
            this.letter = letter;
            this.title = title;
            this.header = header;
        }

        public boolean withHeader() {
            return header;
        }

        /** «(в) за пунктами + заголовок». */
        public String label() {
            return "(" + letter + ") " + title;
        }
    }

    private Chunker() {
    }

    public static List<Chunk> chunk(List<Corpus.Text> docs, Strategy s) {
        List<Chunk> out = new ArrayList<>();
        for (Corpus.Text d : docs) out.addAll(s == Strategy.FIXED ? fixed(d) : points(d, s));
        return out;
    }

    // ------------------------------------------------------------------ (а) фіксована

    public static List<Chunk> fixed(Corpus.Text d) {
        String text = d.full();
        int[] pageStart = pageStarts(d);
        List<Chunk> out = new ArrayList<>();
        int n = 0;
        for (int from = 0; from < text.length(); from += STEP) {
            int to = Math.min(text.length(), from + SIZE);
            if (from > 0 && to - from <= OVERLAP) break; // хвіст уже повністю в перекритті попереднього шматка
            out.add(new Chunk(String.format("%s-f%03d", d.doc().id(), ++n), d.doc().id(), d.doc().title(), "", pageAt(pageStart, from),
                    text.substring(from, to), Strategy.FIXED));
            if (to == text.length()) break;
        }
        return out;
    }

    private static int[] pageStarts(Corpus.Text d) {
        int[] st = new int[d.pages().size()];
        int off = 0;
        for (int i = 0; i < st.length; i++) {
            st[i] = off;
            off += d.pages().get(i).length() + 1;
        }
        return st;
    }

    private static int pageAt(int[] starts, int offset) {
        int p = 0;
        for (int i = 0; i < starts.length; i++) if (starts[i] <= offset) p = i;
        return p + 1;
    }

    // ------------------------------------------------------------------ (б), (в) за пунктами

    private record Line(String text, int page) {
    }

    private static final class Unit {
        String num;
        final List<Line> lines = new ArrayList<>();
        boolean headingOnly = true;
        boolean level1;
        String section = "";
    }

    /** Номер пункту на початку рядка або null. */
    public static String pointNumber(String line) {
        Matcher m = POINT.matcher(line.strip());
        if (!m.find()) return null;
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    static boolean endsWithPunct(String s) {
        String t = s.strip();
        return !t.isEmpty() && ".;:,".indexOf(t.charAt(t.length() - 1)) >= 0;
    }

    private static boolean startsUpper(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) return Character.isUpperCase(c);
            if (Character.isDigit(c)) return true;
        }
        return false;
    }

    private static boolean allUpper(String s) {
        int up = 0, letters = 0;
        for (char c : s.toCharArray()) {
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) up++;
            }
        }
        return letters >= 3 && up == letters;
    }

    /**
     * Чи рядок пункту — заголовок розділу: після номера коротка назва з великої літери без розділового знака в кінці, а наступний
     * рядок не продовжує речення (починається з великої літери чи номера, або це короткий хвіст назви).
     */
    static boolean isHeading(String rest, String next) {
        return isHeading(rest, next, null);
    }

    /**
     * Справжній заголовок розділу рівня 1: коротка назва (до 70 знаків) з великої літери, без розділового знака в кінці, не пункт передмови;
     * наступний рядок не продовжує речення — це новий пункт, рядок з великої літери, або короткий хвіст назви (до 40 знаків), після якого
     * знову новий пункт чи велика літера. «2. Академічна стипендія у розмірі» + «…» з малої — не заголовок.
     */
    public static boolean isHeading(String rest, String next, String next2) {
        String r = rest.strip();
        if (r.isEmpty() || r.length() > 70 || !startsUpper(r) || endsWithPunct(r) || PREFACE_WORDS.matcher(r).matches()) return false;
        if (next == null) return true;
        String n = next.strip();
        if (pointNumber(n) != null || startsUpper(n) || n.startsWith("–") || n.startsWith("-")) return true;
        if (allUpper(r)) return allUpper(n);
        if (n.length() > 40 || endsWithPunct(n)) return false;
        if (next2 == null) return true;
        String n2 = next2.strip();
        return pointNumber(n2) != null || startsUpper(n2);
    }

    /**
     * Де починається текст положення: після титулки й передмови. Передмова — від рядка «ПЕРЕДМОВА» до кінця її сторінки (беремо останню
     * таку сторінку серед перших трьох); якщо передмови немає, а перша сторінка коротка й містить «ПОЛОЖЕННЯ», — пропускаємо титулку.
     * Повертає номер сторінки (з 0), з якої шукаємо заголовки розділів. Текст титулки й передмови в шматки потрапляє, але без розділу.
     */
    static int bodyStartPage(Corpus.Text d) {
        int last = -1;
        for (int p = 0; p < Math.min(3, d.pages().size()); p++) {
            for (String l : d.pages().get(p).split("\n")) if (l.strip().equals("ПЕРЕДМОВА")) last = p;
        }
        if (last >= 0) return last + 1;
        String first = d.pages().isEmpty() ? "" : d.pages().get(0);
        return first.length() < 600 && first.contains("ПОЛОЖЕННЯ") && d.pages().size() > 1 ? 1 : 0;
    }

    public static List<Chunk> points(Corpus.Text d, Strategy s) {
        List<Line> lines = new ArrayList<>();
        for (int p = 0; p < d.pages().size(); p++) {
            for (String l : d.pages().get(p).split("\n")) if (!l.isBlank()) lines.add(new Line(l.strip(), p + 1));
        }
        TreeMap<Integer, String> heads = new TreeMap<>(); // рівень → назва (лише рівень 1 і «Розділ …»)
        List<Unit> units = new ArrayList<>();
        Unit cur = new Unit();
        cur.num = "0";
        int bodyPage = bodyStartPage(d) + 1; // Line.page — з 1
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i).text();
            String next = i + 1 < lines.size() ? lines.get(i + 1).text() : null;
            String next2 = i + 2 < lines.size() ? lines.get(i + 2).text() : null;
            boolean inBody = lines.get(i).page() >= bodyPage;
            if (inBody && bodyPage > 1 && i > 0 && lines.get(i - 1).page() < bodyPage) {
                // початок тексту положення після передмови: вступ — окремий пункт, а не продовження «5. УПРОВАДЖЕНО НА ЗАМІНУ»
                units.add(cur);
                cur = new Unit();
                cur.num = "вступ";
                cur.headingOnly = false;
            }
            String num = pointNumber(l);
            Matcher rz = ROZDIL.matcher(l);
            boolean rozdil = num == null && rz.matches();
            if (num == null && !rozdil) {
                if (cur.headingOnly && cur.level1 && !cur.lines.isEmpty() && isContinuationOfHeading(cur, l)) {
                    cur.lines.add(lines.get(i));
                    String h = heads.lastEntry() == null ? null : heads.lastEntry().getValue();
                    if (h != null) heads.put(heads.lastKey(), h + " " + l);
                    cur.section = section(heads);
                    continue;
                }
                cur.lines.add(lines.get(i));
                cur.headingOnly = false;
                continue;
            }
            units.add(cur);
            cur = new Unit();
            cur.lines.add(lines.get(i));
            if (rozdil) {
                cur.num = "р" + rz.group(1);
                if (inBody) {
                    heads.clear();
                    heads.put(0, l);
                    cur.level1 = true;
                }
            } else {
                cur.num = num;
                String rest = l.substring(l.indexOf(num) + num.length()).replaceFirst("^\\.?\\s*", "");
                int level = num.split("\\.").length;
                if (level == 1 && inBody && isHeading(rest, next, next2)) {
                    heads.clear();
                    heads.put(1, num + ". " + rest);
                    cur.level1 = true;
                } else if (level == 2 && isHeading(rest, next, next2)) {
                    // «8.3. Види контролю»: назва підрозділу лишається в тексті шматка, але розділом не стає
                } else {
                    cur.headingOnly = false;
                }
            }
            cur.section = section(heads);
        }
        units.add(cur);

        // заголовок без тексту приєднуємо до наступного пункту
        List<Unit> merged = new ArrayList<>();
        List<Line> carry = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            Unit u = units.get(i);
            if (u.lines.isEmpty()) continue;
            if (u.headingOnly && i + 1 < units.size() && text(u.lines).length() < 200) {
                carry.addAll(u.lines);
                continue;
            }
            if (!carry.isEmpty()) {
                List<Line> all = new ArrayList<>(carry);
                all.addAll(u.lines);
                u.lines.clear();
                u.lines.addAll(all);
                carry.clear();
            }
            merged.add(u);
        }

        List<Chunk> out = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (Unit u : merged) {
            String base = d.doc().id() + "-" + u.num;
            int k = seen.merge(base, 1, Integer::sum);
            if (k > 1) base = base + "~" + k;
            List<String> parts = split(text(u.lines), SIZE);
            int page = u.lines.get(0).page();
            for (int j = 0; j < parts.size(); j++) {
                if (parts.get(j).strip().length() < MIN_CHUNK) continue;
                String id = parts.size() == 1 ? base : String.format("%s-%02d", base, j + 1);
                out.add(new Chunk(id, d.doc().id(), d.doc().title(), u.section, page, parts.get(j), s));
            }
        }
        return out;
    }

    private static boolean isContinuationOfHeading(Unit cur, String l) {
        String first = cur.lines.get(cur.lines.size() - 1).text();
        if (cur.lines.size() > 2 || endsWithPunct(first)) return false;
        String n = l.strip();
        return allUpper(first) ? allUpper(n) && pointNumber(n) == null : n.length() <= 50 && !startsUpper(n) && !endsWithPunct(n);
    }

    private static String section(TreeMap<Integer, String> heads) {
        List<String> parts = new ArrayList<>();
        heads.forEach((lvl, t) -> parts.add(t.replaceAll("\\s+", " ").strip()));
        return String.join(" › ", parts);
    }

    private static String text(List<Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (Line l : lines) sb.append(sb.isEmpty() ? "" : "\n").append(l.text());
        return sb.toString();
    }

    /** Ріже текст на шматки до max знаків по межах речень; речення, довше за max, — по пробілу (або жорстко). */
    public static List<String> split(String text, int max) {
        List<String> out = new ArrayList<>();
        if (text.length() <= max) {
            out.add(text);
            return out;
        }
        StringBuilder cur = new StringBuilder();
        for (String sent : SENTENCE_END.split(text)) {
            String piece = sent;
            while (piece.length() > max) {
                if (!cur.isEmpty()) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                int cut = piece.lastIndexOf(' ', max);
                if (cut < max / 2) cut = max;
                out.add(piece.substring(0, cut).strip());
                piece = piece.substring(cut).strip();
            }
            if (cur.length() + 1 + piece.length() > max && !cur.isEmpty()) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (!piece.isEmpty()) cur.append(cur.isEmpty() ? "" : " ").append(piece);
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    // ------------------------------------------------------------------ статистика

    /** Кількість шматків, середня й максимальна довжина (знаків). */
    public record Stats(int count, double avgLen, int maxLen) {
    }

    public static Stats stats(List<Chunk> cs) {
        int max = 0;
        long sum = 0;
        for (Chunk c : cs) {
            sum += c.length();
            max = Math.max(max, c.length());
        }
        return new Stats(cs.size(), cs.isEmpty() ? 0 : (double) sum / cs.size(), max);
    }

    /**
     * Приклад обірваного правила у фіксованій нарізці: шматок, що закінчується посеред слова, і початок наступного.
     * Береться перший такий шматок, що містить перше зі слів preferWords (далі — друге…), або будь-який. null — не знайдено.
     */
    public static Chunk[] tornExample(List<Chunk> fixed, String... preferWords) {
        for (String w : preferWords) {
            Chunk[] t = tornWith(fixed, w);
            if (t != null) return t;
        }
        return tornWith(fixed, null);
    }

    private static Chunk[] tornWith(List<Chunk> fixed, String preferWord) {
        for (int i = 0; i + 1 < fixed.size(); i++) {
            Chunk a = fixed.get(i), b = fixed.get(i + 1);
            if (!a.docId().equals(b.docId())) continue;
            String t = a.text();
            if (t.isEmpty() || !Character.isLetter(t.charAt(t.length() - 1))) continue;
            String bt = b.text();
            int end = t.length() - STEP; // кінець a всередині b: b починається на STEP знаків пізніше
            if (end < 0 || end >= bt.length() || !Character.isLetter(bt.charAt(end))) continue;
            if (preferWord == null || a.text().contains(preferWord)) return new Chunk[]{a, b};
        }
        return null;
    }
}
