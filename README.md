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

Optionally extend the dictionary with your own CSV files by setting the `DATA`
environment variable before running either mode:

```bash
DATA=data java -jar target/tagger.jar
DATA=data java -jar target/tagger.jar serve 8080
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

## CSV data loading

Set the `DATA` environment variable to a directory containing `.csv` files to
extend the built-in dictionary at startup.  No code changes needed.

### How it works

- Every `*.csv` file in the directory is processed **alphabetically**.
- The **first row** is the column header; every subsequent row is a data row.
- For each data row a single UUID is generated.  For **each column** in that
  row, one Lucene document is written to the index:

  | Field | Value |
  |-------|-------|
  | `phrase` (`F_TEXT`) | cell value (blank cells are silently skipped) |
  | `id` (`F_ID`) | `<row-uuid>-<columnName>` |
  | `type` (`F_TYPE`) | file name without the `.csv` extension |

- Documents are appended after the built-in phrases; the FST is compiled once
  from the combined index.

### Example CSV files (`data/`)

**`data/intent.csv`**

```
intent,action,response
view,VIEW,Viewing
find,FIND,Finding
buy,BUY,Buying
```

Produces 9 documents (`type=intent`) — one per cell.

**`data/product.csv`**

```
name,alias,sku
Apache Lucene,Lucene,sw-lucene
Apache Solr,Solr,sw-solr
Elasticsearch,ES,sw-es
Kubernetes,k8s,sw-k8s
```

Produces 12 documents (`type=product`).

### Tag response with CSV data loaded

```bash
DATA=data java -jar target/tagger.jar serve 8080
curl -s "http://localhost:8080/tag?text=I+want+to+find+Lucene+and+buy+Solr"
```

```json
[
  {"start":10,"end":14,"surface":"find","id":"<uuid>-intent","type":"intent"},
  {"start":10,"end":14,"surface":"find","id":"<uuid>-action","type":"intent"},
  {"start":15,"end":21,"surface":"Lucene","id":"sw:lucene","type":"PRODUCT"},
  {"start":15,"end":21,"surface":"Lucene","id":"<uuid>-alias","type":"product"},
  {"start":26,"end":29,"surface":"buy","id":"<uuid>-intent","type":"intent"},
  {"start":30,"end":34,"surface":"Solr","id":"<uuid>-alias","type":"product"}
]
```

Multiple tags for the same span are normal when a cell value appears in more
than one column or matches both a hard-coded entry and a CSV entry.

### Error handling

| Situation | Behaviour |
|-----------|-----------|
| `DATA` not set | Silent skip; built-in phrases only |
| `DATA` path does not exist | `IllegalArgumentException` at startup |
| Empty directory or no `*.csv` files | Silent skip with one log line |
| Blank cell | Document skipped; no empty-phrase entry written |

---

## Source layout (`App.java`)

Everything lives in one file using nested static classes:

| Symbol | Role |
|--------|------|
| `buildAnalyzer()` | `StandardTokenizer → LowerCase → ASCIIFold` — no stemming |
| `createIndex()` | Creates an in-memory `ByteBuffersDirectory` |
| `addData()` | Writes hard-coded phrase documents via `IndexWriter` |
| `loadCsvData()` | Appends CSV documents; reads `DATA` env var |
| `buildPhrases()` | Returns the built-in `List<String[]>` dictionary |
| `buildTagger()` | Orchestrates steps 1-3 for server mode |
| `TextTagger` | FST-backed phrase dictionary; forward-maximum-match arc walk |
| `TaggerServer` | `com.sun.net.httpserver` HTTP wrapper; GET+POST `/tag`, `/health` |
| `App.main()` | Entry point: demo mode (default) or server mode (`serve`) |
