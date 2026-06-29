package com.planb.planb_backend.domain.bookmark.repository;

import com.planb.planb_backend.domain.bookmark.entity.Bookmark;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BookmarkRepository extends JpaRepository<Bookmark, Long> {

    boolean existsByUserIdAndGooglePlaceId(Long userId, String googlePlaceId);

    List<Bookmark> findByUserIdOrderByCreatedAtDesc(Long userId);
}
