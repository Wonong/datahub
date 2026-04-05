package com.linkedin.metadata.config.search;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration for embedding providers used to generate query embeddings for semantic search.
 *
 * <p>Supports five providers:
 *
 * <ul>
 *   <li><b>local-http</b>: Local embedding service (model loaded in a companion container). No API
 *       key required. Model is baked into the Docker image at build time.
 *   <li><b>aws-bedrock</b>: AWS Bedrock Runtime API with Cohere/Titan models
 *   <li><b>openai</b>: OpenAI Embeddings API with text-embedding-3-small/large/ada-002 models
 *   <li><b>cohere</b>: Cohere Embed API with embed-english-v3.0/multilingual-v3.0 models
 *   <li><b>huggingface</b>: HuggingFace managed Inference API (requires API key)
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmbeddingProviderConfiguration {

  /**
   * Type of embedding provider. Supported values: "local-http", "openai", "aws-bedrock", "cohere",
   * "huggingface". Defaults to "local-http".
   */
  private String type = "local-http";

  /**
   * Maximum text length in characters before truncation. Cohere Embed v3 enforces a 2048-character
   * limit on the request body separate from the token context window. Defaults to 2048.
   */
  private int maxCharacterLength = 2048;

  /** Configuration for AWS Bedrock embedding provider. */
  private BedrockConfig bedrock = new BedrockConfig();

  /** Configuration for OpenAI embedding provider. */
  private OpenAIConfig openai = new OpenAIConfig();

  /** Configuration for Cohere embedding provider. */
  private CohereConfig cohere = new CohereConfig();

  /** Configuration for HuggingFace managed Inference API (requires API key). */
  private HuggingFaceConfig huggingface = new HuggingFaceConfig();

  /** Configuration for local HTTP embedding service. */
  private HttpConfig localHttp = new HttpConfig();

  /**
   * Returns the model ID for the configured provider type, pulling from the appropriate sub-config.
   */
  public String getModelId() {
    if (type == null) {
      return null;
    }
    switch (type.toLowerCase()) {
      case "openai":
        return openai != null ? openai.getModel() : null;
      case "cohere":
        return cohere != null ? cohere.getModel() : null;
      case "aws-bedrock":
        return bedrock != null ? bedrock.getModel() : null;
      case "huggingface":
        return huggingface != null ? huggingface.getModel() : null;
      case "local-http":
        return "local";
      default:
        return null;
    }
  }

  /** AWS Bedrock-specific configuration. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class BedrockConfig {
    /**
     * AWS region where Bedrock is available (e.g., "us-west-2", "us-east-1"). Required for
     * aws-bedrock provider.
     */
    private String awsRegion = "us-west-2";

    /**
     * Bedrock model ID for embeddings. Defaults to "cohere.embed-english-v3" (1024 dimensions).
     * Other options: - "cohere.embed-multilingual-v3" (1024 dimensions) -
     * "amazon.titan-embed-text-v1" (1536 dimensions) - "amazon.titan-embed-text-v2:0" (1024
     * dimensions default)
     */
    private String model = "cohere.embed-english-v3";
  }

  /** OpenAI-specific configuration. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class OpenAIConfig {
    /**
     * OpenAI API key (starts with "sk-"). Required when type is "openai". Can be set via
     * OPENAI_API_KEY environment variable.
     */
    private String apiKey;

    /**
     * OpenAI embedding model. Supported models:
     *
     * <ul>
     *   <li><b>text-embedding-3-large</b> (default): 3072 dimensions, highest quality
     *   <li><b>text-embedding-3-small</b>: 1536 dimensions, optimized for speed and cost
     *   <li><b>text-embedding-ada-002</b>: 1536 dimensions, legacy model
     * </ul>
     *
     * Defaults to "text-embedding-3-large".
     */
    private String model = "text-embedding-3-large";

    /**
     * OpenAI API endpoint. Defaults to "https://api.openai.com/v1/embeddings". For Azure OpenAI,
     * use:
     * "https://{resource-name}.openai.azure.com/openai/deployments/{deployment-id}/embeddings?api-version=2023-05-15"
     */
    private String endpoint = "https://api.openai.com/v1/embeddings";
  }

  /** Cohere-specific configuration. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class CohereConfig {
    /**
     * Cohere API key. Required when type is "cohere". Can be set via COHERE_API_KEY environment
     * variable.
     */
    private String apiKey;

    /**
     * Cohere embedding model. Supported models:
     *
     * <ul>
     *   <li><b>embed-english-v3.0</b> (default): 1024 dimensions, English only
     *   <li><b>embed-multilingual-v3.0</b>: 1024 dimensions, 100+ languages
     *   <li><b>embed-english-light-v3.0</b>: 384 dimensions, faster and cheaper
     * </ul>
     *
     * Defaults to "embed-english-v3.0".
     */
    private String model = "embed-english-v3.0";

    /**
     * Cohere API endpoint. Defaults to "https://api.cohere.ai/v1/embed". For custom deployments,
     * specify the full embed endpoint URL.
     */
    private String endpoint = "https://api.cohere.ai/v1/embed";
  }

  /** HuggingFace managed Inference API configuration (requires API key). */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class HuggingFaceConfig {
    /** HuggingFace access token (starts with "hf_"). Required for the managed Inference API. */
    private String apiKey;

    /** HuggingFace model identifier. */
    private String model = "nlpai-lab/KURE-v1";

    /** Base URL of the inference server. */
    private String baseUrl = "https://api-inference.huggingface.co";
  }

  /**
   * Configuration for the local HTTP embedding service ({@code type: local-http}).
   *
   * <p>The service runs as a companion container, downloads the model at Docker build time, and
   * loads it into memory on startup. No API key is needed.
   *
   * <p>Set {@code EMBEDDING_SERVICE_URL} to point to a remote deployment when the embedding service
   * is extracted into its own deployment.
   */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class HttpConfig {
    /**
     * Base URL of the embedding service. Default assumes a companion container in the same
     * docker-compose stack reachable via service name.
     */
    private String baseUrl = "http://datahub-embedding-service:8766";

    /**
     * HTTP request timeout in seconds. Set high enough to survive the model cold-start (typically
     * 30–60 s on first request).
     */
    private int timeoutSeconds = 120;
  }
}
