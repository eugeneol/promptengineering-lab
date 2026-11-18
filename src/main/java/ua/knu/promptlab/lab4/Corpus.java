package ua.knu.promptlab.lab4;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Лабораторна 4. Корпус — список положень (ресурс {@code lab4/corpus.json}): id, назва, шифр, адреса PDF на efund.cnu.edu.ua,
 * кількість сторінок і SHA-256 файлу, з яким готували еталони (12.11.2026). Тексти положень у проєкт НЕ входять — їх завантажує
 * {@link CorpusLoader}. У режимі MOCK — {@code lab4/mock/corpus.json}: три короткі ВИГАДАНІ положення «Умовного університету».
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Corpus(String source, String checked, String note, List<Doc> documents) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Doc(String id, String title, String code, String url, int pages, String sha256, String resource) {
    }

    /** Один завантажений документ: опис і очищений текст по сторінках (без колонтитулів). */
    public record Text(Doc doc, List<String> pages) {
        public String full() {
            return String.join("\n", pages);
        }

        public int length() {
            return full().length();
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    public static Corpus load(boolean mock) {
        String path = mock ? "/lab4/mock/corpus.json" : "/lab4/corpus.json";
        try (InputStream in = Corpus.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Не знайдено ресурс " + path + " (src/main/resources). Maven → Reload і перезапустіть.");
            return JSON.readValue(in, Corpus.class);
        } catch (IOException e) {
            throw new IllegalStateException("Не вдалося прочитати " + path + ": " + e.getMessage(), e);
        }
    }

    public Doc doc(String id) {
        return documents.stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("немає документа " + id + " у корпусі"));
    }
}
