package com.thabendra.telecom.subscriber;

import org.springframework.ws.soap.server.endpoint.annotation.FaultCode;
import org.springframework.ws.soap.server.endpoint.annotation.SoapFault;

/** Subscriber or plan doesn't exist. Returned as a SOAP Client fault / HTTP 404. */
@SoapFault(faultCode = FaultCode.CLIENT)
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
