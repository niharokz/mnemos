# ── build ────────────────────────────────────────────────────────────────
# Dependencies are vendored (vendor/), so the build needs no network access.
FROM mirror.gcr.io/library/golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod go.sum assets.go VERSION ./
COPY vendor ./vendor
COPY android/demo-vault ./android/demo-vault
COPY cmd ./cmd
COPY internal ./internal
COPY web ./web
RUN go vet -mod=vendor ./... && go test -mod=vendor ./... \
 && CGO_ENABLED=0 go build -mod=vendor -trimpath -ldflags="-s -w" -o /out/mnemos ./cmd/mnemos \
 && mkdir -m 1777 /out/tmp /out/state

# ── run ──────────────────────────────────────────────────────────────────
# An empty image: just the binary (web files and timezone data are built in).
FROM scratch
COPY --from=build /out/mnemos /mnemos
# A writable /tmp for the demo vault when the container is not read-only
# (compose mounts a tmpfs over it instead).
COPY --from=build /out/tmp /tmp
# Bans survive restarts here (compose mounts the mnemos-state volume; files inside are 0600).
COPY --from=build /out/state /state
ENV MNEMOS_NOTES=/notes MNEMOS_LISTEN=:8080
EXPOSE 8080
USER 1000:1000
HEALTHCHECK --interval=1m --timeout=5s --start-period=5s CMD ["/mnemos", "health"]
ENTRYPOINT ["/mnemos"]
CMD ["serve"]
