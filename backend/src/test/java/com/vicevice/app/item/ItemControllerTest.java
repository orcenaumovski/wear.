package com.vicevice.app.item;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vicevice.app.storage.ImageStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemControllerTest {
    @TempDir
    Path tempDir;

    @Test
    void missingItemRoutesReturn404() throws Exception {
        ItemRepository itemRepository = mock(ItemRepository.class);
        when(itemRepository.findById(99)).thenReturn(Optional.empty());
        ItemController controller = controller(itemRepository);

        assertNotFound(() -> controller.get(99));
        assertNotFound(() -> controller.image(99));
        assertNotFound(() -> controller.analyze(99, null));
    }

    @Test
    void servesImageWithNoSniffHeader() throws Exception {
        ItemRepository itemRepository = mock(ItemRepository.class);
        Files.write(tempDir.resolve("piece.jpg"), new byte[] {
                (byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00
        });

        Item item = new Item();
        ReflectionTestUtils.setField(item, "id", 1);
        item.setImagePath("piece.jpg");
        item.setCreatedAtEpochMs(123L);
        when(itemRepository.findById(1)).thenReturn(Optional.of(item));

        ResponseEntity<Resource> response = controller(itemRepository).image(1);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().exists()).isTrue();
    }

    @Test
    void deleteRemovesImageAndRow() throws Exception {
        ItemRepository itemRepository = mock(ItemRepository.class);
        Path imagePath = tempDir.resolve("piece.jpg");
        Files.write(imagePath, new byte[] {
                (byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00
        });

        Item item = new Item();
        ReflectionTestUtils.setField(item, "id", 7);
        item.setImagePath("piece.jpg");
        item.setCreatedAtEpochMs(123L);
        when(itemRepository.findById(7)).thenReturn(Optional.of(item));

        ResponseEntity<Void> response = controller(itemRepository).delete(7);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(Files.exists(imagePath)).isFalse();
        verify(itemRepository).delete(item);
    }

    private ItemController controller(ItemRepository itemRepository) {
        return new ItemController(
                itemRepository,
                new ImageStorageService(tempDir.toString()),
                mock(ItemAnalysisService.class),
                new ObjectMapper()
        );
    }

    private static void assertNotFound(ThrowingAction action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getReason()).contains("Item not found");
                });
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
