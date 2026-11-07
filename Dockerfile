# mnemostxt web — the editor for your notes folder, behind a sign-in.
# ── 1. the editor UI (frontend/) ───────────────────────────────────────────
FROM mirror.gcr.io/library/node:22-alpine AS ui
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm test && npm run build

# ── 2. the server (server/), with the UI built in ──────────────────────────
# Go dependencies are vendored, so this stage needs no network.
FROM mirror.gcr.io/library/golang:1.24-alpine AS go
WORKDIR /src/server
COPY server/ ./
COPY VERSION /src/VERSION
COPY --from=ui /src/frontend/dist ./web/dist
RUN go vet -mod=vendor ./... && go test -mod=vendor ./... \
 && CGO_ENABLED=0 go build -mod=vendor -trimpath -ldflags="-s -w -X main.version=$(cat /src/VERSION)" -o /out/mnemostxt ./cmd/mnemostxt \
 && mkdir -m 1777 /out/state

# ── 3. run: an empty image, just the binary ────────────────────────────────
FROM scratch
COPY --from=go /out/mnemostxt /mnemostxt
COPY --from=go /out/state /state
ENV MNEMOSTXT_NOTES=/notes MNEMOSTXT_STATE=/state MNEMOSTXT_LISTEN=:8080
EXPOSE 8080
USER 1000:1000
HEALTHCHECK --interval=1m --timeout=5s --start-period=5s CMD ["/mnemostxt", "health"]
ENTRYPOINT ["/mnemostxt"]
CMD ["serve"]
