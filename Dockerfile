FROM node:24-bookworm-slim AS frontend-build
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk-noble AS backend-build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml LICENSE THIRD_PARTY_NOTICES.md ./
COPY LICENSES/ LICENSES/
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B dependency:go-offline
COPY src/ src/
COPY --from=frontend-build /build/frontend/dist frontend/dist
RUN ./mvnw -B package

FROM eclipse-temurin:21-jre-noble AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       ca-certificates curl tar unzip fontconfig \
       libfontconfig1 libfreetype6 libgraphite2-3 libharfbuzz0b libicu74 libpng16-16 libssl3 \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --home-dir /home/doc2tex --shell /usr/sbin/nologin doc2tex

WORKDIR /app
COPY scripts/setup-tools.sh scripts/setup-fonts.sh scripts/
RUN set -e \
    && sed -i 's/\r$//' scripts/setup-tools.sh scripts/setup-fonts.sh \
    && bash scripts/setup-tools.sh \
    && rm -rf .tools/downloads .tools/*/upstream \
    && { missing="$(ldd /app/.tools/tectonic/tectonic | grep 'not found' || true)"; \
         if [ -n "$missing" ]; then printf '%s\n' "$missing"; exit 1; fi; } \
    && /app/.tools/pandoc/pandoc --version \
    && /app/.tools/tectonic/tectonic --version

COPY --from=backend-build /build/target/doc2tex-0.1.0.jar /app/doc2tex.jar
COPY src/test/resources/corpus/research-complex.docx /tmp/warmup/research-complex.docx
RUN mkdir -p /app/.data/jobs /home/doc2tex/.cache /tmp/warmup/article /tmp/warmup/report \
    && chown -R doc2tex:doc2tex /app /home/doc2tex /tmp/warmup

USER doc2tex
ENV HOME=/home/doc2tex \
    XDG_CACHE_HOME=/home/doc2tex/.cache
RUN (cd /tmp/warmup/article \
        && /app/.tools/pandoc/pandoc --sandbox --from=docx --to=latex --standalone --wrap=none --track-changes=accept \
           --extract-media=media --metadata=date: --variable=documentclass:article --output=main.tex /tmp/warmup/research-complex.docx \
        && /app/.tools/tectonic/tectonic --untrusted --color never main.tex) \
    && (cd /tmp/warmup/report \
        && /app/.tools/pandoc/pandoc --sandbox --from=docx --to=latex --standalone --wrap=none --track-changes=accept \
           --extract-media=media --metadata=date: --variable=documentclass:report --output=main.tex /tmp/warmup/research-complex.docx \
        && /app/.tools/tectonic/tectonic --untrusted --color never main.tex) \
    && rm -rf /tmp/warmup

ENV SERVER_ADDRESS=0.0.0.0 \
    PORT=8080 \
    DOC2TEX_DATA_DIR=/app/.data/jobs \
    DOC2TEX_FONT_DIR=/app/.tools/fonts \
    DOC2TEX_AI_LAYOUT_ENABLED=false \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=45.0 -XX:+UseG1GC"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=25s --retries=3 \
  CMD curl -fsS "http://127.0.0.1:${PORT:-8080}/api/system" >/dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app/doc2tex.jar"]
