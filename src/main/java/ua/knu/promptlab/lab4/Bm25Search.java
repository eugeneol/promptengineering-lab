package ua.knu.promptlab.lab4;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Лабораторна 4, крок 4. Пошук за словами BM25 (слайд 13) у бібліотеці Lucene: індекс у пам'яті, BM25Similarity з k1 = 1,2
 * (наскільки швидко «насичуються» повтори слова) і b = 0,75 (наскільки довгі шматки штрафуються).
 *
 * <p>Аналізатор (як текст ріжеться на слова): за замовчуванням {@link StandardAnalyzer} — слова нижнім регістром, без стоп-слів;
 * «перескладання» і «перескладати» для нього — різні слова. Бонус — UkrainianMorfologikAnalyzer (бібліотека
 * lucene-analysis-morfologik): зводить форми слова до початкової (лематизація), тож «перескладати» знайде «перескладання».
 * Запит — ті самі слова, з'єднані через OR (BooleanQuery з SHOULD), без окремого парсера запитів.
 */
public final class Bm25Search implements AutoCloseable {

    public static final float K1 = 1.2f;
    public static final float B = 0.75f;

    private final List<Chunk> chunks;
    private final Analyzer analyzer;
    private final ByteBuffersDirectory dir = new ByteBuffersDirectory();
    private final DirectoryReader reader;
    private final IndexSearcher searcher;
    private final String analyzerName;

    public Bm25Search(List<Chunk> chunks, boolean morfologik) {
        this.chunks = chunks;
        this.analyzer = morfologik ? ukrainian() : new StandardAnalyzer();
        this.analyzerName = morfologik ? "UkrainianMorfologikAnalyzer (лематизація)" : "StandardAnalyzer (нижній регістр, без лематизації)";
        try {
            IndexWriterConfig cfg = new IndexWriterConfig(analyzer).setSimilarity(new BM25Similarity(K1, B));
            try (IndexWriter w = new IndexWriter(dir, cfg)) {
                for (int i = 0; i < chunks.size(); i++) {
                    Document doc = new Document();
                    doc.add(new StoredField("i", i));
                    doc.add(new TextField("text", chunks.get(i).searchText(), Field.Store.NO));
                    w.addDocument(doc);
                }
            }
            reader = DirectoryReader.open(dir);
            searcher = new IndexSearcher(reader);
            searcher.setSimilarity(new BM25Similarity(K1, B));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** UkrainianMorfologikAnalyzer, якщо бібліотека є в classpath (через рефлексію — щоб основна лаба не залежала від бонусу). */
    static Analyzer ukrainian() {
        try {
            return (Analyzer) Class.forName("org.apache.lucene.analysis.uk.UkrainianMorfologikAnalyzer").getConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("UkrainianMorfologikAnalyzer недоступний (" + e + "). Перевірте залежність "
                    + "lucene-analysis-morfologik у pom.xml і Maven → Reload.", e);
        }
    }

    /** Чи доступний бонусний аналізатор. */
    public static boolean morfologikAvailable() {
        try {
            ukrainian().close();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public String analyzerName() {
        return analyzerName;
    }

    /** Слова запиту після аналізатора (для консолі й тестів). */
    public List<String> terms(String text) {
        Set<String> out = new LinkedHashSet<>();
        try (TokenStream ts = analyzer.tokenStream("text", text)) {
            CharTermAttribute t = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) out.add(t.toString());
            ts.end();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new ArrayList<>(out);
    }

    public List<VectorSearch.Hit> search(String query, int k) {
        BooleanQuery.Builder q = new BooleanQuery.Builder();
        for (String t : terms(query)) q.add(new TermQuery(new Term("text", t)), BooleanClause.Occur.SHOULD);
        try {
            List<VectorSearch.Hit> out = new ArrayList<>();
            for (ScoreDoc sd : searcher.search(q.build(), k).scoreDocs) {
                int i = searcher.storedFields().document(sd.doc).getField("i").numericValue().intValue();
                out.add(new VectorSearch.Hit(chunks.get(i), sd.score));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        try {
            reader.close();
            dir.close();
            analyzer.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
