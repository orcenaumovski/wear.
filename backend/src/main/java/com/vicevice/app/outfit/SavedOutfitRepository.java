package com.vicevice.app.outfit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SavedOutfitRepository extends JpaRepository<SavedOutfit, Integer> {
    List<SavedOutfit> findByUserIdOrderByCreatedAtEpochMsDesc(Integer userId);

    List<SavedOutfit> findByUserIdIsNull();

    Optional<SavedOutfit> findByIdAndUserId(Integer id, Integer userId);
}
