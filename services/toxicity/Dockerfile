# syntax=docker/dockerfile:1
# Base image pinned by digest. The tag is for humans; the digest is what builds.
ARG BASE=python:3.12-slim@sha256:aa943ce411423c7582288f18707ff66eddcbaeb510502d747a72aeb34f30d159

FROM ${BASE} AS model
# The model artifacts are fetched at build time and must match the checksums given as build arguments.
# There are no defaults: a build without them fails, so an unverified model is never baked into the image.
ARG MODEL_URL
ARG MODEL_SHA256
ARG TOKENIZER_URL
ARG TOKENIZER_SHA256
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl \
 && rm -rf /var/lib/apt/lists/* \
 && mkdir /models
RUN curl -fsSL "${MODEL_URL}" -o /models/model.onnx \
 && echo "${MODEL_SHA256}  /models/model.onnx" | sha256sum -c - \
 && curl -fsSL "${TOKENIZER_URL}" -o /models/tokenizer.json \
 && echo "${TOKENIZER_SHA256}  /models/tokenizer.json" | sha256sum -c -

FROM ${BASE}
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    TOXICITY_MODEL_PATH=/models/model.onnx \
    TOXICITY_TOKENIZER_DIR=/models
WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY toxicity_service ./toxicity_service
COPY --from=model /models /models
RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin toxicity
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["python", "-m", "toxicity_service.main"]
