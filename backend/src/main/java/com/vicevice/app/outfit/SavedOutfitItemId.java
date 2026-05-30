package com.vicevice.app.outfit;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class SavedOutfitItemId implements Serializable {
    @Column(name = "outfit_id", nullable = false)
    private Integer outfitId;

    @Column(name = "item_id", nullable = false)
    private Integer itemId;

    @Column(name = "role", nullable = false)
    private String role;

    public SavedOutfitItemId() {
    }

    public SavedOutfitItemId(Integer outfitId, Integer itemId, String role) {
        this.outfitId = outfitId;
        this.itemId = itemId;
        this.role = role;
    }

    public Integer getOutfitId() {
        return outfitId;
    }

    public Integer getItemId() {
        return itemId;
    }

    public String getRole() {
        return role;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SavedOutfitItemId that)) return false;
        return Objects.equals(outfitId, that.outfitId)
                && Objects.equals(itemId, that.itemId)
                && Objects.equals(role, that.role);
    }

    @Override
    public int hashCode() {
        return Objects.hash(outfitId, itemId, role);
    }
}
