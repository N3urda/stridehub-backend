FROM python:3.12-slim-bookworm AS dependencies

RUN apt-get update \
    && apt-get install --no-install-recommends -y ca-certificates git \
    && rm -rf /var/lib/apt/lists/*

# Keep the upstream API contract aligned with the StrideHub adapters.
RUN pip install --no-cache-dir --prefix=/install \
    'mi-fitness-mcp[api] @ git+https://github.com/HUAYUE1024/mi-fitness-mcp-cn.git@1767b4921ad94e38a68ac77cbb0bdf809a92864d'

FROM python:3.12-slim-bookworm

COPY --from=dependencies /install /usr/local
WORKDIR /app
COPY tools/mi-fitness-local.py tools/mi_fitness_*.py /app/tools/

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    MI_FITNESS_STATE_DIR=/data/mi-fitness \
    MI_FITNESS_CONFIG_DIR=/run/stridehub-mi-fitness \
    MI_FITNESS_HOST=0.0.0.0 \
    MI_FITNESS_ALLOWED_HOSTS=mi-fitness,127.0.0.1,localhost \
    MI_FITNESS_BASE_URL=http://mi-fitness:8321

RUN mkdir -p /data/mi-fitness /run/stridehub-mi-fitness \
    && chmod 0700 /data/mi-fitness /run/stridehub-mi-fitness

CMD ["python", "tools/mi-fitness-local.py", "serve"]
