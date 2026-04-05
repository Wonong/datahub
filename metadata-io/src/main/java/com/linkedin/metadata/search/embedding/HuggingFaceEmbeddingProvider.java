package com.linkedin.metadata.search.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of {@link EmbeddingProvider} that calls the HuggingFace Inference API to generate
 * embeddings.
 *
 * <p>Uses the Feature Extraction pipeline endpoint: {@code POST
 * https://api-inference.huggingface.co/pipeline/feature-extraction/{model}}
 *
 * <p>The response format is {@code [[float, ...]]} — a 2D array where the first element contains
 * the sentence-level embedding vector (mean pooling over token embeddings).
 *
 * <p>Primary use case is the {@code dragonkue/kure-v1} model (768 dimensions), a Korean-language
 * semantic embedding model. Self-hosted endpoints (e.g., Text Embeddings Inference) are also
 * supported via the {@code endpoint} parameter.
 *
 * <p>When a model is still loading, HuggingFace returns HTTP 503 with an estimated wait time. This
 * provider treats 503 as a retryable error.
 *
 * @see <a href="https://huggingface.co/docs/api-inference/tasks/feature-extraction">HuggingFace
 *     Feature Extraction API</a>
 */
@Slf4j
public class HuggingFaceEmbeddingProvider implements EmbeddingProvider {

  private static final String DEFAULT_MODEL = "dragonkue/kure-v1";
  private static final String DEFAULT_BASE_URL = "https://api-inference.huggingface.co";
  private static final String FEATURE_EXTRACTION_PATH = "/pipeline/feature-extraction/";
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

  // HuggingFace can be slow on cold starts; allow one retry for transient/loading errors.
  private static final int MAX_ATTEMPTS = 2;

  private final String apiKey;
  private final String baseUrl;
  @Nonnull private final String defaultModel;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  /**
   * Creates a provider with default settings targeting the HuggingFace Inference API.
   *
   * @param apiKey HuggingFace access token (starts with "hf_")
   */
  public HuggingFaceEmbeddingProvider(@Nonnull String apiKey) {
    this(apiKey, DEFAULT_BASE_URL, DEFAULT_MODEL);
  }

  /**
   * Creates a provider with custom base URL and model — useful for self-hosted deployments such as
   * Text Embeddings Inference (TEI).
   *
   * @param apiKey HuggingFace access token or empty string for unauthenticated self-hosted servers
   * @param baseUrl Base URL of the inference server (no trailing slash)
   * @param defaultModel Default model identifier (e.g., "dragonkue/kure-v1")
   */
  public HuggingFaceEmbeddingProvider(
      @Nonnull String apiKey, @Nonnull String baseUrl, @Nonnull String defaultModel) {
    this(
        apiKey,
        baseUrl,
        defaultModel,
        HttpClient.newBuilder()
            .connectTimeout(DEFAULT_TIMEOUT)
            .version(HttpClient.Version.HTTP_1_1)
            .build());
  }

  /**
   * Creates a provider with a custom HttpClient — primarily used in tests.
   *
   * @param apiKey HuggingFace access token
   * @param baseUrl Base URL of the inference server
   * @param defaultModel Default model identifier
   * @param httpClient Pre-configured HttpClient
   */
  public HuggingFaceEmbeddingProvider(
      @Nonnull String apiKey,
      @Nonnull String baseUrl,
      @Nonnull String defaultModel,
      @Nonnull HttpClient httpClient) {
    this.apiKey = Objects.requireNonNull(apiKey, "apiKey cannot be null");
    this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl cannot be null");
    this.defaultModel = Objects.requireNonNull(defaultModel, "defaultModel cannot be null");
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient cannot be null");
    this.objectMapper = new ObjectMapper();

    log.info(
        "Initialized HuggingFaceEmbeddingProvider: baseUrl={}, model={}", baseUrl, defaultModel);
  }

  @Override
  @Nonnull
  public float[] embed(@Nonnull String text, @Nullable String model) {
    Objects.requireNonNull(text, "text cannot be null");

    String modelToUse = model != null ? model : defaultModel;
    Exception lastException = null;

    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        return embedInternal(text, modelToUse);
      } catch (RuntimeException e) {
        // Non-retryable: 4xx errors, malformed response
        lastException = e;
        break;
      } catch (Exception e) {
        // Retryable: IOException (network), InterruptedException, or 503 model-loading
        lastException = e;
        if (attempt < MAX_ATTEMPTS) {
          log.warn(
              "HuggingFace embedding attempt {}/{} failed for model {}, retrying: {}",
              attempt,
              MAX_ATTEMPTS,
              modelToUse,
              e.getMessage());
        }
      }
    }

    log.error(
        "All {} attempts failed for HuggingFace embedding with model {}", MAX_ATTEMPTS, modelToUse);
    Exception cause = Objects.requireNonNull(lastException);
    throw new RuntimeException(
        String.format(
            "HuggingFace API call failed for model %s after %d attempts: %s",
            modelToUse, MAX_ATTEMPTS, cause.getMessage()),
        cause);
  }

  @Nonnull
  private float[] embedInternal(@Nonnull String text, @Nonnull String modelToUse)
      throws IOException, InterruptedException {
    String url = buildUrl(modelToUse);

    ObjectNode requestBody = objectMapper.createObjectNode();
    requestBody.put("inputs", text);

    String requestJson = objectMapper.writeValueAsString(requestBody);
    log.debug("HuggingFace request to {}: inputs length={}", url, text.length());

    HttpRequest.Builder requestBuilder =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(DEFAULT_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestJson));

    if (!apiKey.isBlank()) {
      requestBuilder.header("Authorization", "Bearer " + apiKey);
    }

    HttpResponse<String> response =
        httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
    int statusCode = response.statusCode();

    if (statusCode != 200) {
      String errorMsg =
          String.format(
              "HuggingFace API returned status %d for model %s: %s",
              statusCode, modelToUse, response.body());

      if (statusCode == 503) {
        // Model is still loading — treat as transient so the retry loop retries
        throw new IOException(errorMsg);
      }
      if (statusCode >= 500) {
        throw new IOException(errorMsg);
      }
      throw new RuntimeException(errorMsg);
    }

    return parseEmbedding(response.body(), modelToUse);
  }

  /**
   * Parses the HuggingFace feature-extraction response.
   *
   * <p>The API returns a 2D array {@code [[float, ...]]} where the outer array has one element per
   * input. Since we always send a single input, index 0 is the sentence embedding.
   */
  @Nonnull
  private float[] parseEmbedding(@Nonnull String responseBody, @Nonnull String modelToUse) {
    JsonNode root;
    try {
      root = objectMapper.readTree(responseBody);
    } catch (IOException e) {
      throw new RuntimeException(
          "Invalid JSON response from HuggingFace for model " + modelToUse + ": " + e.getMessage(),
          e);
    }

    // Response is [[float, ...]] — outer array is per-input, inner array is the vector
    if (!root.isArray() || root.size() == 0) {
      throw new RuntimeException(
          "Invalid response from HuggingFace for model "
              + modelToUse
              + ": expected a non-empty array, got: "
              + responseBody);
    }

    JsonNode embeddingArray = root.get(0);
    if (!embeddingArray.isArray()) {
      throw new RuntimeException(
          "Invalid response from HuggingFace for model "
              + modelToUse
              + ": first element is not an array, got: "
              + embeddingArray);
    }

    int dimensions = embeddingArray.size();
    if (dimensions == 0) {
      throw new RuntimeException(
          "Invalid response from HuggingFace for model "
              + modelToUse
              + ": embedding vector is empty");
    }

    float[] embedding = new float[dimensions];
    for (int i = 0; i < dimensions; i++) {
      JsonNode value = embeddingArray.get(i);
      if (!value.isNumber()) {
        throw new RuntimeException(
            "Invalid response from HuggingFace for model "
                + modelToUse
                + ": embedding contains non-numeric value at index "
                + i);
      }
      embedding[i] = (float) value.asDouble();
    }

    log.debug(
        "Generated embedding with {} dimensions via HuggingFace model {}", dimensions, modelToUse);
    return embedding;
  }

  private String buildUrl(@Nonnull String model) {
    return baseUrl + FEATURE_EXTRACTION_PATH + model;
  }
}
