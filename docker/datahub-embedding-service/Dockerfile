# ---------------------------------------------------------------------------
# DataHub Embedding Service
#
# Build: model is downloaded at image build time so the container is fully
#        self-contained — no network access required at runtime.
#
# GPU:   Set the base image to a CUDA-enabled image and install the
#        CUDA-flavoured PyTorch wheel to enable GPU inference:
#        FROM nvidia/cuda:12.4.1-runtime-ubuntu22.04
#
# Run:
#   docker run -p 8766:8766 datahub-embedding-service
#
# Override the model at build time:
#   docker build --build-arg MODEL_NAME=sentence-transformers/all-MiniLM-L6-v2 .
# ---------------------------------------------------------------------------

FROM python:3.11-slim

ARG MODEL_NAME=nlpai-lab/KURE-v1
ARG MODEL_CACHE_DIR=/models

ENV MODEL_NAME=${MODEL_NAME} \
    MODEL_CACHE_DIR=${MODEL_CACHE_DIR} \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

# Install Python dependencies first (layer is cached unless requirements change).
COPY app/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Download the model at build time so the image is self-contained.
# sentence-transformers saves to ${MODEL_CACHE_DIR}/<org>_<model>/.
RUN python - <<'EOF'
from sentence_transformers import SentenceTransformer
import os
model_name = os.environ["MODEL_NAME"]
cache_dir  = os.environ["MODEL_CACHE_DIR"]
print(f"Downloading model '{model_name}' to '{cache_dir}' ...")
SentenceTransformer(model_name, cache_folder=cache_dir)
print("Download complete.")
EOF

COPY app/main.py .

EXPOSE 8766

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8766/health')"

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8766"]
