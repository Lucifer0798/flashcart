package com.flashcart.inventory.repository;

import java.util.List;
import java.util.UUID;

import com.flashcart.inventory.domain.StockMovement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {

	Page<StockMovement> findBySkuOrderByCreatedAtDesc(String sku, Pageable pageable);

	List<StockMovement> findByReservationIdOrderByCreatedAtAsc(UUID reservationId);
}
