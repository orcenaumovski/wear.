package com.vicevice.app.ollama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;

@Component
public class OllamaClient {
    private final RestClient restClient;
    private final String model;

    @Autowired
    public OllamaClient(
            @Value("${app.ollama.baseUrl}") String baseUrl,
            @Value("${app.ollama.model}") String model,
            @Value("${app.ollama.connectTimeout}") Duration connectTimeout,
            @Value("${app.ollama.readTimeout}") Duration readTimeout
    ) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(connectTimeout, readTimeout))
                .build();
        this.model = model;
    }

    OllamaClient(RestClient restClient, String model) {
        this.restClient = restClient;
        this.model = model;
    }

    public String chatWithImage(String prompt, String imageBase64) {
        ChatRequest req = new ChatRequest(
                model,
                false,
                List.of(new Message("user", prompt, List.of(imageBase64)))
        );

        return send(req);
    }

    public String chat(String prompt) {
        ChatRequest req = new ChatRequest(
                model,
                false,
                List.of(new Message("user", prompt, null))
        );

        return send(req);
    }

    private String send(ChatRequest req) {
        try {
            ChatResponse res = restClient.post()
                    .uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(req)
                    .retrieve()
                    .body(ChatResponse.class);

            if (res == null || res.message == null || res.message.content == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Ollama returned an empty response.");
            }
            return res.message.content;
        } catch (ResourceAccessException e) {
            throw new ResponseStatusException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "Ollama request timed out or Ollama is not reachable. Check that Ollama is running and the model is available.",
                    e
            );
        } catch (RestClientResponseException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Ollama returned HTTP " + e.getStatusCode().value() + ": " + cleanResponseBody(e.getResponseBodyAsString()),
                    e
            );
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Ollama request failed.", e);
        }
    }

    private static SimpleClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    private static String cleanResponseBody(String body) {
        if (body == null || body.isBlank()) {
            return "empty error body";
        }
        String compact = body.replaceAll("\\s+", " ").trim();
        return compact.length() <= 220 ? compact : compact.substring(0, 220) + "...";
    }

    public record ChatRequest(String model, boolean stream, List<Message> messages) {}

    public record Message(String role, String content, List<String> images) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChatResponse {
        public MessageOut message;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MessageOut {
        public String role;
        public String content;
    }
}

