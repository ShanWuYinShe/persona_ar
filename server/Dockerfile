# 阶段 1: 编译构建
FROM golang:alpine AS builder

WORKDIR /build

# 安装基础证书和 Git
RUN apk add --no-cache ca-certificates git

# 下载依赖
COPY go.mod go.sum ./
RUN go mod download

# 拷贝源码并编译静态可执行二进制
COPY . .
ARG APP_VERSION=2.1.0-dev
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w -X github.com/ShanWuYinShe/persona_ar/server/internal/api.ServerVersion=${APP_VERSION}" -o /build/persona-server ./cmd/server

# 阶段 2: 极简生产运行镜像
FROM alpine:3.20

WORKDIR /app

RUN apk add --no-cache ca-certificates tzdata curl

COPY --from=builder /build/persona-server /app/persona-server

# 创建数据与持久化目录
RUN mkdir -p /app/data/tts_cache

EXPOSE 8000

ENV PORT=8000
ENV HOST=0.0.0.0

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8000/healthz || exit 1

CMD ["/app/persona-server"]
