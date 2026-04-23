package com.harcor;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.miscellaneous.ConcatenateGraphFilter;
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
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IntsRefBuilder;
import org.apache.lucene.util.fst.ByteSequenceOutputs;
import org.apache.lucene.util.fst.FST;
import org.apache.lucene.util.fst.FSTCompiler;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Lucene Text Tagger — three explicit steps:
 *
 *   Step 1: createIndex()          — build a ByteBuffersDirectory (in-memory Lucene index)
 *   Step 2: addData()              — write phrase documents via IndexWriter
 *   Step 3: TextTagger.fromIndex() — read all docs via DirectoryReader and compile a
 *                                    Lucene FST (Finite State Transducer); then call
 *                                    tagger.tag() which walks the FST arc-by-arc on the
 *                                    analyzed input — no query engine, no HashMap.
 *
 * Build:  mvn package -q
 * Run:    java -jar target/tagger.jar
 */
public class App {

    // ── Lucene index field names ─────────────────────────────────────────────
    static final String F_TEXT = "phrase";   // analyzed TextField (stored for re-reading)
    static final String F_ID   = "id";       // stored entity identifier
    static final String F_TYPE = "type";     // stored entity type label

    // ── Analyzer ─────────────────────────────────────────────────────────────
    // Must be IDENTICAL at index time and at tag time — otherwise FST keys
    // won't align with tag-time tokens.
    // Chain: split on whitespace/punctuation → lowercase → fold accents.
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

    /** Run text through the analyzer and return a flat list of token strings. */
    static List<String> analyze(String text, Analyzer analyzer) throws IOException {
        List<String> out = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream(F_TEXT, new StringReader(text))) {
            CharTermAttribute termAttr = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) out.add(termAttr.toString());
            stream.end();
        }
        return out;
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
     * Each document represents one surface form of an entity.
     *
     *   TextField   "phrase" — analyzed AND stored (TextTagger re-reads it)
     *   StoredField "id"     — entity URI / identifier
     *   StoredField "type"   — entity type label
     */
    static void addData(Directory dir, Analyzer analyzer, List<String[]> phrases)
            throws IOException {

        System.out.println("Step 2: Adding data with IndexWriter");
        IndexWriterConfig cfg = new IndexWriterConfig(analyzer);
        cfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

        try (IndexWriter writer = new IndexWriter(dir, cfg)) {
            for (String[] row : phrases) {
                Document doc = new Document();
                doc.add(new TextField(F_TEXT, row[0], Field.Store.YES));
                doc.add(new StoredField(F_ID,   row[1]));
                doc.add(new StoredField(F_TYPE, row[2]));
                writer.addDocument(doc);
            }
            writer.commit();
        }

        System.out.println("        " + phrases.size() + " phrase documents committed.\n");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TextTagger  —  Lucene FST-backed phrase dictionary
    // ═════════════════════════════════════════════════════════════════════════

    /** A matched entity span in the original input text. */
    record Tag(int start, int end, String surface, String id, String type) {}

    /**
     * TextTagger compiles the phrase dictionary into a Lucene FST
     * (Finite State Transducer) and matches text by walking the FST arc-by-arc
     * over the analyzed token stream.
     *
     * ── What is a Lucene FST? ─────────────────────────────────────────────
     * An FST is a deterministic finite automaton where:
     *   • Each transition (arc) is labelled with one byte and may carry a
     *     partial output value.
     *   • Walking arcs from the root to an accept state spells out a key
     *     (concatenation of arc labels) and produces an output value
     *     (sum of arc outputs + the final-state output).
     *   • Common prefixes and suffixes are shared, giving very compact RAM use.
     * Lucene uses the same FST internally for its terms dictionary and for
     * SolrTextTagger's phrase index.
     *
     * ── FST key encoding via ConcatenateGraphFilter ───────────────────────
     * Each phrase is stored in the FST as a byte sequence produced by passing
     * the phrase through the standard analyzer chain followed by
     * ConcatenateGraphFilter:
     *
     *   StandardTokenizer → LowerCaseFilter → ASCIIFoldingFilter
     *       → ConcatenateGraphFilter
     *
     * ConcatenateGraphFilter consumes the full token graph and emits a single
     * token with all terms joined by SEP_LABEL (U+001E, byte 0x1E):
     *
     *   token₁  +  0x1E  +  token₂  +  0x1E  +  …  +  tokenₙ
     *
     * Using ConcatenateGraphFilter is idiomatic Lucene: it is the same
     * approach used by SolrTextTagger and Lucene's CompletionField to build
     * FST keys that correctly handle synonym graphs and position gaps.
     *
     * ── FST output encoding ───────────────────────────────────────────────
     * Outputs use ByteSequenceOutputs (prefix-factoring of BytesRef values).
     * Each accept state carries "id₁\ttype₁\nid₂\ttype₂\n…" — multiple
     * entity records are newline-joined for the rare case where two surface
     * forms analyze to the same token sequence.
     *
     * ── Construction (fromIndex) ─────────────────────────────────────────
     *   1. Open the Lucene index with DirectoryReader — no query engine needed.
     *   2. For each stored phrase, pipe it through the analyzer +
     *      ConcatenateGraphFilter to get the canonical FST key bytes.
     *   3. Collect (BytesRef key → "id\ttype" value) pairs in a TreeMap
     *      so FSTCompiler receives inputs in ascending lexicographic order.
     *   4. Feed into FSTCompiler; call compile() → FSTMetadata, then wrap
     *      with FST.fromFSTReader() to get the arc-walking FST object.
     *
     * ── Tagging (tag) ────────────────────────────────────────────────────
     *   1. Analyze the raw input; record each token's original char offsets.
     *   2. At each position i, walk the FST from the root:
     *        • Feed bytes of tokens[i], tokens[i+1], … one byte at a time.
     *        • Insert SEP between successive tokens.
     *        • After each complete token, if arc.isFinal() → record a match.
     *        • Stop when findTargetArc() returns null (no extension possible).
     *   3. Take the longest recorded match (forward maximum match).
     *   4. Emit one Tag per entity record, advance cursor, repeat.
     *      No IndexSearcher.  No PhraseQuery.  No HashMap.
     */
    static class TextTagger {

        /**
         * Token separator used inside FST keys.
         * Must equal ConcatenateGraphFilter.SEP_LABEL (U+001E, byte 0x1E) so that
         * keys produced at index time (via ConcatenateGraphFilter) and bytes fed
         * at tag time (manually, one token at a time) are identical.
         */
        private static final int SEP = ConcatenateGraphFilter.SEP_LABEL;

        private final FST<BytesRef> fst;
        private final Analyzer      analyzer;

        private TextTagger(FST<BytesRef> fst, Analyzer analyzer) {
            this.fst      = fst;
            this.analyzer = analyzer;
        }

        // ── Key helper ────────────────────────────────────────────────────────

        /**
         * Produce the FST key for a dictionary phrase by piping it through the
         * base analyzer followed by ConcatenateGraphFilter.
         *
         * ConcatenateGraphFilter joins all tokens in the graph into a single
         * token using SEP_LABEL (0x1E) as the delimiter, and returns the result
         * via BytesTermAttribute.  The raw BytesRef is the exact byte sequence
         * that will be walked arc-by-arc during tagging.
         *
         * Returns null if the phrase produces no tokens (stop-word-only phrases).
         */
        private static BytesRef keyFromPhrase(String phrase, Analyzer analyzer)
                throws IOException {
            TokenStream base = analyzer.tokenStream(F_TEXT, new StringReader(phrase));
            try (ConcatenateGraphFilter concat = new ConcatenateGraphFilter(base)) {
                // ConcatenateGraphFilter exposes its output via a shared CharTermAttribute.
                // (It uses an internal BytesRefBuilderTermAttribute privately; the public
                //  BytesTermAttribute slot is unrelated and is never populated by it.)
                CharTermAttribute charAttr = concat.addAttribute(CharTermAttribute.class);
                concat.reset();
                BytesRef result = null;
                if (concat.incrementToken()) {
                    // The CharTermAttribute contains tokens joined by SEP_LABEL (0x1E).
                    // SEP_LABEL < 0x80, so its UTF-8 encoding is the single byte 0x1E —
                    // identical to the byte we feed into the FST arc walker at tag time.
                    result = new BytesRef(charAttr.toString().getBytes(StandardCharsets.UTF_8));
                }
                concat.end();   // must be called before close()
                return result;
            }
        }

        // ── FST construction ──────────────────────────────────────────────────

        /**
         * Read every stored document from the Lucene index and compile the
         * phrase dictionary into an FST.
         *
         * Lucene 9.10 FST construction pattern:
         *   FSTCompiler<T>.compile() → FST.FSTMetadata<T>
         *   FST.fromFSTReader(metadata, compiler.getFSTReader()) → FST<T>
         */
        static TextTagger fromIndex(Directory dir, Analyzer analyzer) throws IOException {

            System.out.println("Step 3a: Building FST from index via DirectoryReader");

            // ① Read all documents into a sorted map.
            //    FSTCompiler requires inputs in ascending lexicographic order —
            //    TreeMap's natural BytesRef ordering satisfies this.
            //    Entries whose phrases analyze to the same token sequence
            //    (e.g. "Zürich" / "Zurich" both → "zurich") are merged into
            //    one value string with newline separation.  A LinkedHashSet
            //    per key prevents duplicate records (same id+type, different surface).
            TreeMap<BytesRef, LinkedHashSet<String>> sortedDict = new TreeMap<>();

            try (DirectoryReader reader = DirectoryReader.open(dir)) {
                System.out.println("         " + reader.numDocs() + " documents in index");

                for (int docId = 0; docId < reader.maxDoc(); docId++) {
                    Document doc    = reader.storedFields().document(docId);
                    String   phrase = doc.get(F_TEXT);
                    String   id     = doc.get(F_ID);
                    String   type   = doc.get(F_TYPE);
                    if (phrase == null || phrase.isBlank()) continue;

                    // Pipe the phrase through the analyzer + ConcatenateGraphFilter.
                    // This produces the canonical FST key bytes with 0x1E separators —
                    // the same bytes we will feed arc-by-arc during tagging.
                    BytesRef key = keyFromPhrase(phrase, analyzer);
                    if (key == null || key.length == 0) continue;

                    sortedDict.computeIfAbsent(key, k -> new LinkedHashSet<>())
                              .add(id + "\t" + type);   // Set deduplicates same id+type
                }
            }

            // ② Compile the FST using ByteSequenceOutputs (prefix-factoring BytesRef).
            ByteSequenceOutputs outputs = ByteSequenceOutputs.getSingleton();
            FSTCompiler<BytesRef> compiler =
                new FSTCompiler.Builder<>(FST.INPUT_TYPE.BYTE1, outputs).build();

            IntsRefBuilder scratchInts = new IntsRefBuilder();

            for (Map.Entry<BytesRef, LinkedHashSet<String>> entry : sortedDict.entrySet()) {
                BytesRef key   = entry.getKey();
                BytesRef value = new BytesRef(
                    String.join("\n", entry.getValue()).getBytes(StandardCharsets.UTF_8));

                // Convert byte key to IntsRef (each byte becomes one int 0–255)
                scratchInts.clear();
                for (int i = 0; i < key.length; i++) {
                    scratchInts.append(key.bytes[key.offset + i] & 0xFF);
                }

                compiler.add(scratchInts.get(), value);
            }

            // ③ Retrieve the compiled FST.
            //    In Lucene 9.10 compile() returns FSTMetadata; wrap it with
            //    FST.fromFSTReader() to get the arc-walking FST object.
            FST.FSTMetadata<BytesRef> meta = compiler.compile();
            FST<BytesRef>             fst  = FST.fromFSTReader(meta, compiler.getFSTReader());

            System.out.printf("         FST compiled: %d unique token-sequences, %d bytes RAM%n%n",
                sortedDict.size(), fst.ramBytesUsed());

            return new TextTagger(fst, analyzer);
        }

        // ── Tagging ───────────────────────────────────────────────────────────

        /**
         * Tag a piece of text by walking the FST arc-by-arc over the token stream.
         *
         * For each starting token position i:
         *   • Reset to the FST root arc.
         *   • Feed bytes of token[i] through findTargetArc() one byte at a time.
         *   • Insert SEP (0x01) between successive tokens.
         *   • After each complete token, check arc.isFinal() → dictionary match.
         *   • Continue extending until findTargetArc() returns null.
         *   • Emit the longest match (forward maximum match / greedy).
         */
        List<Tag> tag(String text) throws IOException {

            // Tokenize the input; keep original char offsets for span extraction
            List<String> tokens  = new ArrayList<>();
            List<int[]>  offsets = new ArrayList<>();

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

            // Pre-encode all token strings to UTF-8 bytes once
            List<byte[]> tokenBytes = new ArrayList<>();
            for (String t : tokens) tokenBytes.add(t.getBytes(StandardCharsets.UTF_8));

            FST.Arc<BytesRef>   arc       = new FST.Arc<>();
            FST.BytesReader     fstReader = fst.getBytesReader();
            List<Tag>           results   = new ArrayList<>();

            int i = 0;
            while (i < tokens.size()) {
                fst.getFirstArc(arc);
                // ByteSequenceOutputs distributes output bytes across arcs during FST
                // compilation (prefix factoring).  We must accumulate every arc's output
                // along the path to reconstruct the full value at any accept state.
                BytesRef accumulated = fst.outputs.getNoOutput();

                int      matchEndJ = -1;    // token index of the last accepted state
                BytesRef matchOut  = null;  // FST output at that state

                outer:
                for (int j = i; j < tokens.size(); j++) {

                    // Insert the inter-token separator before every token after the first
                    if (j > i) {
                        if (fst.findTargetArc(SEP, arc, arc, fstReader) == null) break;
                        accumulated = fst.outputs.add(accumulated, arc.output());
                    }

                    // Feed each byte of tokens[j] through the FST, accumulating outputs
                    for (byte b : tokenBytes.get(j)) {
                        if (fst.findTargetArc(b & 0xFF, arc, arc, fstReader) == null) {
                            break outer;   // FST rejected this byte — no further extension
                        }
                        accumulated = fst.outputs.add(accumulated, arc.output());
                    }

                    // arc.isFinal() → current path is an accept state (a dictionary match).
                    // Combine accumulated path output with the final-state residual output.
                    if (arc.isFinal()) {
                        matchEndJ = j;
                        matchOut = fst.outputs.add(accumulated, arc.nextFinalOutput());
                    }
                }

                if (matchEndJ >= 0) {
                    int    start   = offsets.get(i)[0];
                    int    end     = offsets.get(matchEndJ)[1];
                    String surface = text.substring(start, end);

                    // Decode: "id₁\ttype₁\nid₂\ttype₂\n…"
                    String combined = new String(
                        matchOut.bytes, matchOut.offset, matchOut.length, StandardCharsets.UTF_8);
                    for (String record : combined.split("\n")) {
                        String[] fields = record.split("\t", 2);
                        results.add(new Tag(start, end, surface, fields[0], fields[1]));
                    }

                    i = matchEndJ + 1;   // advance cursor past the matched span
                } else {
                    i++;
                }
            }

            return results;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Main
    // ═════════════════════════════════════════════════════════════════════════

    public static void main(String[] args) throws Exception {

        // Phrase dictionary: { surface phrase, entity id, entity type }
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

        String[] inputs = {
            "Acme Corp uses Apache Lucene for search in New York City.",
            "Ada Lovelace would have loved Kubernetes and Elasticsearch.",
            "Google and Microsoft both operate in the United States and United Kingdom.",
            "I flew from Zürich to Berlin — the conference is in Germany.",
            "There is nothing to tag in this sentence about the weather.",
        };

        Analyzer analyzer = buildAnalyzer();

        // Step 1: Create the index
        Directory dir = createIndex();

        // Step 2: Add data
        addData(dir, analyzer, phrases);

        // Step 3: Compile FST from index, then tag
        TextTagger tagger = TextTagger.fromIndex(dir, analyzer);

        System.out.println("Step 3b: Tagging sentences with TextTagger (FST arc walk)");
        System.out.println("         (forward maximum match — longest phrase wins)\n");

        for (String input : inputs) {
            System.out.println("  INPUT : " + input);
            List<Tag> tags = tagger.tag(input);

            if (tags.isEmpty()) {
                System.out.println("  TAGS  : (none)\n");
            } else {
                for (Tag t : tags) {
                    System.out.printf("  TAG   : [%d-%d] \"%-20s\" id=%-12s type=%s%n",
                        t.start(), t.end(), t.surface(), t.id(), t.type());
                }
                System.out.println();
            }
        }

        dir.close();
        analyzer.close();
    }
}
