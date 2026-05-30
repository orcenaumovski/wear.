package com.vicevice.app.item;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vicevice.app.auth.AuthService;
import com.vicevice.app.outfit.SavedOutfitItemRepository;
import com.vicevice.app.storage.ImageStorageService;
import jakarta.validation.constraints.NotNull;
import jakarta.transaction.Transactional;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/items")
public class ItemController {
    private final ItemRepository itemRepository;
    private final ImageStorageService imageStorageService;
    private final ItemAnalysisService itemAnalysisService;
    private final ObjectMapper objectMapper;
    private final AuthService authService;
    private final SavedOutfitItemRepository savedOutfitItemRepository;

    public ItemController(
            ItemRepository itemRepository,
            ImageStorageService imageStorageService,
            ItemAnalysisService itemAnalysisService,
            ObjectMapper objectMapper,
            AuthService authService,
            SavedOutfitItemRepository savedOutfitItemRepository
    ) {
        this.itemRepository = itemRepository;
        this.imageStorageService = imageStorageService;
        this.itemAnalysisService = itemAnalysisService;
        this.objectMapper = objectMapper;
        this.authService = authService;
        this.savedOutfitItemRepository = savedOutfitItemRepository;
    }

    @GetMapping
    public List<ItemDto> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        AuthService.AuthenticatedUser user = authService.requireUser(authorization);
        return itemRepository.findByUserIdOrderByCreatedAtEpochMsDesc(user.id()).stream().map(it -> ItemDto.from(
                it,
                imageStorageService.filenameForClient(it.getImagePath()),
                null
        )).toList();
    }

    @GetMapping("/{id}")
    public ItemDto get(
            @PathVariable Integer id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        AuthService.AuthenticatedUser user = authService.requireUser(authorization);
        Item item = findItem(id, user);
        return ItemDto.from(item, imageStorageService.filenameForClient(item.getImagePath()), null);
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<Resource> image(
            @PathVariable Integer id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String token
    ) throws Exception {
        AuthService.AuthenticatedUser user = authService.requireUser(authorization, token);
        Item item = findItem(id, user);
        Path path = imageStorageService.resolve(item.getImagePath());
        Resource resource = new UrlResource(path.toUri());
        if (!resource.exists() || !resource.isReadable()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .header("X-Content-Type-Options", "nosniff")
                .contentType(mediaTypeFor(path))
                .body(resource);
    }

    /**
     * MVP: one request creates items, stores images, runs LLM vision analysis, and persists results.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<ItemDto> create(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String name,
            @RequestParam("images") @NotNull List<MultipartFile> images
    ) throws Exception {
        AuthService.AuthenticatedUser user = authService.requireUser(authorization);
        long now = System.currentTimeMillis();

        List<ItemDto> out = new ArrayList<>();
        for (MultipartFile img : images) {
            ImageStorageService.StoredImage stored = imageStorageService.store(img);

            Item item = new Item();
            item.setName(name);
            item.setImagePath(stored.filename());
            item.setCreatedAtEpochMs(now);
            item.setUserId(user.id());
            itemRepository.save(item);

            String analysisError = null;
            try {
                ItemAnalysisService.ItemAnalysisResult analysis =
                        itemAnalysisService.analyze(stored.path());

                applyAnalysis(item, analysis);
                itemRepository.save(item);
            } catch (Exception e) {
                // Keep the item (image is stored + row exists), but surface the error to the client.
                analysisError = cleanError(e);
            }

            out.add(ItemDto.from(item, imageStorageService.filenameForClient(item.getImagePath()), analysisError));
        }
        return out;
    }

    @PostMapping("/{id}/analyze")
    public ItemDto analyze(
            @PathVariable Integer id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) MultipartFile image
    ) throws Exception {
        AuthService.AuthenticatedUser user = authService.requireUser(authorization);
        Item item = findItem(id, user);
        try {
            ItemAnalysisService.ItemAnalysisResult analysis = image != null && !image.isEmpty()
                    ? itemAnalysisService.analyze(image.getBytes())
                    : itemAnalysisService.analyze(imageStorageService.resolve(item.getImagePath()));

            applyAnalysis(item, analysis);
            itemRepository.save(item);
            return ItemDto.from(item, imageStorageService.filenameForClient(item.getImagePath()), null);
        } catch (Exception e) {
            return ItemDto.from(item, imageStorageService.filenameForClient(item.getImagePath()), cleanError(e));
        }
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(
            @PathVariable Integer id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) throws Exception {
        AuthService.AuthenticatedUser user = authService.requireUser(authorization);
        Item item = findItem(id, user);
        imageStorageService.delete(item.getImagePath());
        savedOutfitItemRepository.deleteByIdItemId(item.getId());
        itemRepository.delete(item);
        return ResponseEntity.noContent().build();
    }

    private Item findItem(Integer id, AuthService.AuthenticatedUser user) {
        return itemRepository.findByIdAndUserId(id, user.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found: " + id));
    }

    private void applyAnalysis(Item item, ItemAnalysisService.ItemAnalysisResult analysis) throws Exception {
        item.setCategory(blankToNull(analysis.category()));
        if (analysis.tags() != null) {
            item.setTagsJson(objectMapper.writeValueAsString(analysis.tags()));
        }
        if (analysis.colors() != null) {
            item.setColorsJson(objectMapper.writeValueAsString(analysis.colors()));
        }
        if (!isBlank(analysis.title()) && shouldReplaceName(item.getName())) {
            item.setName(analysis.title().trim());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }

    private static boolean shouldReplaceName(String name) {
        if (isBlank(name)) {
            return true;
        }
        String normalized = name.trim().toLowerCase();
        return normalized.equals("no image provided")
                || normalized.equals("no photo")
                || normalized.equals("missing image")
                || normalized.equals("missing photo");
    }

    private static String cleanError(Exception e) {
        if (e instanceof ResponseStatusException responseStatusException && !isBlank(responseStatusException.getReason())) {
            return responseStatusException.getReason();
        }

        String message = e.getMessage();
        if (isBlank(message)) {
            return "Analysis failed";
        }
        int firstLineBreak = Math.min(
                message.indexOf('\n') >= 0 ? message.indexOf('\n') : message.length(),
                message.indexOf('\r') >= 0 ? message.indexOf('\r') : message.length()
        );
        String cleaned = message.substring(0, firstLineBreak).trim();
        if (cleaned.contains("Internal Server Error") && cleaned.contains("ref:")) {
            return "Ollama could not analyze this image. Try Analyze again so the browser sends a JPEG copy, or upload a JPEG/PNG version.";
        }
        return cleaned;
    }

    private static MediaType mediaTypeFor(Path path) throws Exception {
        String contentType = Files.probeContentType(path);
        if (!isBlank(contentType)) {
            return MediaType.parseMediaType(contentType);
        }

        String fileName = path.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (fileName.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (fileName.endsWith(".gif")) {
            return MediaType.IMAGE_GIF;
        }
        if (fileName.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        if (fileName.endsWith(".avif")) {
            return MediaType.parseMediaType("image/avif");
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    public record ItemDto(
            Integer id,
            String name,
            String category,
            String tagsJson,
            String colorsJson,
            String imagePath,
            String imageUrl,
            Long createdAtEpochMs,
            String analysisError
    ) {
        public static ItemDto from(Item item, String imagePath, String analysisError) {
            return new ItemDto(
                    item.getId(),
                    item.getName(),
                    item.getCategory(),
                    item.getTagsJson(),
                    item.getColorsJson(),
                    imagePath,
                    "/api/items/" + item.getId() + "/image",
                    item.getCreatedAtEpochMs(),
                    analysisError
            );
        }
    }
}

