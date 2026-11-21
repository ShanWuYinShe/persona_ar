# 阶段 1: 编译构建
FROM golang:1.24-alpine AS builder

WORKDIR /build

# 安装基础证书和 Git
RUN apk add --no-cache ca-certificates git

# 下载依赖
COPY go.mod go.sum ./
RUN go mod download

# 拷贝源码并编译静态可执行二进制
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o /build/persona-server ./cmd/server

# 阶段 2: 极简生产运行镜像
FROM alpine:3.20

WORKDIR /app

RUN apk add --no-cache ca-certificates tzdata

COPY --from=builder /build/persona-server /app/persona-server

# 创建数据与持久化目录
RUN mkdir -p /app/data/tts_cache

EXPOSE 8000

ENV PORT=8000
ENV HOST=0.0.0.0

CMD ["/app/persona-server"]
