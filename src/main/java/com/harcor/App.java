package com.harcor;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Lucene Text Tagger — three explicit steps:
 *
 *   Step 1: createIndex()   — build a Lucene RAMDirectory from a phrase list
 *   Step 2: addData()       — write phrase documents with IndexWriter
 *   Step 3: tag()           — scan input text using IndexSearcher + PhraseQuery
 *
 * Build:  mvn package -q
 * Run:    java -jar target/tagger.jar
 */
public class App {

    // ── Field names in the Lucene index ─────────────────────────────────────
    static final String F_TEXT = "phrase";  // analyzed TextField, used for PhraseQuery
    static final String F_ID   = "id";     // stored entity ID
    static final String F_TYPE = "type";   // stored entity type

    // ── Analyzer ─────────────────────────────────────────────────────────────
    // Must be identical at index time and at tag time.
    // Rules: split on whitespace/punctuation → lowercase → fold accents.
    // No stemming — it breaks exact phrase matching.
    static Analyzer buildAnalyzer() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String field) {
                Tokenizer   t = new StandardTokenizer();
                TokenStream s = new LowerCaseFilter(t);
                s = new ASCIIFoldingFilter(s);   // "Zürich" → "zurich"
                return new TokenStreamComponents(t, s);
            }
        };
    }

    // ═════════════════════════════════════════════════════════════════════════
    // STEP 1 — Create the index
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Create an in-memory Lucene directory.
     * ByteBuffersDirectory is the modern replacement for the deprecated RAMDirectory.
     */
    static Directory createIndex() {
        System.out.println("Step 1: Creating index (ByteBuffersDirectory — in-memory)");
        Directory dir = new ByteBuffersDirectory();
        System.out.println("        Index directory created.\n");
        return dir;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // STEP 2 — Add data
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Write phrase documents into the index using IndexWriter.
     *
     * Each document represents one phrase variant (alias, abbreviation, etc.).
     * The same entity can have multiple documents — one per surface form.
     *
     *   TextField  "phrase" — analyzed, used for PhraseQuery matching
     *   StoredField "id"    — entity URI / identifier, returned with results
     *   StoredField "type"  — entity type label, returned with results
     */
    static void addData(Directory dir, Analyzer analyzer, List<String[]> phrases)
            throws IOException {

        System.out.println("Step 2: Adding data with IndexWriter");

        IndexWriterConfig cfg = new IndexWriterConfig(analyzer);
        cfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

        try (IndexWriter writer = new IndexWriter(dir, cfg)) {
            for (String[] row : phrases) {
                // row = { phrase, id, type }
                Document doc = new Document();
                doc.add(new TextField(F_TEXT, row[0], Field.Store.YES));  // analyzed
                doc.add(new StoredField(F_ID,   row[1]));                  // stored as-is
                doc.add(new StoredField(F_TYPE, row[2]));                  // stored as-is
                writer.addDocument(doc);
            }
            writer.commit();
        }

        System.out.println("        " + phrases.size() + " phrase documents committed.\n");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // STEP 3 — Tag text
    // ═════════════════════════════════════════════════════════════════════════

    /** A matched tag: character offsets + the fields from the matching document. */
    record Tag(int start, int end, String surface, String id, String type) {}

    /**
     * Scan input text for dictionary phrases using IndexSearcher + PhraseQuery.
     *
     * Algorithm — forward maximum match:
     *   1. Analyze the input text into tokens with their original character offsets.
     *   2. At each token position, try phrase windows from longest to shortest.
     *   3. For each window, build a PhraseQuery and run it against the index.
     *   4. First (longest) window that hits → emit a Tag and skip past it.
     *
     * This is the same strategy used by SolrTextTagger internally.
     */
    static List<Tag> tag(String text, Directory dir, Analyzer analyzer)
            throws IOException {

        // Analyze the input: collect tokens + their original character offsets
        List<String> tokens  = new ArrayList<>();
        List<int[]>  offsets = new ArrayList<>();   // [startChar, endChar] per token

        try (TokenStream stream = analyzer.tokenStream(F_TEXT, new StringReader(text))) {
            CharTermAttribute termAttr   = stream.addAttribute(CharTermAttribute.class);
            OffsetAttribute   offsetAttr = stream.addAttribute(OffsetAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                tokens.add(termAttr.toString());
                offsets.add(new int[]{ offsetAttr.startOffset(), offsetAttr.endOffset() });
            }
            stream.end();
        }

        // Open the index for searching
        List<Tag> results = new ArrayList<>();

        try (DirectoryReader reader = DirectoryReader.open(dir)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            int maxPhraseLen = 5;  // longest phrase in tokens to try

            for (int i = 0; i < tokens.size(); i++) {
                boolean matched = false;

                // Try longest window first → guarantees longest-match-wins
                for (int len = Math.min(maxPhraseLen, tokens.size() - i);
                     len >= 1 && !matched; len--) {

                    // Build a PhraseQuery for this token window
                    PhraseQuery.Builder builder = new PhraseQuery.Builder();
                    for (int j = 0; j < len; j++) {
                        builder.add(new Term(F_TEXT, tokens.get(i + j)), j);
                    }
                    PhraseQuery query = builder.build();

                    TopDocs hits = searcher.search(query, 10);
                    if (hits.totalHits.value > 0) {
                        int    start   = offsets.get(i)[0];
                        int    end     = offsets.get(i + len - 1)[1];
                        String surface = text.substring(start, end);

                        for (var scoreDoc : hits.scoreDocs) {
                            Document doc = reader.storedFields().document(scoreDoc.doc);
                            results.add(new Tag(
                                start, end, surface,
                                doc.get(F_ID),
                                doc.get(F_TYPE)
                            ));
                        }

                        i += len - 1;   // advance past matched span (loop adds 1 more)
                        matched = true;
                    }
                }
            }
        }

        results.sort(Comparator.comparingInt(Tag::start));
        return results;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Main
    // ═════════════════════════════════════════════════════════════════════════

    public static void main(String[] args) throws Exception {

        // ── Phrase dictionary: { phrase, id, type } ─────────────────────────
        List<String[]> phrases = List.of(
            new String[]{ "New York City",    "geo:nyc",   "CITY"    },
            new String[]{ "New York",         "geo:nyc",   "CITY"    },
            new String[]{ "NYC",              "geo:nyc",   "CITY"    },
            new String[]{ "Los Angeles",      "geo:la",    "CITY"    },
            new String[]{ "San Francisco",    "geo:sf",    "CITY"    },
            new String[]{ "London",           "geo:ldn",   "CITY"    },
            new String[]{ "Berlin",           "geo:ber",   "CITY"    },
            new String[]{ "Zürich",           "geo:zur",   "CITY"    },
            new String[]{ "Zurich",           "geo:zur",   "CITY"    },
            new String[]{ "United States",    "cnt:us",    "COUNTRY" },
            new String[]{ "United Kingdom",   "cnt:uk",    "COUNTRY" },
            new String[]{ "Germany",          "cnt:de",    "COUNTRY" },
            new String[]{ "Acme Corporation", "org:acme",  "ORG"     },
            new String[]{ "Acme Corp",        "org:acme",  "ORG"     },
            new String[]{ "Google",           "org:goog",  "ORG"     },
            new String[]{ "Amazon",           "org:amzn",  "ORG"     },
            new String[]{ "Microsoft",        "org:msft",  "ORG"     },
            new String[]{ "Apache Lucene",    "sw:lucene", "PRODUCT" },
            new String[]{ "Lucene",           "sw:lucene", "PRODUCT" },
            new String[]{ "Apache Solr",      "sw:solr",   "PRODUCT" },
            new String[]{ "Elasticsearch",    "sw:es",     "PRODUCT" },
            new String[]{ "Kubernetes",       "sw:k8s",    "PRODUCT" },
            new String[]{ "Ada Lovelace",     "per:ada",   "PERSON"  },
            new String[]{ "Alan Turing",      "per:tur",   "PERSON"  },
            new String[]{ "Donald Knuth",     "per:knu",   "PERSON"  }
        );

        // ── Input sentences to tag ───────────────────────────────────────────
        String[] inputs = {
            "Acme Corp uses Apache Lucene for search in New York City.",
            "Ada Lovelace would have loved Kubernetes and Elasticsearch.",
            "Google and Microsoft both operate in the United States and United Kingdom.",
            "I flew from Zürich to Berlin — the conference is in Germany.",
            "There is nothing to tag in this sentence about the weather.",
        };

        Analyzer analyzer = buildAnalyzer();

        // ── Step 1: Create the index ─────────────────────────────────────────
        Directory dir = createIndex();

        // ── Step 2: Add data ─────────────────────────────────────────────────
        addData(dir, analyzer, phrases);

        // ── Step 3: Tag each sentence ────────────────────────────────────────
        System.out.println("Step 3: Tagging sentences with IndexSearcher + PhraseQuery");
        System.out.println("        (forward maximum match — longest phrase wins)\n");

        for (String input : inputs) {
            System.out.println("  INPUT : " + input);

            List<Tag> tags = tag(input, dir, analyzer);

            if (tags.isEmpty()) {
                System.out.println("  TAGS  : (none)\n");
            } else {
                // Deduplicate: same span may match multiple index docs (aliases)
                String lastSpan = null;
                for (Tag t : tags) {
                    String span = t.start() + "-" + t.end();
                    if (span.equals(lastSpan)) continue;   // already printed this span
                    System.out.printf("  TAG   : [%d-%d] \"%-20s\" id=%-12s type=%s%n",
                        t.start(), t.end(), t.surface(), t.id(), t.type());
                    lastSpan = span;
                }
                System.out.println();
            }
        }

        dir.close();
        analyzer.close();
    }
}
