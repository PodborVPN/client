# Podbor для Linux: контекст собирает задача :linux:dockerContext, образ только раскладывает готовое.

# Xray — из того же модуля, что внутри libXray: движок и конвертер ссылок всегда одной версии.
FROM golang:1.27-alpine AS engine
WORKDIR /src
COPY libxray-cli/main.go .
RUN go mod init podbor/libxray-cli \
 && go get github.com/xtls/libxray@v1.261017.0 \
 && go get "github.com/xtls/xray-core/main@$(go list -m -f '{{.Version}}' github.com/xtls/xray-core)" \
 && CGO_ENABLED=0 go build -trimpath -o /libxray-cli . \
 && CGO_ENABLED=0 go build -trimpath -o /xray github.com/xtls/xray-core/main

FROM eclipse-temurin:21-jre-alpine
RUN adduser -D -u 10001 podbor && mkdir /data && chown podbor /data
COPY --from=engine /libxray-cli /usr/local/bin/libxray-cli
COPY --from=engine /xray /usr/local/bin/xray
COPY podbor-linux /opt/podbor/app
COPY assets /opt/podbor/assets
COPY web /opt/podbor/web
USER podbor
VOLUME /data
EXPOSE 8898 8899
ENTRYPOINT ["/opt/podbor/app/bin/podbor-linux"]
