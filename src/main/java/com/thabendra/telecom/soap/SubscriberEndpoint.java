package com.thabendra.telecom.soap;

import com.thabendra.telecom.soap.contract.ChangePlanRequest;
import com.thabendra.telecom.soap.contract.ChangePlanResponse;
import com.thabendra.telecom.soap.contract.CreateSubscriberRequest;
import com.thabendra.telecom.soap.contract.CreateSubscriberResponse;
import com.thabendra.telecom.soap.contract.GetBalanceRequest;
import com.thabendra.telecom.soap.contract.GetBalanceResponse;
import com.thabendra.telecom.soap.contract.GetSubscriberRequest;
import com.thabendra.telecom.soap.contract.GetSubscriberResponse;
import com.thabendra.telecom.soap.contract.ListPlansRequest;
import com.thabendra.telecom.soap.contract.ListPlansResponse;
import com.thabendra.telecom.soap.contract.TopUpRequest;
import com.thabendra.telecom.soap.contract.TopUpResponse;
import com.thabendra.telecom.subscriber.Plan;
import com.thabendra.telecom.subscriber.Subscriber;
import com.thabendra.telecom.subscriber.SubscriberService;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;

import static com.thabendra.telecom.soap.WebServiceConfig.NAMESPACE;

/** Thin SOAP adapter: maps generated contract types to and from the service. */
@Endpoint
public class SubscriberEndpoint {

    private final SubscriberService service;

    public SubscriberEndpoint(SubscriberService service) {
        this.service = service;
    }

    @PayloadRoot(namespace = NAMESPACE, localPart = "ListPlansRequest")
    @ResponsePayload
    public ListPlansResponse listPlans(@RequestPayload ListPlansRequest request) {
        ListPlansResponse response = new ListPlansResponse();
        service.listPlans().forEach(plan -> response.getPlan().add(toContract(plan)));
        return response;
    }

    @PayloadRoot(namespace = NAMESPACE, localPart = "CreateSubscriberRequest")
    @ResponsePayload
    public CreateSubscriberResponse createSubscriber(@RequestPayload CreateSubscriberRequest request) {
        Subscriber created = service.createSubscriber(request.getMsisdn(), request.getFullName(),
                request.getNic(), request.getPlanCode(), request.getInitialBalance());
        CreateSubscriberResponse response = new CreateSubscriberResponse();
        response.setSubscriber(toContract(created));
        return response;
    }

    @PayloadRoot(namespace = NAMESPACE, localPart = "GetSubscriberRequest")
    @ResponsePayload
    public GetSubscriberResponse getSubscriber(@RequestPayload GetSubscriberRequest request) {
        GetSubscriberResponse response = new GetSubscriberResponse();
        response.setSubscriber(toContract(service.getSubscriber(request.getMsisdn())));
        return response;
    }

    @PayloadRoot(namespace = NAMESPACE, localPart = "GetBalanceRequest")
    @ResponsePayload
    public GetBalanceResponse getBalance(@RequestPayload GetBalanceRequest request) {
        Subscriber subscriber = service.getSubscriber(request.getMsisdn());
        GetBalanceResponse response = new GetBalanceResponse();
        response.setMsisdn(subscriber.getMsisdn());
        response.setBalance(subscriber.getBalance());
        return response;
    }

    @PayloadRoot(namespace = NAMESPACE, localPart = "TopUpRequest")
    @ResponsePayload
    public TopUpResponse topUp(@RequestPayload TopUpRequest request) {
        Subscriber subscriber = service.topUp(request.getMsisdn(), request.getAmount());
        TopUpResponse response = new TopUpResponse();
        response.setMsisdn(subscriber.getMsisdn());
        response.setBalance(subscriber.getBalance());
        return response;
    }

    @PayloadRoot(namespace = NAMESPACE, localPart = "ChangePlanRequest")
    @ResponsePayload
    public ChangePlanResponse changePlan(@RequestPayload ChangePlanRequest request) {
        ChangePlanResponse response = new ChangePlanResponse();
        response.setSubscriber(toContract(service.changePlan(request.getMsisdn(), request.getNewPlanCode())));
        return response;
    }

    private static com.thabendra.telecom.soap.contract.Plan toContract(Plan plan) {
        var dto = new com.thabendra.telecom.soap.contract.Plan();
        dto.setCode(plan.getCode());
        dto.setName(plan.getName());
        dto.setMonthlyFee(plan.getMonthlyFee());
        dto.setDataGb(plan.getDataGb());
        dto.setVoiceMinutes(plan.getVoiceMinutes());
        dto.setMaxBandwidthKbps(plan.getMaxBandwidthKbps());
        return dto;
    }

    private static com.thabendra.telecom.soap.contract.Subscriber toContract(Subscriber subscriber) {
        var dto = new com.thabendra.telecom.soap.contract.Subscriber();
        dto.setMsisdn(subscriber.getMsisdn());
        dto.setFullName(subscriber.getFullName());
        dto.setNic(subscriber.getNic());
        dto.setBalance(subscriber.getBalance());
        dto.setStatus(subscriber.getStatus().name());
        dto.setPlan(toContract(subscriber.getPlan()));
        return dto;
    }
}
