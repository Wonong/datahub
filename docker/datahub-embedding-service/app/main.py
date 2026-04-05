import logging
import os
from contextlib import asynccontextmanager
from typing import List

import torch
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger(__name__)

# The model name is baked into the image at build time. Override via MODEL_NAME if needed.
MODEL_NAME: str = os.getenv("MODEL_NAME", "nlpai-lab/KURE-v1")
# sentence-transformers caches models under this folder (set at build time in the Dockerfile).
MODEL_CACHE_DIR: str = os.getenv("MODEL_CACHE_DIR", "/models")

_model: SentenceTransformer | None = None
_device: str | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Load the embedding model once at startup and release resources on shutdown."""
    global _model, _device

    _device = "cuda" if torch.cuda.is_available() else "cpu"
    logger.info("Loading model '%s' on device '%s' ...", MODEL_NAME, _device)

    # sentence-transformers resolves the model from cache when the files already exist locally,
    # so no network access is needed after the Docker build step.
    _model = SentenceTransformer(MODEL_NAME, cache_folder=MODEL_CACHE_DIR, device=_device)

    dim = _model.get_sentence_embedding_dimension()
    logger.info("Model loaded. Embedding dimension: %d", dim)

    yield  # application runs here

    logger.info("Shutting down embedding service.")
    _model = None


app = FastAPI(title="DataHub Embedding Service", lifespan=lifespan)


class EmbedRequest(BaseModel):
    texts: List[str]


class EmbedResponse(BaseModel):
    embeddings: List[List[float]]


@app.post("/embed", response_model=EmbedResponse)
def embed(request: EmbedRequest) -> EmbedResponse:
    if _model is None:
        raise HTTPException(status_code=503, detail="Model not loaded yet — try again shortly.")
    if not request.texts:
        raise HTTPException(status_code=400, detail="'texts' must be a non-empty list.")

    logger.debug("Embedding %d text(s)", len(request.texts))

    # normalize_embeddings=True produces unit-norm vectors suited for cosine similarity KNN.
    vectors = _model.encode(
        request.texts,
        normalize_embeddings=True,
        convert_to_numpy=True,
        show_progress_bar=False,
    )
    return EmbedResponse(embeddings=vectors.tolist())


@app.get("/health")
def health() -> dict:
    if _model is None:
        raise HTTPException(status_code=503, detail="Model not loaded.")
    return {
        "status": "ok",
        "model": MODEL_NAME,
        "device": _device,
        "dimensions": _model.get_sentence_embedding_dimension(),
    }
