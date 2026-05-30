package com.vicevice.app.outfit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SavedOutfitItemRepository extends JpaRepository<SavedOutfitItem, SavedOutfitItemId> {
    List<SavedOutfitItem> findByIdOutfitId(Integer outfitId);

    List<SavedOutfitItem> findByIdOutfitIdIn(Collection<Integer> outfitIds);

    void deleteByIdOutfitId(Integer outfitId);

    void deleteByIdItemId(Integer itemId);
}
