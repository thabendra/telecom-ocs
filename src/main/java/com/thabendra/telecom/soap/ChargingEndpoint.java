package com.thabendra.telecom.soap;

import com.thabendra.telecom.charging.ChargingApi;
import com.thabendra.telecom.charging.ChargingService;
import com.thabendra.telecom.soap.contract.ChargeSource;
import com.thabendra.telecom.soap.contract.DenyAction;
import com.thabendra.telecom.soap.contract.Grant;
import com.thabendra.telecom.soap.contract.GrantResult;
import com.thabendra.telecom.soap.contract.ReleaseSessionRequest;
import com.thabendra.telecom.soap.contract.ReleaseSessionResponse;
import com.thabendra.telecom.soap.contract.StartSessionRequest;
import com.thabendra.telecom.soap.contract.StartSessionResponse;
import com.thabendra.telecom.soap.contract.UpdateSessionRequest;
import com.thabendra.telecom.soap.contract.UpdateSessionResponse;
import com.thabendra.telecom.usage.UsageService.App;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.Namespace;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;
import org.springframework.ws.server.endpoint.annotation.XPathParam;

import static com.thabendra.telecom.soap.WebServiceConfig.NAMESPACE;

/**
 * SOAP adapter of the online charging API (CH-1..CH-3): same operations as the REST
 * {@link com.thabendra.telecom.charging.ChargingController}, same {@link ChargingService} bean behind it.
 * The session id is inside the XML, so it is pulled out with an XPath to match the
 * {@code (sessionId, request)} shape of {@link ChargingApi}.
 */
@Endpoint
@Namespace(prefix = "s", uri = NAMESPACE)
public class ChargingEndpoint implements ChargingApi<StartSessionRequest, StartSessionResponse,
        UpdateSessionRequest, UpdateSessionResponse, ReleaseSessionRequest, ReleaseSessionResponse> {

    private final ChargingService service;

    public ChargingEndpoint(ChargingService service) {
        this.service = service;
    }

    @Override
    @PayloadRoot(namespace = NAMESPACE, localPart = "StartSessionRequest")
    @ResponsePayload
    public StartSessionResponse startSession(@RequestPayload StartSessionRequest request) {
        ChargingService.Grant grant = service.startSession(request.getMsisdn(),
                App.valueOf(request.getApp().value()), mb(request.getRequestedMb()));
        StartSessionResponse response = new StartSessionResponse();
        response.setGrant(toContract(grant));
        return response;
    }

    @Override
    @PayloadRoot(namespace = NAMESPACE, localPart = "UpdateSessionRequest")
    @ResponsePayload
    public UpdateSessionResponse updateSession(@XPathParam("/s:UpdateSessionRequest/s:sessionId") String sessionId,
                                               @RequestPayload UpdateSessionRequest request) {
        ChargingService.Grant grant = service.updateSession(sessionId, request.getRequestNumber(),
                request.getUsedMb(), mb(request.getRequestedMb()));
        UpdateSessionResponse response = new UpdateSessionResponse();
        response.setGrant(toContract(grant));
        return response;
    }

    @Override
    @PayloadRoot(namespace = NAMESPACE, localPart = "ReleaseSessionRequest")
    @ResponsePayload
    public ReleaseSessionResponse releaseSession(@XPathParam("/s:ReleaseSessionRequest/s:sessionId") String sessionId,
                                                 @RequestPayload ReleaseSessionRequest request) {
        ChargingService.Release release = service.releaseSession(sessionId, request.getRequestNumber(),
                request.getUsedMb());
        ReleaseSessionResponse response = new ReleaseSessionResponse();
        response.setSessionId(release.sessionId());
        response.setTotalUsedMb(release.totalUsedMb());
        return response;
    }

    private static Grant toContract(ChargingService.Grant grant) {
        Grant dto = new Grant();
        dto.setSessionId(grant.sessionId());
        dto.setResult(GrantResult.fromValue(grant.result().name()));
        dto.setGrantedMb(grant.grantedMb());
        dto.setBandwidthKbps(grant.bandwidthKbps());
        dto.setSource(grant.source() == null ? null : ChargeSource.fromValue(grant.source()));
        dto.setPackageCode(grant.packageCode());
        dto.setValiditySeconds(grant.validitySeconds());
        dto.setAction(grant.action() == null ? null : DenyAction.fromValue(grant.action().name()));
        return dto;
    }

    // requestedMb is optional (CH-5): left out = the backend picks the grant size
    private static Long mb(Integer requestedMb) {
        return requestedMb == null ? null : requestedMb.longValue();
    }
}
