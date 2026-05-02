package com.vicevice.app.outfit;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OutfitControllerTest {
    @Test
    void validatesItemIdsAndNormalizesRolesToClosetCategories() {
        Map<Integer, String> categoryByItemId = new LinkedHashMap<>();
        categoryByItemId.put(1, "shirt");
        categoryByItemId.put(2, "sneakers");
        categoryByItemId.put(3, "watch");
        categoryByItemId.put(4, "bag");

        OutfitController.OutfitResponse raw = new OutfitController.OutfitResponse(List.of(
                new OutfitController.OutfitPlan(
                        " Flexible model outfit ",
                        List.of(
                                new OutfitController.OutfitItemPlan(999, "fake piece"),
                                new OutfitController.OutfitItemPlan(1, "base layer"),
                                new OutfitController.OutfitItemPlan(2, "chunky sneakers"),
                                new OutfitController.OutfitItemPlan(3, "wrist detail"),
                                new OutfitController.OutfitItemPlan(4, "statement bag")
                        ),
                        " Uses a few real pieces. "
                )
        ));

        OutfitController.OutfitResponse validated = OutfitController.validateOutfits(raw, categoryByItemId, 3);

        assertThat(validated.outfits()).hasSize(1);
        assertThat(validated.outfits().get(0).name()).isEqualTo("Flexible model outfit");
        assertThat(validated.outfits().get(0).items()).containsExactly(
                new OutfitController.OutfitItemPlan(1, "shirt"),
                new OutfitController.OutfitItemPlan(2, "sneakers"),
                new OutfitController.OutfitItemPlan(3, "watch"),
                new OutfitController.OutfitItemPlan(4, "bag")
        );
    }

    @Test
    void fallsBackToItemCategoryWhenRoleIsBlank() {
        Map<Integer, String> categoryByItemId = new LinkedHashMap<>();
        categoryByItemId.put(1, "linen overshirt");

        OutfitController.OutfitResponse raw = new OutfitController.OutfitResponse(List.of(
                new OutfitController.OutfitPlan(
                        "",
                        List.of(new OutfitController.OutfitItemPlan(1, "   ")),
                        ""
                )
        ));

        OutfitController.OutfitResponse validated = OutfitController.validateOutfits(raw, categoryByItemId, 1);

        assertThat(validated.outfits()).hasSize(1);
        assertThat(validated.outfits().get(0).name()).isEqualTo("Outfit 1");
        assertThat(validated.outfits().get(0).items()).containsExactly(
                new OutfitController.OutfitItemPlan(1, "linen overshirt")
        );
    }

    @Test
    void fillsMissingOutfitsWithValidClosetPieces() {
        Map<Integer, String> categoryByItemId = new LinkedHashMap<>();
        categoryByItemId.put(1, "top");
        categoryByItemId.put(2, "bottom");
        categoryByItemId.put(3, "shoes");

        OutfitController.OutfitResponse oneOutfit = new OutfitController.OutfitResponse(List.of(
                new OutfitController.OutfitPlan(
                        "Existing outfit",
                        List.of(new OutfitController.OutfitItemPlan(1, "base layer")),
                        "Already valid."
                )
        ));

        OutfitController.OutfitResponse filled = OutfitController.fillToCount(oneOutfit, categoryByItemId, 3);

        assertThat(filled.outfits()).hasSize(3);
        assertThat(filled.outfits())
                .flatExtracting(OutfitController.OutfitPlan::items)
                .allSatisfy(piece -> {
                    assertThat(categoryByItemId).containsKey(piece.itemId());
                    assertThat(piece.role()).isNotBlank();
                });
    }
}
