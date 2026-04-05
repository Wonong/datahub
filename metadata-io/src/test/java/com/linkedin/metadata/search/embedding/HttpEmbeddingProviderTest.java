package com.linkedin.metadata.search.embedding;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** Unit tests for {@link HttpEmbeddingProvider}. */
@SuppressWarnings({"unchecked", "rawtypes"})
public class HttpEmbeddingProviderTest {

  private HttpClient mockHttpClient;
  private HttpEmbeddingProvider provider;

  @BeforeMethod
  public void setup() {
    mockHttpClient = mock(HttpClient.class);
    provider =
        new HttpEmbeddingProvider("http://localhost:8766", Duration.ofSeconds(5), mockHttpClient);
  }

  // ---------------------------------------------------------------------------
  // Happy path
  // ---------------------------------------------------------------------------

  @Test
  public void testSuccessfulEmbedding() throws Exception {
    String responseJson = "{\"embeddings\": [[0.1, 0.2, 0.3]]}";
    mockResponse(200, responseJson);

    float[] result = provider.embed("test text", null);

    assertNotNull(result);
    assertEquals(result.length, 3);
    assertEquals(result[0], 0.1f, 0.001f);
    assertEquals(result[1], 0.2f, 0.001f);
    assertEquals(result[2], 0.3f, 0.001f);
  }

  @Test
  public void testModelParameterIsIgnored() throws Exception {
    // The HTTP provider delegates model selection to the server; the model arg is irrelevant.
    mockResponse(200, "{\"embeddings\": [[0.5, 0.6]]}");
    float[] result = provider.embed("hello", "some-model");
    assertEquals(result.length, 2);
  }

  @Test
  public void testHighDimensionalVector() throws Exception {
    StringBuilder sb = new StringBuilder("{\"embeddings\": [[");
    for (int i = 0; i < 768; i++) {
      if (i > 0) sb.append(",");
      sb.append("0.").append(i % 10);
    }
    sb.append("]]}");
    mockResponse(200, sb.toString());

    float[] result = provider.embed("768-dim test", null);
    assertEquals(result.length, 768);
  }

  // ---------------------------------------------------------------------------
  // Error handling — non-retryable
  // ---------------------------------------------------------------------------

  @Test(expectedExceptions = RuntimeException.class)
  public void testClientErrorIsNotRetried() throws Exception {
    mockResponse(400, "{\"detail\": \"bad request\"}");
    provider.embed("bad input", null);
  }

  @Test(expectedExceptions = RuntimeException.class)
  public void testMissingEmbeddingsFieldThrows() throws Exception {
    mockResponse(200, "{\"result\": [[0.1]]}");
    provider.embed("text", null);
  }

  @Test(expectedExceptions = RuntimeException.class)
  public void testEmptyEmbeddingsArrayThrows() throws Exception {
    mockResponse(200, "{\"embeddings\": []}");
    provider.embed("text", null);
  }

  @Test(expectedExceptions = RuntimeException.class)
  public void testEmptyVectorThrows() throws Exception {
    mockResponse(200, "{\"embeddings\": [[]]}");
    provider.embed("text", null);
  }

  @Test(expectedExceptions = RuntimeException.class)
  public void testNonNumericVectorThrows() throws Exception {
    mockResponse(200, "{\"embeddings\": [[\"a\", \"b\"]]}");
    provider.embed("text", null);
  }

  @Test(expectedExceptions = RuntimeException.class)
  public void testInvalidJsonThrows() throws Exception {
    mockResponse(200, "not-json");
    provider.embed("text", null);
  }

  // ---------------------------------------------------------------------------
  // Error handling — retryable (5xx)
  // ---------------------------------------------------------------------------

  @Test
  public void testServerErrorIsRetriedAndSucceeds() throws Exception {
    HttpResponse errorResponse = mock(HttpResponse.class);
    when(errorResponse.statusCode()).thenReturn(503);
    when(errorResponse.body()).thenReturn("{\"detail\": \"model loading\"}");

    HttpResponse successResponse = mock(HttpResponse.class);
    when(successResponse.statusCode()).thenReturn(200);
    when(successResponse.body()).thenReturn("{\"embeddings\": [[0.1, 0.2]]}");

    when(mockHttpClient.send(any(), any())).thenReturn(errorResponse).thenReturn(successResponse);

    float[] result = provider.embed("text", null);
    assertEquals(result.length, 2);
    verify(mockHttpClient, times(2)).send(any(), any());
  }

  @Test(expectedExceptions = RuntimeException.class)
  public void testAllAttemptsFailThrows() throws Exception {
    when(mockHttpClient.send(any(), any())).thenThrow(new IOException("connection refused"));
    provider.embed("text", null);
  }

  @Test
  public void testIoExceptionIsRetriedOnce() throws Exception {
    HttpResponse success = mock(HttpResponse.class);
    when(success.statusCode()).thenReturn(200);
    when(success.body()).thenReturn("{\"embeddings\": [[0.9]]}");

    when(mockHttpClient.send(any(), any()))
        .thenThrow(new IOException("connection reset"))
        .thenReturn(success);

    float[] result = provider.embed("text", null);
    assertEquals(result.length, 1);
    verify(mockHttpClient, times(2)).send(any(), any());
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private void mockResponse(int status, String body) throws Exception {
    HttpResponse response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(response.body()).thenReturn(body);
    when(mockHttpClient.send(any(), any())).thenReturn(response);
  }
}
