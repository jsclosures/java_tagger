# ── Stage 1: build ───────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# Cache dependency downloads separately from source changes
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Build the fat JAR
COPY src ./src
RUN mvn package -q -DskipTests

# ── Stage 2: runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy AS runtime

WORKDIR /app

# Copy the assembled fat JAR
COPY --from=builder /build/target/tagger.jar tagger.jar

# Bundle the sample data directory; can be overridden at runtime with a volume
COPY data ./data

# HTTP server port (matches the PORT env-var the app reads)
ENV PORT=8080

# Load CSV data from the bundled data directory by default
# Override with -e DATA=/path or a volume mount to use different files
ENV DATA=/app/data

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "tagger.jar", "serve"]
