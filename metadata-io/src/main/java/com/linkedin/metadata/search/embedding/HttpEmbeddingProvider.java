package com.linkedin.metadata.search.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
 * {@link EmbeddingProvider} that calls a local or remote HTTP embedding service.
 *
 * <p>Expected API contract:
 *
 * <pre>{@code
 * POST {baseUrl}/embed
 * Content-Type: application/json
 * {"texts": ["text to embed"]}
 *
 * 200 OK
 * {"embeddings": [[0.1, 0.2, ...]]}
 * }</pre>
 *
 * <p>This provider is intentionally agnostic of which model runs behind the endpoint. The service
 * is responsible for model selection — typically the model is baked into the Docker image at build
 * time and loaded into memory on startup.
 *
 * <p>Designed for two deployment patterns:
 *
 * <ol>
 *   <li><b>Sidecar / same-host</b>: {@code baseUrl=http://localhost:8766} (default). The embedding
 *       service runs as a companion container in the same compose stack.
 *   <li><b>Dedicated service</b>: {@code baseUrl=http://datahub-embedding-service:8766}. The
 *       embedding service is deployed independently and the URL is overridden via {@code
 *       EMBEDDING_SERVICE_URL}.
 * </ol>
 */
@Slf4j
public class HttpEmbeddingProvider implements EmbeddingProvider {

  private static final String EMBED_PATH = "/embed";

  // Two attempts: one for the initial call, one retry for transient I/O errors.
  private static final int MAX_ATTEMPTS = 2;

  private final String baseUrl;
  private final Duration timeout;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  /**
   * Creates a provider pointing to the default local embedding service.
   *
   * @param baseUrl Base URL of the embedding service (e.g. "http://localhost:8766")
   * @param timeoutSeconds Request timeout — set high (120 s) to survive model cold-start
   */
  public HttpEmbeddingProvider(@Nonnull String baseUrl, int timeoutSeconds) {
    this(
        baseUrl,
        Duration.ofSeconds(timeoutSeconds),
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds))
            .version(HttpClient.Version.HTTP_1_1)
            .build());
  }

  /** Test constructor allowing injection of a mock {@link HttpClient}. */
  HttpEmbeddingProvider(
      @Nonnull String baseUrl, @Nonnull Duration timeout, @Nonnull HttpClient httpClient) {
    this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    this.objectMapper = new ObjectMapper();
    log.info("Initialized HttpEmbeddingProvider: baseUrl={}", baseUrl);
  }

  @Override
  @Nonnull
  public float[] embed(@Nonnull String text, @Nullable String model) {
    Objects.requireNonNull(text, "text cannot be null");

    Exception lastException = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        return doEmbed(text);
      } catch (RuntimeException e) {
        // 4xx or parse errors are not retryable
        throw e;
      } catch (Exception e) {
        lastException = e;
        if (attempt < MAX_ATTEMPTS) {
          log.warn(
              "Embedding attempt {}/{} failed (baseUrl={}), retrying: {}",
              attempt,
              MAX_ATTEMPTS,
              baseUrl,
              e.getMessage());
        }
      }
    }

    throw new RuntimeException(
        "Embedding service call failed after "
            + MAX_ATTEMPTS
            + " attempts ("
            + baseUrl
            + "): "
            + Objects.requireNonNull(lastException).getMessage(),
        lastException);
  }

  @Nonnull
  private float[] doEmbed(@Nonnull String text) throws IOException, InterruptedException {
    String url = baseUrl + EMBED_PATH;

    ObjectNode body = objectMapper.createObjectNode();
    ArrayNode texts = body.putArray("texts");
    texts.add(text);

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    int status = response.statusCode();

    if (status != 200) {
      String msg =
          String.format(
              "Embedding service returned HTTP %d (%s): %s", status, url, response.body());
      // 5xx and 503 (model loading) are transient
      if (status >= 500) {
        throw new IOException(msg);
      }
      throw new RuntimeException(msg);
    }

    return parseEmbedding(response.body(), url);
  }

  @Nonnull
  private float[] parseEmbedding(@Nonnull String body, @Nonnull String url) {
    JsonNode root;
    try {
      root = objectMapper.readTree(body);
    } catch (IOException e) {
      throw new RuntimeException("Invalid JSON from embedding service (" + url + "): " + body, e);
    }

    // {"embeddings": [[float, ...]]}
    JsonNode embeddingsNode = root.get("embeddings");
    if (embeddingsNode == null || !embeddingsNode.isArray() || embeddingsNode.size() == 0) {
      throw new RuntimeException(
          "Embedding service response missing 'embeddings' array (" + url + "): " + body);
    }

    JsonNode vector = embeddingsNode.get(0);
    if (!vector.isArray() || vector.size() == 0) {
      throw new RuntimeException("Embedding service returned empty vector (" + url + "): " + body);
    }

    float[] result = new float[vector.size()];
    for (int i = 0; i < vector.size(); i++) {
      JsonNode val = vector.get(i);
      if (!val.isNumber()) {
        throw new RuntimeException(
            "Embedding vector contains non-numeric value at index " + i + " (" + url + ")");
      }
      result[i] = (float) val.asDouble();
    }

    log.debug("Received {}-dim embedding from {}", result.length, url);
    return result;
  }
}
