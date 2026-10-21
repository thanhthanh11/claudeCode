---
paths:
  - "observability/**"
  - "docker-compose.yml"
  - "**/logback*.xml"
  - "**/tracing/**"
  - "config-server/src/main/resources/config-repo/application.yml"
---

# Tracing và log tập trung

- Micrometer Tracing + OTLP → Jaeger (`OTLP_TRACING_ENDPOINT`, `TRACING_SAMPLING_PROBABILITY`). HTTP/Kafka truyền `traceparent` tự động (`spring.kafka.*.observation-enabled`); Redis request mang `traceContext`; outbox lưu `TRACE_CONTEXT` và publish trong span con → một giao dịch là một trace liền mạch. Console log có `[traceId-spanId]`.
- Log tập trung (Grafana + Loki + Alloy): ngoài console (text), mỗi service ghi JSON `${LOG_DIR:logs}/json/<service>.json` (`logging.structured.format.file=logstash` trong config-repo `application.yml`, có `traceId`/`spanId` từ MDC, rolling 50MB, giữ 3 ngày).
  - Alloy mount thư mục dự án, đọc `logs/json/*.json` và `<module>/logs/json/*.json` (khi chạy `mvn -pl ... spring-boot:run`) → Loki (giữ 7 ngày).
  - Label: `job=bank`, `service_name`, `level`. `traceId`/`spanId`/`logger`/`thread` là structured metadata (lọc `{job="bank"} | traceId="..."`), không làm label.
  - Grafana `http://localhost:3000` (admin/admin, `GRAFANA_ADMIN_PASSWORD`; dashboard **Bank / Bank - Logs**): bấm `traceId` trong log → trace Jaeger; từ span → "Logs for this span". `discovery-server`/`config-server` không nạp config-repo nên không ghi log JSON.
  - Jaeger all-in-one `http://localhost:16686` lưu trace trong bộ nhớ: restart container là mất trace.
- Log IT của `saga-it` ghi vào `saga-it/target/saga-it-logs/` (ghi đè `logging.file.name`) nên không bị Alloy đẩy lên Loki.
- `docker-compose.yml`: Redis 6379, Kafka KRaft 9092, Jaeger 16686 (OTLP 4318), Loki 3100, Alloy 12345, Grafana 3000, Mailpit (SMTP 1025, UI 8025). Oracle chạy riêng. `docker exec` trong Git Bash cần `MSYS_NO_PATHCONV=1`.
- Việc nên làm tiếp: metrics (Prometheus/Micrometer) + dashboard Grafana cho Saga, outbox, consumer lag; cảnh báo khi có `MANUAL_REVIEW` / message vào DLT.
