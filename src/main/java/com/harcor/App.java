package com.harcor;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.miscellaneous.ConcatenateGraphFilter;
import org.apache.lucene.analysis.pattern.PatternReplaceCharFilter;
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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    static final String F_TEXT   = "phrase";   // analyzed TextField (stored for re-reading)
    static final String F_ID     = "id";       // stored entity identifier
    static final String F_TYPE   = "type";     // stored entity type label
    static final String F_OUTPUT = "output";   // semantic output token returned in results

    // ── Analyzer ─────────────────────────────────────────────────────────────
    // Must be IDENTICAL at index time and at tag time — otherwise FST keys
    // won't align with tag-time tokens.
    //
    // Full chain (applied in order):
    //   1. PatternReplaceCharFilter — strips hyphens/dashes from the raw char
    //      stream before the tokenizer sees it, so "part-no" and "partno" both
    //      become the single token "partno".  Using a CharFilter (not a
    //      TokenFilter) preserves the original character offsets so that
    //      Tag.surface() still references the correct span in the input text.
    //   2. StandardTokenizer — splits on whitespace and non-alphanumeric
    //      boundaries (after hyphens have already been removed).
    //   3. LowerCaseFilter — folds to lowercase for case-insensitive matching.
    //   4. ASCIIFoldingFilter — folds accented characters ("Zürich" → "zurich").
    //
    // No stemming — it breaks exact phrase matching.

    /** Regex that matches the ASCII hyphen-minus and common Unicode dash characters. */
    private static final Pattern HYPHEN_PATTERN =
        Pattern.compile("[\\u002D\\u2010\\u2011\\u2012\\u2013\\u2014\\u2212]");

    static Analyzer buildAnalyzer() {
        return new Analyzer() {
            @Override
            protected Reader initReader(String fieldName, Reader reader) {
                // Strip all hyphens/dashes before the tokenizer runs.
                // Replacement is "" (empty) so "sw-lucene" → "swlucene" (one token),
                // not "sw lucene" (two tokens).
                return new PatternReplaceCharFilter(HYPHEN_PATTERN, "", reader);
            }

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

    /**
     * Derive the output token from a phrase: uppercase every character then
     * strip everything that is not A-Z or 0-9.
     * <p>Examples: "New York City" → "NEWYORKCITY", "f*ck" → "FCK"
     */
    static String deriveOutput(String phrase) {
        return phrase.toUpperCase().replaceAll("[^A-Z0-9]", "");
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
                doc.add(new TextField(F_TEXT,   row[0], Field.Store.YES));
                doc.add(new StoredField(F_ID,    row[1]));
                doc.add(new StoredField(F_TYPE,  row[2]));
                doc.add(new StoredField(F_OUTPUT, deriveOutput(row[0])));
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
    record Tag(int start, int end, String surface, String id, String type, String output) {}

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
                    String   output = doc.get(F_OUTPUT);
                    if (phrase == null || phrase.isBlank()) continue;
                    if (output == null) output = deriveOutput(phrase);

                    // Pipe the phrase through the analyzer + ConcatenateGraphFilter.
                    // This produces the canonical FST key bytes with 0x1E separators —
                    // the same bytes we will feed arc-by-arc during tagging.
                    BytesRef key = keyFromPhrase(phrase, analyzer);
                    if (key == null || key.length == 0) continue;

                    sortedDict.computeIfAbsent(key, k -> new LinkedHashSet<>())
                              .add(id + "\t" + type + "\t" + output);  // Set deduplicates same record
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

                    // Decode: "id₁\ttype₁\toutput₁\nid₂\ttype₂\toutput₂\n…"
                    String combined = new String(
                        matchOut.bytes, matchOut.offset, matchOut.length, StandardCharsets.UTF_8);
                    for (String record : combined.split("\n")) {
                        String[] fields = record.split("\t", 3);
                        String output = fields.length > 2 ? fields[2] : deriveOutput(surface);
                        results.add(new Tag(start, end, surface, fields[0], fields[1], output));
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
    // TaggerServer  —  com.sun.net.httpserver HTTP wrapper
    // ═════════════════════════════════════════════════════════════════════════

    // ── Shared JSON helpers (used by TaggerServer and McpServer) ─────────────

    /** Escape a string for safe embedding inside a JSON double-quoted value. */
    static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /** Serialize a tag result into the standard response envelope. */
    static String toJson(String text, List<Tag> tags, long elapsedMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n")
          .append("  \"totaltime\": ").append(elapsedMs).append(",\n")
          .append("  \"text\": \"").append(jsonEscape(text)).append("\",\n")
          .append("  \"docs\": ");

        if (tags.isEmpty()) {
            sb.append("[]");
        } else {
            sb.append("[\n");
            for (int i = 0; i < tags.size(); i++) {
                Tag t = tags.get(i);
                sb.append("    {")
                  .append("\"start\":").append(t.start()).append(',')
                  .append("\"end\":").append(t.end()).append(',')
                  .append("\"surface\":\"").append(jsonEscape(t.surface())).append("\",")
                  .append("\"id\":\"").append(jsonEscape(t.id())).append("\",")
                  .append("\"type\":\"").append(jsonEscape(t.type())).append("\",")
                  .append("\"output\":\"").append(jsonEscape(t.output())).append("\"")
                  .append('}');
                if (i < tags.size() - 1) sb.append(',');
                sb.append('\n');
            }
            sb.append("  ]");
        }

        return sb.append("\n}").toString();
    }

    /**
     * Minimal HTTP server built on the JDK's built-in com.sun.net.httpserver API.
     *
     * Endpoints
     * ─────────
     *   GET  /tag?text=...        tag URL-encoded text; returns JSON array
     *   POST /tag                 body: {"text":"..."};  returns JSON array
     *   GET  /health              returns {"status":"ok"}
     *
     * JSON response format
     * ─────────────────────
     *   [
     *     {"start":0,"end":9,"surface":"Acme Corp","id":"org:acme","type":"ORG"},
     *     ...
     *   ]
     *
     * Usage
     * ─────
     *   java -jar target/tagger.jar serve [port]
     *   PORT env var is also honoured (Replit / container convention).
     */
    static class TaggerServer {

        private static final Pattern JSON_TEXT =
            Pattern.compile("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

        private final HttpServer  server;
        private final TextTagger  tagger;

        TaggerServer(TextTagger tagger, int port) throws IOException {
            this.tagger = tagger;
            this.server = HttpServer.create(new InetSocketAddress(port), /*backlog=*/ 0);
            this.server.setExecutor(Executors.newCachedThreadPool());
            this.server.createContext("/tag",    this::handleTag);
            this.server.createContext("/health", this::handleHealth);
        }

        void start() { server.start(); }
        void stop()  { server.stop(0); }

        // ── Handlers ─────────────────────────────────────────────────────────

        /** GET /tag?text=... or POST /tag with JSON body {"text":"..."} */
        private void handleTag(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod();

            String text;
            if ("GET".equalsIgnoreCase(method)) {
                text = queryParam(ex.getRequestURI().getRawQuery(), "text");
            } else if ("POST".equalsIgnoreCase(method)) {
                String body = readBody(ex);
                text = jsonTextField(body);
            } else {
                respond(ex, 405, "application/json", "{\"error\":\"method not allowed\"}");
                return;
            }

            if (text == null || text.isBlank()) {
                respond(ex, 400, "application/json",
                    "{\"error\":\"provide ?text= (GET) or {\\\"text\\\":\\\"...\\\"} (POST)\"}");
                return;
            }

            long start    = System.nanoTime();
            List<Tag> tags = tagger.tag(text);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            respond(ex, 200, "application/json", toJson(text, tags, elapsedMs));
        }

        /** GET /health */
        private void handleHealth(HttpExchange ex) throws IOException {
            respond(ex, 200, "application/json", "{\"status\":\"ok\"}");
        }

        // ── Helpers ───────────────────────────────────────────────────────────

        /** Read the full request body as a UTF-8 string. */
        private static String readBody(HttpExchange ex) throws IOException {
            try (InputStream in = ex.getRequestBody()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        /** Extract a single query-string parameter value (URL-decoded). */
        private static String queryParam(String rawQuery, String name) {
            if (rawQuery == null) return null;
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                if (eq < 0) continue;
                String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                if (name.equals(k)) {
                    return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
            return null;
        }

        /** Extract the "text" field value from a JSON object string. */
        private static String jsonTextField(String json) {
            if (json == null) return null;
            Matcher m = JSON_TEXT.matcher(json);
            if (!m.find()) return null;
            // Unescape basic JSON escape sequences
            return m.group(1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n",  "\n")
                    .replace("\\r",  "\r")
                    .replace("\\t",  "\t");
        }

        /** Write a complete HTTP response and close the exchange. */
        private static void respond(HttpExchange ex, int status,
                                    String contentType, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
            ex.sendResponseHeaders(status, bytes.length);
            try (var out = ex.getResponseBody()) { out.write(bytes); }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Shared setup helpers
    // ═════════════════════════════════════════════════════════════════════════

    /** Build the standard phrase dictionary used by both modes. */
    static List<String[]> buildPhrases() {
        return List.of(
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
    }

    /**
     * Optionally loads CSV files into an existing Lucene index.
     *
     * <p>Reads the {@code DATA} environment variable for a directory path.
     * Every {@code *.csv} file found there is processed in alphabetical order.
     * The first row of each file is the header; one Lucene document is written
     * per data row:
     *
     * <ul>
     *   <li>{@link #F_TEXT}   = value of the first column (the phrase to match)</li>
     *   <li>{@link #F_ID}     = random UUID for the row</li>
     *   <li>{@link #F_TYPE}   = file name without the {@code .csv} extension</li>
     *   <li>{@link #F_OUTPUT} = value of the {@code action} column if present,
     *                           otherwise {@link #deriveOutput(String)} of F_TEXT</li>
     * </ul>
     *
     * <p>Rows whose first-column value is blank are skipped.
     * If {@code DATA} is unset or blank the method returns immediately.
     * Uses {@link IndexWriterConfig.OpenMode#CREATE_OR_APPEND} so documents are
     * appended after those written by {@link #addData}.
     */
    static void loadCsvData(Directory dir, Analyzer analyzer) throws IOException {
        String dataEnv = System.getenv("DATA");
        if (dataEnv == null || dataEnv.isBlank()) {
            System.out.println("        DATA env var not set — skipping CSV load\n");
            return;
        }

        Path dataDir = Paths.get(dataEnv);
        if (!Files.isDirectory(dataDir)) {
            throw new IllegalArgumentException(
                "DATA path does not exist or is not a directory: " + dataDir);
        }

        // Collect *.csv files, sorted alphabetically for deterministic order
        List<Path> csvFiles;
        try (var stream = Files.list(dataDir)) {
            csvFiles = stream
                .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".csv"))
                .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                .toList();
        }

        if (csvFiles.isEmpty()) {
            System.out.println("        No .csv files found in " + dataDir
                + " — skipping CSV load\n");
            return;
        }

        System.out.println("Step 2b: Loading CSV data from " + dataDir);

        IndexWriterConfig cfg = new IndexWriterConfig(analyzer);
        cfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);

        int totalDocs = 0;

        try (IndexWriter writer = new IndexWriter(dir, cfg)) {
            for (Path csvFile : csvFiles) {
                String fileName  = csvFile.getFileName().toString();
                String typeLabel = fileName.substring(0, fileName.length() - 4);

                List<String> lines = Files.readAllLines(csvFile, StandardCharsets.UTF_8);
                if (lines.isEmpty()) continue;

                // First line is the header; locate optional "action" column
                String[] headers     = splitCsv(lines.get(0));
                int      actionColIdx = -1;
                for (int h = 0; h < headers.length; h++) {
                    if ("action".equalsIgnoreCase(headers[h].trim())) {
                        actionColIdx = h;
                        break;
                    }
                }

                int fileDocs = 0;
                for (int rowIdx = 1; rowIdx < lines.size(); rowIdx++) {
                    String line = lines.get(rowIdx).trim();
                    if (line.isEmpty()) continue;

                    String[] cells = splitCsv(line);

                    // First column is the phrase to index
                    String phrase = cells.length > 0 ? cells[0].trim() : "";
                    if (phrase.isEmpty()) continue;   // skip blank rows

                    // output: action column value if present, else derive from phrase
                    String output = deriveOutput(phrase);
                    if (actionColIdx >= 0 && actionColIdx < cells.length) {
                        String v = cells[actionColIdx].trim();
                        if (!v.isEmpty()) output = v;
                    }

                    Document doc = new Document();
                    doc.add(new TextField(F_TEXT,    phrase,                    Field.Store.YES));
                    doc.add(new StoredField(F_ID,    UUID.randomUUID().toString()));
                    doc.add(new StoredField(F_TYPE,  typeLabel));
                    doc.add(new StoredField(F_OUTPUT, output));
                    writer.addDocument(doc);
                    fileDocs++;
                }

                System.out.println("         " + fileName
                    + " → " + fileDocs + " document(s) (type=" + typeLabel + ")");
                totalDocs += fileDocs;
            }
            writer.commit();
        }

        System.out.println("         CSV load total: " + totalDocs + " document(s) from "
            + csvFiles.size() + " file(s) in " + dataDir + "\n");
    }

    /**
     * Minimal CSV line splitter: splits on commas, returns raw tokens
     * (callers trim as needed).
     */
    private static String[] splitCsv(String line) {
        return line.split(",", -1);
    }

    /** Steps 1-3: create index, add phrases, compile FST → ready-to-use TextTagger. */
    static TextTagger buildTagger(Analyzer analyzer) throws Exception {
        Directory dir = createIndex();
        addData(dir, analyzer, buildPhrases());
        loadCsvData(dir, analyzer);
        return TextTagger.fromIndex(dir, analyzer);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // McpServer  —  Model Context Protocol server (stdio transport)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * MCP server that exposes the tagger as a single tool called {@code tag}.
     *
     * <p>Transport: stdio (newline-delimited JSON-RPC 2.0 on stdin/stdout).
     * All diagnostic output goes to stderr so stdout carries only protocol frames.
     *
     * <p>Supported methods:
     * <ul>
     *   <li>{@code initialize}                — capability handshake</li>
     *   <li>{@code notifications/initialized} — client ready notification (no reply)</li>
     *   <li>{@code tools/list}                — returns the {@code tag} tool schema</li>
     *   <li>{@code tools/call}                — runs the tagger, returns envelope JSON</li>
     * </ul>
     *
     * <p>Usage: {@code java -jar tagger.jar mcp}
     */
    static class McpServer {

        private static final Pattern P_METHOD =
            Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"");
        private static final Pattern P_ID =
            Pattern.compile("\"id\"\\s*:\\s*(-?\\d+|\"[^\"]*\"|null)");
        private static final Pattern P_TOOL_NAME =
            Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
        private static final Pattern P_TEXT_ARG =
            Pattern.compile("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

        private static final String PROTOCOL_VERSION = "2024-11-05";
        private static final String SERVER_NAME      = "lucene-tagger";
        private static final String SERVER_VERSION   = "1.0";

        private final TextTagger tagger;
        private final PrintStream out;

        McpServer(TextTagger tagger) {
            this.tagger = tagger;
            this.out    = System.out;   // capture before anything might reassign it
        }

        /** Block on stdin, dispatching each newline-delimited JSON-RPC message. */
        void run() throws Exception {
            System.err.println("[mcp] Ready — listening on stdin");
            BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    dispatch(line);
                } catch (Exception e) {
                    System.err.println("[mcp] error: " + e.getMessage());
                }
            }
        }

        private void dispatch(String json) throws Exception {
            Matcher mMethod = P_METHOD.matcher(json);
            if (!mMethod.find()) return;     // not a valid JSON-RPC frame
            String method = mMethod.group(1);
            String rawId  = extractRawId(json);

            switch (method) {
                case "initialize"                -> handleInitialize(rawId);
                case "notifications/initialized" -> { /* notification — no reply */ }
                case "tools/list"                -> handleToolsList(rawId);
                case "tools/call"                -> handleToolsCall(rawId, json);
                default                          ->
                    sendError(rawId, -32601, "Method not found: " + method);
            }
        }

        // ── Method handlers ───────────────────────────────────────────────────

        private void handleInitialize(String rawId) {
            send("{" +
                "\"jsonrpc\":\"2.0\"," +
                "\"id\":" + rawId + "," +
                "\"result\":{" +
                    "\"protocolVersion\":\"" + PROTOCOL_VERSION + "\"," +
                    "\"capabilities\":{\"tools\":{}}," +
                    "\"serverInfo\":{" +
                        "\"name\":\"" + SERVER_NAME + "\"," +
                        "\"version\":\"" + SERVER_VERSION + "\"" +
                    "}" +
                "}" +
            "}");
        }

        private void handleToolsList(String rawId) {
            send("{" +
                "\"jsonrpc\":\"2.0\"," +
                "\"id\":" + rawId + "," +
                "\"result\":{\"tools\":[{" +
                    "\"name\":\"tag\"," +
                    "\"description\":\"Tag phrases in text using a Lucene FST phrase " +
                        "dictionary. Returns matched spans with character offsets, " +
                        "phrase type, canonical id, and output token.\"," +
                    "\"inputSchema\":{" +
                        "\"type\":\"object\"," +
                        "\"properties\":{" +
                            "\"text\":{" +
                                "\"type\":\"string\"," +
                                "\"description\":\"The text to tag\"" +
                            "}" +
                        "}," +
                        "\"required\":[\"text\"]" +
                    "}" +
                "}]}" +
            "}");
        }

        private void handleToolsCall(String rawId, String json) throws Exception {
            // Validate tool name
            Matcher mName = P_TOOL_NAME.matcher(json);
            if (!mName.find() || !"tag".equals(mName.group(1))) {
                sendError(rawId, -32602, "Unknown tool (only 'tag' is available)");
                return;
            }

            // Extract and unescape the text argument
            Matcher mText = P_TEXT_ARG.matcher(json);
            if (!mText.find()) {
                sendError(rawId, -32602, "Missing required argument: text");
                return;
            }
            String text = unescapeJson(mText.group(1));

            // Run tagger and build envelope
            long      start     = System.nanoTime();
            List<Tag> tags      = tagger.tag(text);
            long      elapsedMs = (System.nanoTime() - start) / 1_000_000;
            String    envelope  = toJson(text, tags, elapsedMs);

            // Embed envelope as an escaped string inside the MCP content frame
            send("{" +
                "\"jsonrpc\":\"2.0\"," +
                "\"id\":" + rawId + "," +
                "\"result\":{\"content\":[{" +
                    "\"type\":\"text\"," +
                    "\"text\":\"" + jsonEscape(envelope) + "\"" +
                "}]}" +
            "}");
        }

        // ── Helpers ───────────────────────────────────────────────────────────

        private void sendError(String rawId, int code, String message) {
            send("{" +
                "\"jsonrpc\":\"2.0\"," +
                "\"id\":" + (rawId != null ? rawId : "null") + "," +
                "\"error\":{\"code\":" + code + ",\"message\":\"" +
                    jsonEscape(message) + "\"}" +
            "}");
        }

        /** Extract the raw {@code id} token from a JSON-RPC frame (number, string, or null). */
        private static String extractRawId(String json) {
            Matcher m = P_ID.matcher(json);
            return m.find() ? m.group(1) : "null";
        }

        /** Unescape basic JSON escape sequences in a captured string value. */
        private static String unescapeJson(String s) {
            return s.replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n",  "\n")
                    .replace("\\r",  "\r")
                    .replace("\\t",  "\t");
        }

        private void send(String json) {
            out.println(json);
            out.flush();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Main
    // ═════════════════════════════════════════════════════════════════════════

    public static void main(String[] args) throws Exception {

        boolean mcpMode   = args.length > 0 && "mcp".equals(args[0]);
        boolean serveMode = args.length > 0 && "serve".equals(args[0]);

        if (mcpMode) {
            // ── MCP stdio server mode ────────────────────────────────────────
            // Redirect stdout to stderr during tagger build so the protocol
            // channel (stdout) carries only JSON-RPC frames.
            PrintStream realOut = System.out;
            System.setOut(System.err);

            System.err.println("[mcp] Building tagger ...");
            Analyzer   analyzer = buildAnalyzer();
            TextTagger tagger   = buildTagger(analyzer);

            System.setOut(realOut);          // restore protocol channel
            new McpServer(tagger).run();

        } else if (serveMode) {
            // ── HTTP server mode ─────────────────────────────────────────────
            // Port: first try command-line arg, then PORT env var, then default 8080
            int port = 8080;
            if (args.length > 1) {
                port = Integer.parseInt(args[1]);
            } else {
                String envPort = System.getenv("PORT");
                if (envPort != null && !envPort.isBlank()) port = Integer.parseInt(envPort);
            }

            System.out.println("Building tagger …");
            Analyzer   analyzer = buildAnalyzer();
            TextTagger tagger   = buildTagger(analyzer);

            TaggerServer srv = new TaggerServer(tagger, port);
            srv.start();

            System.out.println("Tagger HTTP server listening on port " + port);
            System.out.println("  GET  http://localhost:" + port + "/tag?text=Ada+Lovelace+loves+Lucene");
            System.out.println("  POST http://localhost:" + port + "/tag   body: {\"text\":\"Ada Lovelace loves Lucene\"}");
            System.out.println("  GET  http://localhost:" + port + "/health");
            System.out.println("Press Ctrl-C to stop.");

            // Keep the main thread alive
            Thread.currentThread().join();

        } else {
            // ── Demo mode ────────────────────────────────────────────────────
            Analyzer analyzer = buildAnalyzer();

            // Step 1: Create the index
            Directory dir = createIndex();

            // Step 2: Add data
            addData(dir, analyzer, buildPhrases());

            // Step 2b: Optionally load CSV data (DATA env var)
            loadCsvData(dir, analyzer);

            // Step 3: Compile FST from index, then tag
            TextTagger tagger = TextTagger.fromIndex(dir, analyzer);

            String[] inputs = {
                "Acme Corp uses Apache Lucene for search in New York City.",
                "Ada Lovelace would have loved Kubernetes and Elasticsearch.",
                "Google and Microsoft both operate in the United States and United Kingdom.",
                "I flew from Zürich to Berlin — the conference is in Germany.",
                "There is nothing to tag in this sentence about the weather.",
            };

            System.out.println("Step 3b: Tagging sentences with TextTagger (FST arc walk)");
            System.out.println("         (forward maximum match — longest phrase wins)\n");

            for (String input : inputs) {
                System.out.println("  INPUT : " + input);
                List<Tag> tags = tagger.tag(input);

                if (tags.isEmpty()) {
                    System.out.println("  TAGS  : (none)\n");
                } else {
                    for (Tag t : tags) {
                        System.out.printf("  TAG   : [%d-%d] \"%-20s\" id=%-12s type=%-10s output=%s%n",
                            t.start(), t.end(), t.surface(), t.id(), t.type(), t.output());
                    }
                    System.out.println();
                }
            }

            dir.close();
            analyzer.close();
        }
    }
}
