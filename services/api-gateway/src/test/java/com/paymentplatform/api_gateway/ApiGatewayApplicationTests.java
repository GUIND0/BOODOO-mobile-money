package com.paymentplatform.api_gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiGatewayApplicationTests {

    @LocalServerPort
    int port;

    @Test
    void startsAndReportsHealthy() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/actuator/health")).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"status\":\"UP\"");
        }
    }

    @Test
    void rejectsUnauthenticatedRequestsOutsideHealth() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (String path : new String[]{"/actuator/info", "/v1/api-gateway/unconfigured"}) {
                HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + path)).GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                assertThat(response.statusCode()).as(path).isEqualTo(401);
                assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
            }
        }
    }

}
