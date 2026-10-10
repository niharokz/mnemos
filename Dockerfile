# ── build ────────────────────────────────────────────────────────────────
# Dependencies are vendored (vendor/), so the build needs no network access.
FROM mirror.gcr.io/library/golang:1.24-alpine AS build
ARG VERSION=dev
WORKDIR /src
COPY go.mod go.sum ./
COPY vendor ./vendor
COPY cmd ./cmd
COPY internal ./internal
COPY web ./web
RUN go vet -mod=vendor ./... && go test -mod=vendor ./... \
 && CGO_ENABLED=0 go build -mod=vendor -trimpath -ldflags="-s -w -X main.version=${VERSION}" -o /out/samanote ./cmd/samanote

# ── run ──────────────────────────────────────────────────────────────────
# An empty image: just the binary (web files and timezone data are built in).
FROM scratch
COPY --from=build /out/samanote /samanote
ENV SAMANOTE_NOTES=/notes SAMANOTE_LISTEN=:8080
EXPOSE 8080
USER 1000:1000
HEALTHCHECK --interval=1m --timeout=5s --start-period=5s CMD ["/samanote", "health"]
ENTRYPOINT ["/samanote"]
CMD ["serve"]
