FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends xz-utils coreutils ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY .ci/v611-backend-r4 /payload

RUN set -eux; \
    cat /payload/part*.b64 | tr -d '\r\n' | base64 --decode > /tmp/backend.tar.xz; \
    echo "a8272efc8853e500d3a83578fa6658da1a10157ae865eca7a6342c82eae98e61  /tmp/backend.tar.xz" | sha256sum -c -; \
    tar -xJf /tmp/backend.tar.xz -C /app; \
    rm -f /tmp/backend.tar.xz

RUN pip install --no-cache-dir -r /app/backend/requirements.txt

WORKDIR /app/backend
EXPOSE 10000

CMD ["sh", "-c", "uvicorn main:app --host 0.0.0.0 --port ${PORT:-10000}"]
