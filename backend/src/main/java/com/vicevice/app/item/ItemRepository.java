package com.vicevice.app.item;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ItemRepository extends JpaRepository<Item, Integer> {
    List<Item> findByUserIdOrderByCreatedAtEpochMsDesc(Integer userId);

    List<Item> findByUserIdIsNull();

    Optional<Item> findByIdAndUserId(Integer id, Integer userId);

    List<Item> findByUserIdAndIdIn(Integer userId, List<Integer> ids);
}

