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

## Docker

### Quick start

```bash
cd java-tagger-demo
docker build -t lucene-tagger .
docker run --rm -p 8080:8080 lucene-tagger
```

The bundled `data/` files are loaded automatically. Then:

```bash
curl "http://localhost:8080/tag?text=I+want+to+track+my+order"
curl "http://localhost:8080/health"
```

### With Docker Compose

```bash
docker compose up
```

### Custom CSV data

Mount your own directory of CSV files over `/data` and point `DATA` at it:

```bash
docker run --rm -p 8080:8080 \
  -v /path/to/my/csvs:/data \
  -e DATA=/data \
  lucene-tagger
```

Or uncomment the `volumes` block in `docker-compose.yml`.

### Changing the port

```bash
docker run --rm -p 9000:9000 -e PORT=9000 lucene-tagger
```

### Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `8080` | Port the HTTP server listens on |
| `DATA` | `/app/data` | Directory of `.csv` files to load at startup |

---

## CSV data loading

Set the `DATA` environment variable to a directory containing `.csv` files to
extend the built-in dictionary at startup.  No code changes needed.

### How it works

- Every `*.csv` file in the directory is processed **alphabetically**.
- The **first row** is the column header; every subsequent row becomes
  **one Lucene document** (one document per row, not per column):

  | Field | Value |
  |-------|-------|
  | `phrase` (`F_TEXT`) | first column value (blank rows are skipped) |
  | `id` (`F_ID`) | random UUID |
  | `type` (`F_TYPE`) | file name without the `.csv` extension |
  | `output` (`F_OUTPUT`) | `action` column value if present, else derived from phrase |

- Documents are appended after the built-in phrases; the FST is compiled once
  from the combined index.

### Example CSV files (`data/`)

**`data/intent.csv`** — 168 rows → 168 documents (`type=intent`)

```
intent,action,response
track my order,STATUS,Checking order status
check availability,AVAILABILITY,Checking stock
buy,BUY,Buying
```

The `action` column is used as `output`; `response` and any other columns are ignored.

**`data/machine_families.csv`** — 273 rows → 273 documents (`type=machine_families`)

```
machine_family
ARTICULATED TRUCK
BACKHOE LOADER
MOTOR GRADER
```

No `action` column — `output` is derived from the phrase (uppercased, non-alphanumeric stripped).

### Tag response with CSV data loaded

```bash
DATA=data java -jar target/tagger.jar serve 8080
curl -s "http://localhost:8080/tag?text=I+want+to+track+my+order+and+check+availability"
```

```json
[
  {"start":10,"end":24,"surface":"track my order","id":"<uuid>","type":"intent","output":"STATUS"},
  {"start":29,"end":47,"surface":"check availability","id":"<uuid>","type":"intent","output":"AVAILABILITY"}
]
```

### Error handling

| Situation | Behaviour |
|-----------|-----------|
| `DATA` not set | Silent skip; built-in phrases only |
| `DATA` path does not exist | `IllegalArgumentException` at startup |
| Empty directory or no `*.csv` files | Silent skip with one log line |
| Blank first column | Row skipped; no empty-phrase entry written |

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
