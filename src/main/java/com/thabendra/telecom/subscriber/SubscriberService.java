package com.thabendra.telecom.subscriber;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/** Business logic shared by the SOAP endpoint and the REST controller. */
@Service
public class SubscriberService {

    private final SubscriberRepository subscribers;
    private final PlanRepository plans;
    private final JdbcTemplate jdbc;

    public SubscriberService(SubscriberRepository subscribers, PlanRepository plans, JdbcTemplate jdbc) {
        this.subscribers = subscribers;
        this.plans = plans;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Plan> listPlans() {
        return plans.findAll();
    }

    @Transactional(readOnly = true)
    public Subscriber getSubscriber(String msisdn) {
        return subscribers.findByMsisdn(msisdn)
                .orElseThrow(() -> new NotFoundException("Subscriber " + msisdn + " not found"));
    }

    @Transactional
    public Subscriber createSubscriber(String msisdn, String fullName, String nic, String planCode,
                                       BigDecimal initialBalance) {
        if (subscribers.existsByMsisdn(msisdn)) {
            throw new BusinessRuleException("Subscriber " + msisdn + " already exists");
        }
        Plan plan = findPlan(planCode);
        BigDecimal balance = initialBalance == null ? BigDecimal.ZERO : initialBalance;
        return subscribers.save(new Subscriber(msisdn, fullName, nic, balance, plan));
    }

    @Transactional
    public Subscriber topUp(String msisdn, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessRuleException("Top up amount must be positive");
        }
        // lock first: otherwise a charge made between our read and write would be overwritten
        Subscriber subscriber = subscribers.findByMsisdnForUpdate(msisdn)
                .orElseThrow(() -> new NotFoundException("Subscriber " + msisdn + " not found"));
        subscriber.topUp(amount);
        return subscriber;
    }

    /**
     * Runs the CHANGE_PLAN PL/SQL procedure, which locks the subscriber row, checks the rules,
     * charges the new plan's fee and writes the PLAN_CHANGE audit row in one transaction.
     */
    @Transactional
    public Subscriber changePlan(String msisdn, String newPlanCode) {
        try {
            jdbc.update("CALL change_plan(?, ?)", msisdn, newPlanCode);
        } catch (DataAccessException e) {
            throw OracleErrors.translate(e);
        }
        return getSubscriber(msisdn);
    }

    private Plan findPlan(String code) {
        return plans.findByCode(code)
                .orElseThrow(() -> new NotFoundException("Plan " + code + " not found"));
    }
}
