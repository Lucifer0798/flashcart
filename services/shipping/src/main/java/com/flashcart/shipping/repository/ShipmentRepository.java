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

	/**
	 * Fetched with its lines, like every finder above it.
	 *
	 * <p>This one was the exception, and it did not matter while the only caller was an operator
	 * asking about a customer who had none: an empty list never touches the lazy collection. The
	 * moment a customer listed their own shipments it became a 500, because {@code ShipmentResponse}
	 * reads {@code lines} after the session has closed. A latent bug whose only shield was that
	 * nobody could reach the endpoint with data behind it. See ADR 0023.
	 */
	@EntityGraph(attributePaths = "lines")
	List<Shipment> findByCustomerIdOrderByCreatedAtDesc(String customerId);
}
