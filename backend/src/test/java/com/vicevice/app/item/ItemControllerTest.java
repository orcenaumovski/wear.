package com.vicevice.app.item;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vicevice.app.auth.AuthService;
import com.vicevice.app.outfit.SavedOutfitItemRepository;
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
        when(itemRepository.findByIdAndUserId(99, 1)).thenReturn(Optional.empty());
        ItemController controller = controller(itemRepository);

        assertNotFound(() -> controller.get(99, "Bearer test"));
        assertNotFound(() -> controller.image(99, "Bearer test", null));
        assertNotFound(() -> controller.analyze(99, "Bearer test", null));
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
        item.setUserId(1);
        when(itemRepository.findByIdAndUserId(1, 1)).thenReturn(Optional.of(item));

        ResponseEntity<Resource> response = controller(itemRepository).image(1, "Bearer test", null);

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
        item.setUserId(1);
        when(itemRepository.findByIdAndUserId(7, 1)).thenReturn(Optional.of(item));

        SavedOutfitItemRepository savedOutfitItemRepository = mock(SavedOutfitItemRepository.class);
        ResponseEntity<Void> response = controller(itemRepository, savedOutfitItemRepository).delete(7, "Bearer test");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(Files.exists(imagePath)).isFalse();
        verify(savedOutfitItemRepository).deleteByIdItemId(7);
        verify(itemRepository).delete(item);
    }

    private ItemController controller(ItemRepository itemRepository) {
        return controller(itemRepository, mock(SavedOutfitItemRepository.class));
    }

    private ItemController controller(ItemRepository itemRepository, SavedOutfitItemRepository savedOutfitItemRepository) {
        AuthService authService = mock(AuthService.class);
        when(authService.requireUser("Bearer test")).thenReturn(new AuthService.AuthenticatedUser(1, "test"));
        when(authService.requireUser("Bearer test", null)).thenReturn(new AuthService.AuthenticatedUser(1, "test"));
        return new ItemController(
                itemRepository,
                new ImageStorageService(tempDir.toString()),
                mock(ItemAnalysisService.class),
                new ObjectMapper(),
                authService,
                savedOutfitItemRepository
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
