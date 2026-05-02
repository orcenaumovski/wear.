package com.vicevice.app.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class ImageStorageService {
    private final Path imagesDir;

    public ImageStorageService(@Value("${app.storage.imagesDir}") String imagesDir) {
        this.imagesDir = Path.of(imagesDir).toAbsolutePath().normalize();
    }

    public StoredImage store(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw invalidImage("Image file is empty.");
        }

        byte[] bytes = file.getBytes();
        ImageType imageType = detectImageType(bytes);
        if (imageType == null) {
            throw invalidImage("Only JPEG images are supported. Convert WebP, AVIF, and PNG files to JPEG before upload.");
        }

        Files.createDirectories(imagesDir);

        String name = Instant.now().toEpochMilli() + "-" + UUID.randomUUID() + imageType.extension();
        Path target = imagesDir.resolve(name).normalize();
        if (!target.startsWith(imagesDir)) {
            throw new IOException("Refusing to store image outside the configured image directory");
        }

        Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        return new StoredImage(name, target);
    }

    public Path resolve(String imageReference) {
        String filename = filenameOnly(imageReference);
        Path path = imagesDir.resolve(filename).normalize();
        if (!path.startsWith(imagesDir)) {
            throw invalidImage("Invalid image reference.");
        }
        return path;
    }

    public String filenameForClient(String imageReference) {
        try {
            return filenameOnly(imageReference);
        } catch (ResponseStatusException e) {
            return null;
        }
    }

    public void delete(String imageReference) throws IOException {
        Path path = resolve(imageReference);
        try {
            Files.delete(path);
        } catch (NoSuchFileException ignored) {
            // If the file is already gone, still allow the item row to be removed.
        }
    }

    private static ImageType detectImageType(byte[] bytes) {
        if (hasBytes(bytes, 0, 0xff, 0xd8, 0xff)) {
            return ImageType.JPEG;
        }
        return null;
    }

    private static boolean hasBytes(byte[] bytes, int offset, int... expected) {
        if (bytes.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((bytes[offset + i] & 0xff) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static ResponseStatusException invalidImage(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private static String filenameOnly(String imageReference) {
        if (imageReference == null || imageReference.isBlank()) {
            throw invalidImage("Invalid image reference.");
        }

        String normalized = imageReference.trim().replace('\\', '/');
        if (normalized.equals("..") || normalized.startsWith("../") || normalized.contains("/../")) {
            throw invalidImage("Invalid image reference.");
        }

        int lastSlash = normalized.lastIndexOf('/');
        String filename = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
        if (filename.isBlank() || filename.equals(".") || filename.equals("..")) {
            throw invalidImage("Invalid image reference.");
        }

        String lower = filename.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".jpg") && !lower.endsWith(".jpeg")) {
            throw invalidImage("Invalid image reference.");
        }
        return filename;
    }

    public record StoredImage(String filename, Path path) {}

    private enum ImageType {
        JPEG(".jpg");

        private final String extension;

        ImageType(String extension) {
            this.extension = extension;
        }

        String extension() {
            return extension;
        }
    }
}

