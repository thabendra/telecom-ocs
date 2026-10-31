package com.thabendra.telecom.subscriber;

import org.springframework.ws.soap.server.endpoint.annotation.FaultCode;
import org.springframework.ws.soap.server.endpoint.annotation.SoapFault;

/** Request is valid but breaks a rule (duplicate number, low balance, same plan). SOAP Client fault / HTTP 409. */
@SoapFault(faultCode = FaultCode.CLIENT)
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
