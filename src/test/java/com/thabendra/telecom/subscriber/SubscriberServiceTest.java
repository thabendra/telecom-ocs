package com.thabendra.telecom.subscriber;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubscriberServiceTest {

    private final Plan basic = new Plan("BASIC", "Basic 10GB", new BigDecimal("499.00"), 10, 100, 10240);

    private SubscriberRepository subscribers;
    private PlanRepository plans;
    private JdbcTemplate jdbc;
    private SubscriberService service;

    @BeforeEach
    void setUp() {
        subscribers = mock(SubscriberRepository.class);
        plans = mock(PlanRepository.class);
        jdbc = mock(JdbcTemplate.class);
        service = new SubscriberService(subscribers, plans, jdbc);
    }

    @Test
    void createSubscriberRejectsDuplicateMsisdn() {
        when(subscribers.existsByMsisdn("94771234567")).thenReturn(true);

        assertThatThrownBy(() -> service.createSubscriber("94771234567", "Kamal Perera", "200012345678", "BASIC", null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already exists");
        verify(subscribers, never()).save(any());
    }

    @Test
    void createSubscriberRejectsUnknownPlan() {
        when(plans.findByCode("GOLD")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createSubscriber("94771234567", "Kamal Perera", "200012345678", "GOLD", null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void createSubscriberDefaultsBalanceToZero() {
        when(plans.findByCode("BASIC")).thenReturn(Optional.of(basic));
        when(subscribers.save(any())).thenAnswer(call -> call.getArgument(0));

        Subscriber created = service.createSubscriber("94771234567", "Kamal Perera", "200012345678", "BASIC", null);

        assertThat(created.getBalance()).isEqualByComparingTo("0");
        assertThat(created.getPlan().getCode()).isEqualTo("BASIC");
    }

    @Test
    void topUpAddsToBalance() {
        Subscriber subscriber = new Subscriber("94771234567", "Kamal Perera", "200012345678", new BigDecimal("100.00"), basic);
        when(subscribers.findByMsisdnForUpdate("94771234567")).thenReturn(Optional.of(subscriber));

        service.topUp("94771234567", new BigDecimal("250.50"));

        assertThat(subscriber.getBalance()).isEqualByComparingTo("350.50");
    }

    @Test
    void topUpRejectsNonPositiveAmount() {
        assertThatThrownBy(() -> service.topUp("94771234567", BigDecimal.ZERO))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void changePlanMapsOracleBusinessErrors() {
        SQLException insufficient = new SQLException(
                "ORA-20004: Insufficient balance: 100, plan fee is 1199\nORA-06512: at \"TELECOM.CHANGE_PLAN\"", "72000", 20004);
        when(jdbc.update(anyString(), eq("94771234567"), eq("SMART")))
                .thenThrow(new UncategorizedSQLException("change_plan", "CALL change_plan(?, ?)", insufficient));

        assertThatThrownBy(() -> service.changePlan("94771234567", "SMART"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Insufficient balance: 100, plan fee is 1199");
    }

    @Test
    void changePlanMapsOracleNotFound() {
        SQLException missing = new SQLException("ORA-20001: Subscriber 94770000000 not found", "72000", 20001);
        when(jdbc.update(anyString(), eq("94770000000"), eq("SMART")))
                .thenThrow(new UncategorizedSQLException("change_plan", "CALL change_plan(?, ?)", missing));

        assertThatThrownBy(() -> service.changePlan("94770000000", "SMART"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Subscriber 94770000000 not found");
    }
}
