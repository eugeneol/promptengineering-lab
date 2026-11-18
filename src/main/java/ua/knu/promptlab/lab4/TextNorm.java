package ua.knu.promptlab.lab4;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Лабораторна 4. Нормалізація тексту для порівняння «чи містить шматок еталонний фрагмент» і «чи є ключовий факт у відповіді».
 *
 * <p>Текст із PDF і фрагмент, скопійований людиною, відрізняються дрібницями: регістр, переноси рядків, перенос слова «-» в кінці
 * рядка, різні апострофи й лапки. Тому порівнюємо не сирий текст, а нормалізований:
 * <ol>
 *   <li>прибрати перенос слова: «-» в кінці рядка + наступний рядок → слово разом («незадо-\nвільно» → «незадовільно»);</li>
 *   <li>нижній регістр;</li>
 *   <li>однакові апострофи (’ ʼ ` → '), лапки («» „ “ ” → "), тире (– — − → -), «№ 3» → «№3»;</li>
 *   <li>усі пробіли й переноси рядків → один пробіл.</li>
 * </ol>
 */
public final class TextNorm {

    private TextNorm() {
    }

    private static final Pattern WORD_BREAK = Pattern.compile("(?<=\\p{L})-[ \\t]*\\r?\\n[ \\t]*(?=\\p{Ll})");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern NUMERO = Pattern.compile("№\\s+");

    public static String normalize(String s) {
        if (s == null) return "";
        String t = s.replace("­", "");
        t = WORD_BREAK.matcher(t).replaceAll("");
        t = t.toLowerCase(Locale.ROOT);
        t = t.replace('’', '\'').replace('ʼ', '\'').replace('`', '\'').replace('′', '\'')
                .replace('«', '"').replace('»', '"').replace('„', '"').replace('“', '"').replace('”', '"')
                .replace('–', '-').replace('—', '-').replace('−', '-').replace('‑', '-')
                .replace(' ', ' ');
        t = NUMERO.matcher(t).replaceAll("№");
        t = SPACES.matcher(t).replaceAll(" ");
        return t.strip();
    }

    /** Чи містить текст фрагмент (обидва нормалізуються). Порожній фрагмент — false. */
    public static boolean containsNormalized(String text, String fragment) {
        String f = normalize(fragment);
        return !f.isEmpty() && normalize(text).contains(f);
    }

    /**
     * Ключовий факт: один або кілька допустимих варіантів через «|» (наприклад, «двох робочих|2 робочих»). Є у тексті, якщо є будь-який
     * варіант, і шукаємо його <b>по межах слів</b>: варіант має починатися з початку слова (тож «коміс» знайде «комісії», а «тест» не знайде
     * «атестація»; тому короткі «ста», «так» як факти заборонені — тест варіантів), а якщо закінчується цифрою — то й закінчуватися на межі числа (тож «12» не знайде «120»).
     */
    public static boolean hasFact(String text, String fact) {
        String t = normalize(text);
        for (String alt : fact.split("\\|")) {
            String a = normalize(alt);
            if (a.isEmpty()) continue;
            String tail = Character.isDigit(a.charAt(a.length() - 1)) ? "(?![\\p{N}])" : "";
            if (Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(a) + tail).matcher(t).find()) return true;
        }
        return false;
    }
}
