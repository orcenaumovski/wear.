package com.vicevice.app.ollama;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.client.MockRestServiceServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OllamaClientTest {
    @Test
    void returnsChatContentFromOllama() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ollama.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OllamaClient client = new OllamaClient(builder.build(), "gemma3:4b");

        server.expect(requestTo("http://ollama.test/api/chat"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"message\":{\"role\":\"assistant\",\"content\":\"hello\"}}",
                        MediaType.APPLICATION_JSON
                ));

        assertThat(client.chat("Say hello")).isEqualTo("hello");
        server.verify();
    }

    @Test
    void turnsTimeoutsIntoGatewayTimeout() {
        RestClient restClient = RestClient.builder()
                .requestInterceptor((request, body, execution) -> {
                    throw new ResourceAccessException("read timed out");
                })
                .build();
        OllamaClient client = new OllamaClient(restClient, "gemma3:4b");

        assertThatThrownBy(() -> client.chat("hello"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
                    assertThat(ex.getReason()).contains("Ollama request timed out");
                });
    }

    @Test
    void turnsOllamaHttpErrorsIntoBadGateway() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ollama.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OllamaClient client = new OllamaClient(builder.build(), "gemma3:4b");

        server.expect(requestTo("http://ollama.test/api/chat"))
                .andRespond(withServerError().body("model failed"));

        assertThatThrownBy(() -> client.chat("hello"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(ex.getReason()).contains("Ollama returned HTTP 500");
                });
        server.verify();
    }
}
