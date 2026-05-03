package backend.backend.service;

import backend.backend.configuration.GeminiKeyProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Service
@Slf4j
public class GeminiService {

    private final GeminiKeyProperties geminiKeyProperties;
    private String apiKey;

    private String apiUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent";

    private final RestTemplate restTemplate = new RestTemplate();

    @PostConstruct
    public void init() {
        apiKey = geminiKeyProperties.getKey();
        log.info("GeminiService initialized with API URL: {}");
    }

    public String chatWithGemini(String prompt) {
        try {
            log.info("Sending prompt to Gemini: {}", prompt.substring(0, Math.min(50, prompt.length())));

            Map<String, Object> request = Map.of(
                    "contents", List.of(
                            Map.of("parts", List.of(
                                    Map.of("text", prompt)
                            ))
                    )
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            ResponseEntity<Map> response =
                    restTemplate.postForEntity(apiUrl + "?key=" + apiKey, entity, Map.class);

            return parseGeminiResponse(response.getBody());

        } catch (RestClientException e) {
            log.error("RestClientException while calling Gemini API: {}", e.getMessage());
            return "I'm having trouble connecting to the AI service. Please try again in a moment.";
        } catch (Exception e) {
            log.error("Unexpected error while calling Gemini API: {}", e.getMessage(), e);
            return "An unexpected error occurred. Please try again.";
        }
    }

    private String parseGeminiResponse(Map<String, Object> body) {
        if (body == null) {
            log.error("Gemini returned null response body");
            return "I couldn't get a response. Please try again.";
        }

        log.debug("Gemini response: {}", body);

        if (body.containsKey("error")) {
            Map<String, Object> error = (Map<String, Object>) body.get("error");
            String errorMessage = error != null ? error.get("message").toString() : "Unknown error";
            log.error("Gemini API error: {}", errorMessage);

            if (errorMessage.contains("billing") || errorMessage.contains("quota") || errorMessage.contains("PERMISSION_DENIED")) {
                return "The AI service requires billing to be enabled. Please contact support.";
            }

            return "I encountered an error: " + errorMessage;
        }

        List<Map<String, Object>> candidates =
                (List<Map<String, Object>>) body.getOrDefault("candidates", Collections.emptyList());

        if (candidates.isEmpty()) {
            log.warn("Gemini returned no candidates. Full response: {}", body);
            return "I couldn't generate a response. Please try rephrasing your question.";
        }

        Map<String, Object> firstCandidate = candidates.get(0);
        if (firstCandidate == null) {
            log.warn("First candidate was null");
            return "I couldn't generate a response. Please try again.";
        }

        Map<String, Object> content =
                (Map<String, Object>) firstCandidate.get("content");

        if (content == null) {
            log.warn("Content was null in candidate");
            return "I couldn't generate a response. Please try again.";
        }

        List<Map<String, Object>> parts =
                (List<Map<String, Object>>) content.getOrDefault("parts", Collections.emptyList());

        if (parts.isEmpty()) {
            log.warn("Parts list was empty. Content: {}", content);
            return "I couldn't generate a text response. Please try again.";
        }

        Map<String, Object> firstPart = parts.get(0);
        if (firstPart == null || !firstPart.containsKey("text")) {
            log.warn("First part was null or had no text. Parts: {}", parts);
            return "I couldn't generate a text response. Please try again.";
        }

        String responseText = String.valueOf(firstPart.get("text"));
        log.info("Successfully received response from Gemini (length: {})", responseText.length());
        return responseText;
    }
}