package com.flashcart.order.messaging;

import com.flashcart.common.event.ConsumerFactories;
import com.flashcart.common.event.message.InventoryReservationFailed;
import com.flashcart.common.event.message.InventoryReserved;
import com.flashcart.common.event.message.PaymentCompleted;
import com.flashcart.common.event.message.PaymentFailed;
import com.flashcart.common.event.message.PaymentTimedOut;
import com.flashcart.common.event.message.ReservationExpired;
import com.flashcart.common.event.message.ShipmentCreated;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/** One typed listener factory per event this service reacts to. */
@Configuration
public class OrderKafkaConfig {

	static final String GROUP = "flashcart-order";

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, InventoryReserved> inventoryReservedFactory(
			KafkaProperties properties, KafkaConnectionDetails connectionDetails,
			KafkaTemplate<String, Object> template) {
		return ConsumerFactories.listenerFactory(properties, connectionDetails, GROUP, InventoryReserved.class, template);
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, InventoryReservationFailed>
			inventoryReservationFailedFactory(KafkaProperties properties,
			KafkaConnectionDetails connectionDetails, KafkaTemplate<String, Object> template) {
		return ConsumerFactories.listenerFactory(properties, connectionDetails, GROUP, InventoryReservationFailed.class,
				template);
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, ReservationExpired> reservationExpiredFactory(
			KafkaProperties properties, KafkaConnectionDetails connectionDetails,
			KafkaTemplate<String, Object> template) {
		return ConsumerFactories.listenerFactory(properties, connectionDetails, GROUP, ReservationExpired.class, template);
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, PaymentCompleted> paymentCompletedFactory(
			KafkaProperties properties, KafkaConnectionDetails connectionDetails,
			KafkaTemplate<String, Object> template) {
		return ConsumerFactories.listenerFactory(properties, connectionDetails, GROUP, PaymentCompleted.class, template);
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, PaymentFailed> paymentFailedFactory(
			KafkaProperties properties, KafkaConnectionDetails connectionDetails,
			KafkaTemplate<String, Object> template) {
		return ConsumerFactories.listenerFactory(properties, connectionDetails, GROUP, PaymentFailed.class, template);
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, PaymentTimedOut> paymentTimedOutFactory(
			KafkaProperties properties, KafkaConnectionDetails connectionDetails,
			KafkaTemplate<String, Object> template) {
		return ConsumerFactories.listenerFactory(properties, connectionDetails, GROUP, PaymentTimedOut.class, template);
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, ShipmentCreated> shipmentCreatedFactory(
			KafkaProperties properties, KafkaConnectionDetails connectionDetails,
			KafkaTemplate<String, Object> template) {
		return ConsumerFactories.listenerFactory(properties, connectionDetails, GROUP, ShipmentCreated.class, template);
	}
}
