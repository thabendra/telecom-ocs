package com.thabendra.telecom.soap;

import com.thabendra.telecom.soap.contract.ActivatePackageRequest;
import com.thabendra.telecom.soap.contract.ActivatePackageResponse;
import com.thabendra.telecom.soap.contract.App;
import com.thabendra.telecom.soap.contract.ChargeSource;
import com.thabendra.telecom.soap.contract.GetQuotaRequest;
import com.thabendra.telecom.soap.contract.GetQuotaResponse;
import com.thabendra.telecom.soap.contract.ListPackagesRequest;
import com.thabendra.telecom.soap.contract.ListPackagesResponse;
import com.thabendra.telecom.soap.contract.RecordUsageRequest;
import com.thabendra.telecom.soap.contract.RecordUsageResponse;
import com.thabendra.telecom.usage.DataPackage;
import com.thabendra.telecom.usage.UsageService;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;

import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.GregorianCalendar;

import static com.thabendra.telecom.soap.WebServiceConfig.NAMESPACE;

/** SOAP adapter for packages, quota and usage charging. */
@Endpoint
public class UsageEndpoint {

    private static final DatatypeFactory DATATYPES = DatatypeFactory.newDefaultInstance();

    private final UsageService service;

    public UsageEndpoint(UsageService service) {
        this.service = service;
    }

    @PayloadRoot(namespace = NAMESPACE, localPart = "ListPackagesRequest")
    @ResponsePayload
    public ListPackagesResponse listPackages(@RequestPayload ListPackagesRequest request) {
        ListPackagesResponse response = new ListPackagesResponse();
        service.listPackages().forEach(p -> response.getPackage().add(toContract(p)));
        return response;
    }

    @PayloadRoot(namespace = NAMESPACE, localPart = "ActivatePackageRequest")
    @ResponsePayload
    public ActivatePackageResponse activatePackage(@RequestPayload ActivatePackageRequest request) {
        UsageService.ActivatedPackage activated = service.activatePackage(request.getMsisdn(), request.getPackageCode());
        ActivatePackageResponse response = new ActivatePackageResponse();
        response.setMsisdn(activated.msisdn());
        response.setPackageCode(activated.packageCode());
        response.setExpiresAt(toXml(activated.expiresAt()));
        response.setBalance(activated.balance());
        return response;
    }

    @PayloadRoot(namespace = NAMESPACE, localPart = "RecordUsageRequest")
    @ResponsePayload
    public RecordUsageResponse recordUsage(@RequestPayload RecordUsageRequest request) {
        UsageService.UsageResult result = service.recordUsage(request.getMsisdn(),
                UsageService.App.valueOf(request.getApp().value()), request.getDataMb());
        RecordUsageResponse response = new RecordUsageResponse();
        response.setMsisdn(result.msisdn());
        response.setApp(App.fromValue(result.app().name()));
        response.setDataMb(result.dataMb());
        response.setBandwidthKbps(result.bandwidthKbps());
        response.setMoneyCharged(result.moneyCharged());
        response.setBalance(result.balance());
        for (UsageService.UsagePortion portion : result.portions()) {
            var dto = new com.thabendra.telecom.soap.contract.UsagePortion();
            dto.setSource(ChargeSource.fromValue(portion.source()));
            dto.setPackageCode(portion.packageCode());
            dto.setDataMb(portion.dataMb());
            dto.setMoneyCharged(portion.moneyCharged());
            response.getPortion().add(dto);
        }
        return response;
    }

    @PayloadRoot(namespace = NAMESPACE, localPart = "GetQuotaRequest")
    @ResponsePayload
    public GetQuotaResponse getQuota(@RequestPayload GetQuotaRequest request) {
        UsageService.Quota quota = service.getQuota(request.getMsisdn());
        GetQuotaResponse response = new GetQuotaResponse();
        response.setMsisdn(quota.msisdn());
        response.setBalance(quota.balance());
        for (UsageService.Bucket bucket : quota.buckets()) {
            var dto = new com.thabendra.telecom.soap.contract.Bucket();
            dto.setSource(ChargeSource.fromValue(bucket.source()));
            dto.setCode(bucket.code());
            dto.setName(bucket.name());
            dto.setApp(bucket.app() == null ? null : App.fromValue(bucket.app()));
            dto.setDataMbLeft(bucket.dataMbLeft());
            dto.setDataMbUsed(bucket.dataMbUsed());
            dto.setBandwidthKbps(bucket.bandwidthKbps());
            dto.setExpiresAt(toXml(bucket.expiresAt()));
            response.getBucket().add(dto);
        }
        return response;
    }

    private static com.thabendra.telecom.soap.contract.DataPackage toContract(DataPackage p) {
        var dto = new com.thabendra.telecom.soap.contract.DataPackage();
        dto.setCode(p.getCode());
        dto.setName(p.getName());
        dto.setApp(p.getApp() == null ? null : App.fromValue(p.getApp()));
        dto.setPrice(p.getPrice());
        dto.setDataMb(p.getDataMb());
        dto.setValidityDays(p.getValidityDays());
        dto.setMaxBandwidthKbps(p.getMaxBandwidthKbps());
        dto.setFairUsageMb(p.getFairUsageMb());
        dto.setThrottledBandwidthKbps(p.getThrottledBandwidthKbps());
        return dto;
    }

    private static XMLGregorianCalendar toXml(LocalDateTime time) {
        return time == null ? null
                : DATATYPES.newXMLGregorianCalendar(GregorianCalendar.from(time.atZone(ZoneId.systemDefault())));
    }
}
