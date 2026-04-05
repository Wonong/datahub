package com.linkedin.metadata.search.embedding;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** Unit tests for HuggingFaceEmbeddingProvider. */
public class HuggingFaceEmbeddingProviderTest {

  private HttpClient mockHttpClient;
  private HttpResponse<String> mockResponse;
  private HuggingFaceEmbeddingProvider provider;

  @BeforeMethod
  @SuppressWarnings("unchecked")
  public void setup() {
    mockHttpClient = mock(HttpClient.class);
    mockResponse = mock(HttpResponse.class);
    provider =
        new HuggingFaceEmbeddingProvider(
            "hf_test_token",
            "https://api-inference.huggingface.co",
            "nlpai-lab/KURE-v1",
            mockHttpClient);
  }

  @Test
  public void testEmbedSuccess() throws Exception {
    // HuggingFace returns [[float, ...]] — outer array is per-input
    String responseJson = "[[0.1, 0.2, 0.3]]";

    when(mockResponse.statusCode()).thenReturn(200);
    when(mockResponse.body()).thenReturn(responseJson);
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(mockResponse);

    float[] embedding = provider.embed("주문 테이블", null);

    assertNotNull(embedding);
    assertEquals(embedding.length, 3);
    assertEquals(embedding[0], 0.1f, 0.001f);
    assertEquals(embedding[1], 0.2f, 0.001f);
    assertEquals(embedding[2], 0.3f, 0.001f);
    verify(mockHttpClient, times(1))
        .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
  }

  @Test
  public void testEmbedWithCustomModel() throws Exception {
    String responseJson = "[[0.5, 0.6]]";

    when(mockResponse.statusCode()).thenReturn(200);
    when(mockResponse.body()).thenReturn(responseJson);
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(mockResponse);

    float[] embedding = provider.embed("test", "BAAI/bge-m3");

    assertNotNull(embedding);
    assertEquals(embedding.length, 2);
    verify(mockHttpClient, times(1))
        .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
  }

  @Test
  public void testEmbedWith768Dimensions() throws Exception {
    // kure-v1 produces 768-dimensional vectors
    StringBuilder sb = new StringBuilder("[[");
    for (int i = 0; i < 768; i++) {
      if (i > 0) sb.append(", ");
      sb.append(String.format("%.6f", (i * 0.001) - 0.384));
    }
    sb.append("]]");

    when(mockResponse.statusCode()).thenReturn(200);
    when(mockResponse.body()).thenReturn(sb.toString());
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(mockResponse);

    float[] embedding = provider.embed("orders table with customer and product information", null);

    assertNotNull(embedding);
    assertEquals(embedding.length, 768, "kure-v1 should return 768 dimensions");
  }

  @Test(expectedExceptions = RuntimeException.class)
  public void testEmbedWith401Unauthorized() throws Exception {
    String errorJson = "{\"error\": \"Authorization header is invalid\"}";

    when(mockResponse.statusCode()).thenReturn(401);
    when(mockResponse.body()).thenReturn(errorJson);
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(mockResponse);

    provider.embed("test", null);
  }

  @Test(expectedExceptions = RuntimeException.class)
  public void testEmbedWith400BadRequest() throws Exception {
    String errorJson = "{\"error\": \"Bad request\"}";

    when(mockResponse.statusCode()).thenReturn(400);
    when(mockResponse.body()).thenReturn(errorJson);
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(mockResponse);

    provider.embed("test", null);
  }

  @Test(expectedExceptions = RuntimeException.class)
  public void testEmbedWith503ModelLoadingExhaustsRetries() throws Exception {
    // 503 means the model is still loading — retried, but eventually fails
    String errorJson =
        "{\"error\": \"Model nlpai-lab/KURE-v1 is currently loading\", \"estimated_time\": 20}";

    when(mockResponse.statusCode()).thenReturn(503);
    when(mockResponse.body()).thenReturn(errorJson);
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(mockResponse);

    try {
      provider.embed("test", null);
    } finally {
      // 503 is retryable — should attempt MAX_ATTEMPTS (2) times
      verify(mockHttpClient, times(2))
          .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }
  }

  @Test
  public void testEmbedRetrySucceedsOnSecondAttempt() throws Exception {
    String successJson = "[[0.1, 0.2, 0.3]]";

    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new IOException("Connection reset"))
        .thenReturn(mockResponse);
    when(mockResponse.statusCode()).thenReturn(200);
    when(mockResponse.body()).thenReturn(successJson);

    float[] embedding = provider.embed("test", null);

    assertNotNull(embedding);
    assertEquals(embedding.length, 3);
    verify(mockHttpClient, times(2))
        .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
  }

  @Test(expectedExceptions = RuntimeException.class)
  public void testEmbedIOExceptionExhaustsRetries() throws Exception {
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new IOException("Network error"));

    try {
      provider.embed("test", null);
    } finally {
      verify(mockHttpClient, times(2))
          .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }
  }

  @Test(expectedExceptions = RuntimeException.class)
  public void testEmbedEmptyOuterArray() throws Exception {
    when(mockResponse.statusCode()).thenReturn(200);
    when(mockResponse.body()).thenReturn("[]");
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(mockResponse);

    provider.embed("test", null);
  }

  @Test(expectedExceptions = RuntimeException.class)
  public void testEmbedInnerArrayIsNotArray() throws Exception {
    // Unexpected format: outer is array but inner element is a scalar
    when(mockResponse.statusCode()).thenReturn(200);
    when(mockResponse.body()).thenReturn("[0.1, 0.2, 0.3]");
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(mockResponse);

    provider.embed("test", null);
  }

  @Test(expectedExceptions = RuntimeException.class)
  public void testEmbedNonNumericValues() throws Exception {
    when(mockResponse.statusCode()).thenReturn(200);
    when(mockResponse.body()).thenReturn("[[\"invalid\", \"values\"]]");
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(mockResponse);

    provider.embed("test", null);
  }

  @Test(expectedExceptions = RuntimeException.class)
  public void testEmbedInvalidJson() throws Exception {
    when(mockResponse.statusCode()).thenReturn(200);
    when(mockResponse.body()).thenReturn("not json{{");
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(mockResponse);

    provider.embed("test", null);
  }

  @Test(expectedExceptions = NullPointerException.class)
  public void testEmbedNullText() {
    provider.embed(null, null);
  }

  @Test
  public void testConstructorWithApiKeyOnly() {
    HuggingFaceEmbeddingProvider p = new HuggingFaceEmbeddingProvider("hf_test");
    assertNotNull(p);
  }

  @Test
  public void testConstructorWithAllParameters() {
    HuggingFaceEmbeddingProvider p =
        new HuggingFaceEmbeddingProvider("hf_test", "http://localhost:8080", "nlpai-lab/KURE-v1");
    assertNotNull(p);
  }

  @Test
  public void testSelfHostedServerWithoutApiKey() throws Exception {
    // Self-hosted TEI servers do not require authentication
    HuggingFaceEmbeddingProvider unauthProvider =
        new HuggingFaceEmbeddingProvider(
            "", "http://localhost:8080", "nlpai-lab/KURE-v1", mockHttpClient);

    String responseJson = "[[0.1, 0.2]]";
    when(mockResponse.statusCode()).thenReturn(200);
    when(mockResponse.body()).thenReturn(responseJson);
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(mockResponse);

    float[] embedding = unauthProvider.embed("test", null);

    assertNotNull(embedding);
    assertEquals(embedding.length, 2);
  }
}
