package com.vicevice.app.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageStorageServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void storesJpegWithCanonicalExtension() throws Exception {
        ImageStorageService service = new ImageStorageService(tempDir.toString());
        MockMultipartFile file = new MockMultipartFile(
                "images",
                "not-really-a-png.png",
                "image/png",
                new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00}
        );

        ImageStorageService.StoredImage stored = service.store(file);

        assertThat(stored.filename()).endsWith(".jpg");
        assertThat(stored.filename()).doesNotContain("\\", "/");
        assertThat(stored.path()).startsWith(tempDir.toAbsolutePath().normalize());
        assertThat(Files.readAllBytes(stored.path())).isEqualTo(file.getBytes());
    }

    @Test
    void rejectsPngWebpAndAvifBecauseUploadsMustBeJpeg() {
        ImageStorageService service = new ImageStorageService(tempDir.toString());
        MockMultipartFile png = new MockMultipartFile(
                "images",
                "piece",
                "application/octet-stream",
                new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}
        );
        MockMultipartFile webp = new MockMultipartFile(
                "images",
                "piece.txt",
                "text/plain",
                new byte[] {0x52, 0x49, 0x46, 0x46, 0x04, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50}
        );
        MockMultipartFile avif = new MockMultipartFile(
                "images",
                "piece.avif",
                "image/avif",
                new byte[] {0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70, 0x61, 0x76, 0x69, 0x66}
        );

        assertThatThrownBy(() -> service.store(png))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
        assertThatThrownBy(() -> service.store(webp))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
        assertThatThrownBy(() -> service.store(avif))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
    }

    @Test
    void rejectsEmptyAndUnsupportedFiles() {
        ImageStorageService service = new ImageStorageService(tempDir.toString());
        MockMultipartFile empty = new MockMultipartFile("images", "empty.jpg", "image/jpeg", new byte[0]);
        MockMultipartFile textNamedJpg = new MockMultipartFile(
                "images",
                "fake.jpg",
                "image/jpeg",
                "not an image".getBytes()
        );

        assertThatThrownBy(() -> service.store(empty))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
        assertThatThrownBy(() -> service.store(textNamedJpg))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
    }

    @Test
    void resolvesReferencesInsideImagesDirectoryOnly() throws Exception {
        ImageStorageService service = new ImageStorageService(tempDir.toString());
        Path existingImage = tempDir.resolve("piece.jpg");
        Files.write(existingImage, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff});

        assertThat(service.resolve("piece.jpg")).isEqualTo(existingImage.toAbsolutePath().normalize());
        assertThat(service.resolve("C:\\outside\\folder\\piece.jpg")).isEqualTo(existingImage.toAbsolutePath().normalize());
        assertThat(service.filenameForClient("C:\\outside\\folder\\piece.jpg")).isEqualTo("piece.jpg");
        assertThatThrownBy(() -> service.resolve("../piece.jpg"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
        assertThatThrownBy(() -> service.resolve("not-an-image.txt"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
        assertThatThrownBy(() -> service.resolve("legacy.webp"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
        assertThatThrownBy(() -> service.resolve("legacy.avif"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
    }
}
