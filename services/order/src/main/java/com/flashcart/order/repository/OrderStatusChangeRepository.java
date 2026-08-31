package com.flashcart.order.repository;

import java.util.List;
import java.util.UUID;

import com.flashcart.order.domain.OrderStatusChange;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderStatusChangeRepository extends JpaRepository<OrderStatusChange, UUID> {

	List<OrderStatusChange> findByOrderIdOrderByCreatedAtAsc(UUID orderId);
}
