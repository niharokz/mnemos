# samanote — two-stage build: vet + tests + static binary, then a tiny runtime image.
FROM golang:1.23-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN go vet ./... && go test ./... \
 && CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o /out/samanote ./cmd/samanote

FROM alpine:3.20
RUN apk add --no-cache ca-certificates
COPY --from=build /out/samanote /usr/local/bin/samanote
# The vault (bind-mounted from NOTE_PATH); the container runs as PUID:PGID from .env.
VOLUME ["/notes"]
ENV SAMANOTE_NOTES=/notes SAMANOTE_LISTEN=:8080
EXPOSE 8080
HEALTHCHECK --interval=60s --timeout=5s --start-period=10s CMD ["samanote", "health"]
ENTRYPOINT ["samanote"]
CMD ["serve"]
