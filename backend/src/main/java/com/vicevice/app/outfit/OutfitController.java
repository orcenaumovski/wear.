package com.vicevice.app.outfit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vicevice.app.item.Item;
import com.vicevice.app.item.ItemRepository;
import com.vicevice.app.ollama.OllamaClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/outfits")
public class OutfitController {
    private final ItemRepository itemRepository;
    private final OllamaClient ollamaClient;
    private final ObjectMapper objectMapper;

    public OutfitController(ItemRepository itemRepository, OllamaClient ollamaClient, ObjectMapper objectMapper) {
        this.itemRepository = itemRepository;
        this.ollamaClient = ollamaClient;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public OutfitResponse lastGenerated() {
        return new OutfitResponse(List.of());
    }

    @PostMapping("/generate")
    public OutfitResponse generate(@RequestParam(defaultValue = "3") int count) throws Exception {
        int outfitCount = Math.max(1, Math.min(5, count));
        List<Item> items = itemRepository.findAll();
        if (items.isEmpty()) {
            return new OutfitResponse(List.of());
        }

        Map<Integer, String> categoryByItemId = items.stream()
                .filter(it -> it.getId() != null)
                .collect(Collectors.toMap(
                        Item::getId,
                        Item::getCategory,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        List<ItemForModel> closet = items.stream().map(it -> new ItemForModel(
                it.getId(),
                it.getName(),
                it.getCategory(),
                parseJsonList(it.getColorsJson()),
                parseJsonList(it.getTagsJson())
        )).toList();

        String prompt = """
You are a wardrobe assistant. Generate EXACTLY %d outfit ideas using ONLY the provided items.

Constraints:
- Use only item ids that exist in the closet list.
- Each outfit should include shoes if available.
- Avoid using the same shoes in every outfit if there are alternatives.
- The role should match the closet role for that item so naming stays consistent across outfits.

Return ONLY valid JSON with this exact shape:
{
  "outfits": [
    {
      "name": "short name",
      "items": [
        { "itemId": 123, "role": "short role label" }
      ]
    }
  ]
}

Closet items:
        """.formatted(outfitCount) + objectMapper.writeValueAsString(closet);

        String content = ollamaClient.chat(prompt).trim();
        OutfitResponse firstAttempt = tryParseAndValidate(content, categoryByItemId, outfitCount);
        if (firstAttempt.outfits().size() == outfitCount) {
            return firstAttempt;
        }

        String repairPrompt = """
Your previous output was invalid or produced only %d usable outfits after validation.
Return ONLY valid JSON with EXACTLY %d outfits matching:
{ "outfits": [ { "name": "...", "items": [ { "itemId": 1, "role": "short role label" } ] } ] }

Validation rules:
- itemId MUST be one of these closet ids: %s
- role should match the closet role for that real item
- each outfit MUST contain at least one valid item
- do not invent item ids, categories, or photos

Here is the invalid output:
""".formatted(firstAttempt.outfits().size(), outfitCount, categoryByItemId.keySet()) + content;

        OutfitResponse repairedAttempt;
        try {
            String repaired = ollamaClient.chat(repairPrompt).trim();
            repairedAttempt = tryParseAndValidate(repaired, categoryByItemId, outfitCount);
        } catch (Exception e) {
            repairedAttempt = new OutfitResponse(List.of());
        }

        OutfitResponse bestAttempt = repairedAttempt.outfits().isEmpty() ? firstAttempt : repairedAttempt;
        return fillToCount(bestAttempt, categoryByItemId, outfitCount);
    }

    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String extractJsonObject(String s) {
        int first = s.indexOf('{');
        int last = s.lastIndexOf('}');
        if (first >= 0 && last > first) return s.substring(first, last + 1);
        return s;
    }

    private OutfitResponse tryParseAndValidate(String content, Map<Integer, String> categoryByItemId, int count) {
        try {
            String json = extractJsonObject(content);
            return validateOutfits(objectMapper.readValue(json, OutfitResponse.class), categoryByItemId, count);
        } catch (Exception e) {
            return new OutfitResponse(List.of());
        }
    }

    static OutfitResponse validateOutfits(OutfitResponse response, Map<Integer, String> categoryByItemId, int count) {
        if (response == null || response.outfits() == null) {
            return new OutfitResponse(List.of());
        }

        List<OutfitPlan> outfits = new ArrayList<>();
        for (OutfitPlan outfit : response.outfits()) {
            if (outfit == null || outfit.items() == null) {
                continue;
            }

            List<OutfitItemPlan> pieces = new ArrayList<>();
            Set<Integer> usedItemIds = new LinkedHashSet<>();
            for (OutfitItemPlan piece : outfit.items()) {
                if (piece == null || piece.itemId() == null || !categoryByItemId.containsKey(piece.itemId())) {
                    continue;
                }
                if (!usedItemIds.add(piece.itemId())) {
                    continue;
                }

                String role = normalizeRole(piece.role(), categoryByItemId.get(piece.itemId()));
                pieces.add(new OutfitItemPlan(piece.itemId(), role));
            }

            if (!pieces.isEmpty()) {
                outfits.add(new OutfitPlan(
                        defaultString(outfit.name(), "Outfit " + (outfits.size() + 1)),
                        pieces,
                        null
                ));
            }
            if (outfits.size() == count) {
                break;
            }
        }
        return new OutfitResponse(outfits);
    }

    static OutfitResponse fillToCount(OutfitResponse response, Map<Integer, String> categoryByItemId, int count) {
        List<OutfitPlan> outfits = new ArrayList<>(response.outfits() == null ? List.of() : response.outfits());
        int fallbackIndex = 0;
        while (outfits.size() < count && !categoryByItemId.isEmpty()) {
            List<OutfitItemPlan> pieces = fallbackPieces(categoryByItemId, fallbackIndex);
            if (pieces.isEmpty()) {
                break;
            }
            outfits.add(new OutfitPlan(
                    "Closet mix " + (outfits.size() + 1),
                    pieces,
                    null
            ));
            fallbackIndex++;
        }
        return new OutfitResponse(outfits.size() <= count ? outfits : outfits.subList(0, count));
    }

    private static List<OutfitItemPlan> fallbackPieces(Map<Integer, String> categoryByItemId, int seed) {
        List<OutfitItemPlan> pieces = new ArrayList<>();
        Set<Integer> usedItemIds = new LinkedHashSet<>();

        Integer dress = pickByRole(categoryByItemId, "dress", seed, usedItemIds);
        if (dress != null) {
            pieces.add(new OutfitItemPlan(dress, "dress"));
        } else {
            addIfPresent(pieces, usedItemIds, pickByRole(categoryByItemId, "top", seed, usedItemIds), "top");
            addIfPresent(pieces, usedItemIds, pickByRole(categoryByItemId, "bottom", seed, usedItemIds), "bottom");
        }

        addIfPresent(pieces, usedItemIds, pickByRole(categoryByItemId, "shoes", seed, usedItemIds), "shoes");
        addIfPresent(pieces, usedItemIds, pickByRole(categoryByItemId, "outerwear", seed, usedItemIds), "outerwear");
        addIfPresent(pieces, usedItemIds, pickByRole(categoryByItemId, "accessory", seed, usedItemIds), "accessory");

        if (pieces.isEmpty()) {
            List<Integer> itemIds = new ArrayList<>(categoryByItemId.keySet());
            Integer itemId = itemIds.get(Math.floorMod(seed, itemIds.size()));
            pieces.add(new OutfitItemPlan(itemId, fallbackRole(categoryByItemId.get(itemId))));
        }

        return pieces;
    }

    private static Integer pickByRole(Map<Integer, String> categoryByItemId, String role, int seed, Set<Integer> usedItemIds) {
        List<Integer> matchingIds = categoryByItemId.entrySet().stream()
                .filter(entry -> role.equals(normalizeCategory(entry.getValue())))
                .map(Map.Entry::getKey)
                .filter(id -> !usedItemIds.contains(id))
                .toList();
        if (matchingIds.isEmpty()) {
            return null;
        }
        return matchingIds.get(Math.floorMod(seed, matchingIds.size()));
    }

    private static void addIfPresent(List<OutfitItemPlan> pieces, Set<Integer> usedItemIds, Integer itemId, String role) {
        if (itemId != null && usedItemIds.add(itemId)) {
            pieces.add(new OutfitItemPlan(itemId, role));
        }
    }

    private static String normalizeRole(String role, String itemCategory) {
        String normalizedCategory = cleanRole(itemCategory);
        if (normalizedCategory != null) {
            return normalizedCategory;
        }
        return fallbackRole(role);
    }

    private static String fallbackRole(String itemCategory) {
        String normalized = cleanRole(itemCategory);
        return normalized == null ? "item" : normalized;
    }

    private static String normalizeCategory(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized;
    }

    private static String cleanRole(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String cleaned = value.trim().replaceAll("\\s+", " ");
        if (cleaned.length() > 40) {
            cleaned = cleaned.substring(0, 40).trim();
        }
        return cleaned.isBlank() ? null : cleaned;
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public record OutfitResponse(List<OutfitPlan> outfits) {}

    public record OutfitPlan(String name, List<OutfitItemPlan> items, String reasoning) {}

    public record OutfitItemPlan(Integer itemId, String role) {}

    public record ItemForModel(
            Integer id,
            String name,
            String category,
            List<String> colors,
            List<String> tags
    ) {}
}

