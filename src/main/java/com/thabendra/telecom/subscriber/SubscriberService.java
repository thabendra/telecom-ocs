package com.thabendra.telecom.subscriber;

import com.thabendra.telecom.push.GatewayCommand.Reason;
import com.thabendra.telecom.push.GatewayCommands;
import com.thabendra.telecom.push.PushProperties;
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
    private final GatewayCommands commands;
    private final PushProperties push;

    public SubscriberService(SubscriberRepository subscribers, PlanRepository plans, JdbcTemplate jdbc,
                             GatewayCommands commands, PushProperties push) {
        this.subscribers = subscribers;
        this.plans = plans;
        this.jdbc = jdbc;
        this.commands = commands;
        this.push = push;
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
        // PU-2: blocked for running out? Now charging can use the balance, at the pay as you go speed
        commands.unblockIfOutOfQuota(msisdn, Reason.TOP_UP, push.payAsYouGoKbps());
        return subscriber;
    }

    /**
     * Suspends the subscriber (operator decision or fraud): charging refuses them from now on, and every
     * gateway is told to block their traffic (PU-1). A top-up doesn't lift this, only {@link #unsuspend}.
     */
    @Transactional
    public Subscriber suspend(String msisdn, Reason reason) {
        if (reason != Reason.SUSPENDED && reason != Reason.FRAUD) {
            throw new BusinessRuleException("A suspension reason is SUSPENDED or FRAUD, not " + reason);
        }
        Subscriber subscriber = subscribers.findByMsisdnForUpdate(msisdn)
                .orElseThrow(() -> new NotFoundException("Subscriber " + msisdn + " not found"));
        if (subscriber.getStatus() == Subscriber.Status.SUSPENDED) {
            throw new BusinessRuleException("Subscriber " + msisdn + " is already suspended");
        }
        subscriber.suspend();
        commands.block(msisdn, reason);
        return subscriber;
    }

    /** Lifts the suspension and tells every gateway to let the user through at the plan's speed (PU-2). */
    @Transactional
    public Subscriber unsuspend(String msisdn) {
        Subscriber subscriber = subscribers.findByMsisdnForUpdate(msisdn)
                .orElseThrow(() -> new NotFoundException("Subscriber " + msisdn + " not found"));
        if (subscriber.getStatus() != Subscriber.Status.SUSPENDED) {
            throw new BusinessRuleException("Subscriber " + msisdn + " is not suspended");
        }
        subscriber.activate();
        commands.unblock(msisdn, Reason.UNSUSPENDED, subscriber.getPlan().getMaxBandwidthKbps());
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
