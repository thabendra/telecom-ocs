package com.thabendra.telecom.subscriber;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface SubscriberRepository extends JpaRepository<Subscriber, Long> {

    Optional<Subscriber> findByMsisdn(String msisdn);

    // SELECT ... FOR UPDATE, the same row lock the PL/SQL charging procedures take
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Subscriber s WHERE s.msisdn = :msisdn")
    Optional<Subscriber> findByMsisdnForUpdate(String msisdn);

    boolean existsByMsisdn(String msisdn);
}
