package com.flashcart.shipping.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.flashcart.shipping.domain.Shipment;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipmentRepository extends JpaRepository<Shipment, UUID> {

	@EntityGraph(attributePaths = "lines")
	Optional<Shipment> findByOrderId(UUID orderId);

	@EntityGraph(attributePaths = "lines")
	Optional<Shipment> findByOrderNumber(String orderNumber);

	@EntityGraph(attributePaths = "lines")
	Optional<Shipment> findByTrackingNumber(String trackingNumber);

	List<Shipment> findByCustomerIdOrderByCreatedAtDesc(String customerId);
}
