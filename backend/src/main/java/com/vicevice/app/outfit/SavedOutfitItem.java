package com.vicevice.app.outfit;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "outfit_item")
public class SavedOutfitItem {
    @EmbeddedId
    private SavedOutfitItemId id;

    public SavedOutfitItem() {
    }

    public SavedOutfitItem(Integer outfitId, Integer itemId, String role) {
        this.id = new SavedOutfitItemId(outfitId, itemId, role);
    }

    public SavedOutfitItemId getId() {
        return id;
    }

    public void setId(SavedOutfitItemId id) {
        this.id = id;
    }
}
