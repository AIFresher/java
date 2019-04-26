/*
Copyright 2019 The Kubernetes Authors.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */
package io.kubernetes.client.pager;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.Assert.*;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.google.common.io.Resources;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.V1Namespace;
import io.kubernetes.client.models.V1NamespaceList;
import io.kubernetes.client.util.ClientBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class PagerTest {

  private ApiClient client;
  private static final String LIST_PAGE1_FILE_PATH =
      Resources.getResource("namespace-list-pager1.json").getPath();
  private static final String LIST_PAGE2_FILE_PATH =
      Resources.getResource("namespace-list-pager2.json").getPath();
  private static final String LIST_STATUS_FILE_PATH =
      Resources.getResource("status-400.json").getPath();
  private static final String STATUS_BAD_TOKEN_FILE_PATH =
      Resources.getResource("bad-token-status.json").getPath();
  private static final int PORT = 8087;
  @Rule public WireMockRule wireMockRule = new WireMockRule(PORT);

  @Before
  public void setup() throws IOException {
    client = new ClientBuilder().setBasePath("http://localhost:" + PORT).build();
  }

  @Test
  public void testPaginationForNamespaceListWithSuccessThreadSafely() throws IOException {
    String namespaceListPage1Str = new String(Files.readAllBytes(Paths.get(LIST_PAGE1_FILE_PATH)));
    String namespaceListPage2Str = new String(Files.readAllBytes(Paths.get(LIST_PAGE2_FILE_PATH)));
    CoreV1Api api = new CoreV1Api(client);

    stubFor(
        get(urlPathEqualTo("/api/v1/namespaces"))
            .withQueryParam("limit", equalTo("1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(namespaceListPage1Str)));
    stubFor(
        get(urlPathEqualTo("/api/v1/namespaces"))
            .withQueryParam("limit", equalTo("1"))
            .withQueryParam("continue", equalTo("c1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(namespaceListPage2Str)));

    int threads = 10;
    CountDownLatch latch = new CountDownLatch(threads);
    ExecutorService service = Executors.newFixedThreadPool(threads);

    Pager<V1Namespace, V1NamespaceList> pager =
        new Pager<V1Namespace, V1NamespaceList>(
            (Pager.PagerParams param) -> {
              try {
                return api.listNamespaceCall(
                    null,
                    null,
                    param.getContinueToken(),
                    null,
                    null,
                    param.getLimit(),
                    null,
                    null,
                    null,
                    null,
                    null);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            },
            client,
            1,
            V1NamespaceList.class);

    for (int i = 0; i < threads; i++) {
      service.submit(
          () -> {
            int size = 0;
            for (V1Namespace namespace : pager) {
              assertEquals("default", namespace.getMetadata().getName());
              size++;
            }
            assertEquals(2, size);
            latch.countDown();
          });
    }

    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      fail("timed out waiting for pager finished");
    }

    verify(
        2 * threads,
        getRequestedFor(urlPathEqualTo("/api/v1/namespaces"))
            .withQueryParam("limit", equalTo("1")));
  }

  @Test
  public void testPaginationForNamespaceListWithBadTokenFailure() throws IOException {
    String status400Str = new String(Files.readAllBytes(Paths.get(STATUS_BAD_TOKEN_FILE_PATH)));
    CoreV1Api api = new CoreV1Api(client);

    stubFor(
        get(urlPathEqualTo("/api/v1/namespaces"))
            .withQueryParam("limit", equalTo("1"))
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withHeader("Content-Type", "application/json")
                    .withBody(status400Str)));
    Pager<V1Namespace, V1NamespaceList> pager =
        new Pager<V1Namespace, V1NamespaceList>(
            (Pager.PagerParams param) -> {
              try {
                return api.listNamespaceCall(
                    null,
                    null,
                    param.getContinueToken(),
                    null,
                    null,
                    param.getLimit(),
                    null,
                    null,
                    null,
                    null,
                    null);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            },
            client,
            1,
            V1NamespaceList.class);
    int count = 0;
    try {
      for (V1Namespace namespace : pager) {
        assertEquals("default", namespace.getMetadata().getName());
        count++;
      }
    } catch (Exception e) {
      assertEquals(status400Str, e.getMessage());
    }

    verify(
        getRequestedFor(urlPathEqualTo("/api/v1/namespaces"))
            .withQueryParam("limit", equalTo("1")));
  }

  @Test
  public void testPaginationForNamespaceListWithFieldSelectorFailure() throws IOException {
    String status400Str = new String(Files.readAllBytes(Paths.get(LIST_STATUS_FILE_PATH)));
    CoreV1Api api = new CoreV1Api(client);

    stubFor(
        get(urlPathEqualTo("/api/v1/namespaces"))
            .withQueryParam("fieldSelector", equalTo("metadata.name=default"))
            .withQueryParam("limit", equalTo("1"))
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withHeader("Content-Type", "application/json")
                    .withBody(status400Str)));
    Pager<V1Namespace, V1NamespaceList> pager =
        new Pager<V1Namespace, V1NamespaceList>(
            (Pager.PagerParams param) -> {
              try {
                return api.listNamespaceCall(
                    null,
                    null,
                    param.getContinueToken(),
                    "metadata.name=default",
                    null,
                    param.getLimit(),
                    null,
                    null,
                    null,
                    null,
                    null);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            },
            client,
            1,
            V1NamespaceList.class);
    int count = 0;
    try {
      for (V1Namespace namespace : pager) {
        count++;
        assertEquals("default", namespace.getMetadata().getName());
      }
    } catch (Exception e) {
      assertEquals(status400Str, e.getMessage());
    }

    verify(
        getRequestedFor(urlPathEqualTo("/api/v1/namespaces"))
            .withQueryParam("fieldSelector", equalTo("metadata.name=default"))
            .withQueryParam("limit", equalTo("1")));
  }
}