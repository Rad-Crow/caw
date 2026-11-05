FROM docker.io/library/node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
COPY protocol/ /protocol/
RUN npm run build

FROM docker.io/library/rust:1-bookworm AS server
WORKDIR /src
COPY server/ ./
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/src/target \
    cargo build --release --locked && cp target/release/caw /caw

FROM docker.io/library/debian:bookworm-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 10001 -d /data caw \
    && mkdir /data && chown caw /data
COPY --from=server /caw /usr/local/bin/caw
COPY --from=web /web/dist /app/web
COPY deploy/entrypoint.sh /usr/local/bin/entrypoint
ENV CAW_STATIC_DIR=/app/web CAW_DB=/data/caw.db CAW_BIND=0.0.0.0:8080
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["entrypoint"]
CMD ["caw"]
