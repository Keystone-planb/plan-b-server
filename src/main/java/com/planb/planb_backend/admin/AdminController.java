package com.planb.planb_backend.admin;

import com.planb.planb_backend.domain.place.entity.Place;
import com.planb.planb_backend.domain.place.repository.PlaceRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "어드민", description = "내부 관리자 전용 API — JWT 인증 필요")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final PlaceRepository placeRepository;

    @Operation(
        summary = "전체 장소 목록 조회",
        description = "DB에 저장된 모든 장소를 마지막 분석 시각 기준 내림차순으로 반환합니다. JWT 인증 필요."
    )
    @GetMapping("/places")
    public ResponseEntity<List<AdminPlaceDto>> getAllPlaces() {
        List<Place> places = placeRepository.findAll(
                Sort.by(Sort.Direction.DESC, "lastSyncedAt"));

        List<AdminPlaceDto> result = places.stream()
                .map(AdminPlaceDto::from)
                .toList();

        return ResponseEntity.ok(result);
    }

    // ── 응답 DTO (inner record) ─────────────────────────────────────────────
    public record AdminPlaceDto(
            Long id,
            String googlePlaceId,
            String name,
            String address,
            String type,
            String space,
            String mood,
            Double rating,
            Integer userRatingsTotal,
            String lastSyncedAt,
            String analysisStatus   // "COMPLETE" | "PENDING"
    ) {
        static AdminPlaceDto from(Place p) {
            boolean analyzed = p.getType() != null
                             && p.getSpace() != null
                             && p.getMood() != null;
            return new AdminPlaceDto(
                    p.getId(),
                    p.getGooglePlaceId(),
                    p.getName(),
                    p.getAddress(),
                    p.getType()  != null ? p.getType().name()  : null,
                    p.getSpace() != null ? p.getSpace().name() : null,
                    p.getMood()  != null ? p.getMood().name()  : null,
                    p.getRating(),
                    p.getUserRatingsTotal(),
                    p.getLastSyncedAt() != null ? p.getLastSyncedAt().toString() : null,
                    analyzed ? "COMPLETE" : "PENDING"
            );
        }
    }
}
