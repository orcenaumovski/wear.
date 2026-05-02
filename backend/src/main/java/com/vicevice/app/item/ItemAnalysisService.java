package com.vicevice.app.item;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vicevice.app.ollama.OllamaClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

@Service
public class ItemAnalysisService {
    private final OllamaClient ollamaClient;
    private final ObjectMapper objectMapper;

    public ItemAnalysisService(OllamaClient ollamaClient, ObjectMapper objectMapper) {
        this.ollamaClient = ollamaClient;
        this.objectMapper = objectMapper;
    }

    public ItemAnalysisResult analyze(Path imagePath) throws IOException {
        return analyze(Files.readAllBytes(imagePath));
    }

    public ItemAnalysisResult analyze(byte[] bytes) throws IOException {
        String b64 = Base64.getEncoder().encodeToString(bytes);

        String prompt = """
You are a wardrobe assistant. Analyze the given photo of a SINGLE clothing item (or shoe/accessory).
The image is attached in the request images array. If the photo loads, identify the clothing piece in it.

Return ONLY valid JSON with this exact shape:
{
  "category": "short role label",
  "title": "short human label",
  "colors": ["color1","color2"],
  "tags": ["tag1","tag2"]
}

Rules:
- No markdown. No explanations. JSON only.
- category is the closet role used for grouping. Choose a short, natural label such as t-shirts, pants, outerwear, shoewear, jewelry, bag, hat, dress, watch, belt, or unknown.
- Do not force the item into a fixed list. If a new role fits better, use it.
- colors should be simple names (e.g., "black", "white", "navy", "beige", "red").
- tags should be short (e.g., "casual", "formal", "sporty", "summer", "winter", "pattern:striped", "material:denim").
""";

        String content = ollamaClient.chatWithImage(prompt, b64).trim();

        // If the model wraps JSON in text, try to recover by slicing between first '{' and last '}'
        int first = content.indexOf('{');
        int last = content.lastIndexOf('}');
        if (first >= 0 && last > first) {
            content = content.substring(first, last + 1);
        }

        try {
            return validate(objectMapper.readValue(content, ItemAnalysisResult.class));
        } catch (Exception firstParseFail) {
            // Retry once with a stricter correction prompt (still no "ML pipeline", just prompt repair)
            String repairPrompt = """
You previously returned invalid JSON.
Return ONLY valid JSON matching:
{
  "category": "short role label",
  "title": "short human label",
  "colors": ["color1","color2"],
  "tags": ["tag1","tag2"]
}

The category must be a short, natural closet role. Prefer labels such as t-shirts and shoewear over shirt and sneakers.
It can also be a new role such as jewelry, bag, watch, belt, or hat when that fits the image.
""";
            String repaired = ollamaClient.chatWithImage(repairPrompt + "\n\nHere is the invalid output:\n" + content, b64).trim();
            int f = repaired.indexOf('{');
            int l = repaired.lastIndexOf('}');
            if (f >= 0 && l > f) {
                repaired = repaired.substring(f, l + 1);
            }
            try {
                return validate(objectMapper.readValue(repaired, ItemAnalysisResult.class));
            } catch (Exception secondFail) {
                throw new IOException("Failed to parse model JSON output", secondFail);
            }
        }
    }

    private static ItemAnalysisResult validate(ItemAnalysisResult result) throws IOException {
        if (result == null) {
            throw new IOException("Model returned empty item analysis");
        }
        if (looksLikeMissingImage(result)) {
            throw new IOException("Model did not read the attached image. Try analyzing again with the converted JPEG preview.");
        }
        return result;
    }

    private static boolean looksLikeMissingImage(ItemAnalysisResult result) {
        String joined = String.join(" ",
                safe(result.category()),
                safe(result.title()),
                String.join(" ", safeList(result.colors())),
                String.join(" ", safeList(result.tags()))
        ).toLowerCase(Locale.ROOT);

        return joined.contains("no image")
                || joined.contains("no photo")
                || joined.contains("missing-image")
                || joined.contains("missing image")
                || joined.contains("error:no")
                || joined.contains("error:missing");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    public record ItemAnalysisResult(
            String category,
            String title,
            List<String> colors,
            List<String> tags
    ) {}
}

