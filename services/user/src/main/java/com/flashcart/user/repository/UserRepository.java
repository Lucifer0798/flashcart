package com.flashcart.user.repository;

import java.util.Optional;
import java.util.UUID;

import com.flashcart.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

	/** Callers pass an already-lowercased address; the column stores them that way. */
	Optional<User> findByEmail(String email);

	boolean existsByEmail(String email);

	/**
	 * Fetches the addresses with the user.
	 *
	 * <p>The collection is lazy and {@code open-in-view} is off, so mapping it in the controller --
	 * outside the transaction that loaded the user -- throws LazyInitializationException and the
	 * caller sees a 500. Either the mapping moves inside the transaction or the data comes with the
	 * user; this is the second, because a response DTO built in the service layer is a service layer
	 * that knows about HTTP.
	 */
	@Query("select u from User u left join fetch u.addresses where u.id = :id")
	Optional<User> findWithAddresses(@Param("id") UUID id);
}
