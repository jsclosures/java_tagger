# Lucene Text Tagger — Java CLI Demo

A single-file Java demo covering every aspect of phrase-based text tagging
with **Apache Lucene 9.10** — no Solr required.

## Requirements

- Java 17+  
- Maven 3.6+  
(Both provided by the GraalVM 22.3 module in this environment.)

## Build

```bash
cd java-tagger-demo
mvn package -q
```

Produces `target/tagger.jar` (fat jar, ~12 MB).

## Run

### HTTP server mode

```bash
java -jar target/tagger.jar serve [port]
```

Port defaults to `8080`, overridden by the `PORT` env var or a positional argument.

| Method | Path | Input | Output |
|--------|------|-------|--------|
| GET | `/tag?text=Ada+Lovelace+loves+Lucene` | URL-encoded text | JSON array of tags |
| POST | `/tag` | `{"text":"Ada Lovelace loves Lucene"}` | JSON array of tags |
| GET | `/health` | — | `{"status":"ok"}` |

**Example responses:**

```bash
curl "http://localhost:8080/tag?text=Ada+Lovelace+uses+Apache+Lucene+in+New+York+City"
```

```json
[
  {"start":0,"end":12,"surface":"Ada Lovelace","id":"per:ada","type":"PERSON"},
  {"start":18,"end":31,"surface":"Apache Lucene","id":"sw:lucene","type":"PRODUCT"},
  {"start":35,"end":48,"surface":"New York City","id":"geo:nyc","type":"CITY"}
]
```

```bash
curl -X POST http://localhost:8080/tag \
     -H "Content-Type: application/json" \
     -d '{"text":"Google and Microsoft operate in the United States"}'
```

```json
[
  {"start":0,"end":6,"surface":"Google","id":"org:goog","type":"ORG"},
  {"start":11,"end":20,"surface":"Microsoft","id":"org:msft","type":"ORG"},
  {"start":36,"end":49,"surface":"United States","id":"cnt:us","type":"COUNTRY"}
]
```

### Full demo — all guide sections in one pass

```bash
java -jar target/tagger.jar
```

Runs through all nine demo sections:

| # | Section |
|---|---------|
| 1 | Building the dictionary index |
| 2 | Basic tagging (forward maximum match) |
| 3 | Longest-match-wins — "New York City" beats "New York" |
| 4 | ASCII folding — "Zürich" matches "Zurich" |
| 5 | Case-sensitive analyzer — "US" ≠ "us" |
| 6 | Incremental updates — add entries without full rebuild |
| 7 | Concurrent tagging — 4 threads, read-write lock |
| 8 | Common pitfalls — analyzer mismatch, stemming, reset(), stop words |
| 9 | No-match case |

### Interactive REPL

```bash
java -jar target/tagger.jar repl
```

Type sentences at the `>` prompt. Commands: `dict`, `help`, `quit`.

## Source layout (`App.java`)

Everything lives in one file using nested static classes:

| Class | Role |
|-------|------|
| `DictionaryAnalyzer` | `StandardTokenizer → LowerCase → ASCIIFold` — no stemming |
| `CaseSensitiveAnalyzer` | `WhitespaceTokenizer` only — uppercase and lowercase are distinct |
| `TextTagger` | HashMap-backed index; forward-maximum-match tagging |
| `ThreadSafeTagger` | `ReadWriteLock` wrapper; atomic incremental updates |
| `App` | Main entry point, all nine demos, REPL, output formatting |
