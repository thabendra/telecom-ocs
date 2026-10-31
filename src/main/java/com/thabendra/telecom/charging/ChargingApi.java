package com.thabendra.telecom.charging;

/**
 * The online charging operations every API adapter must offer (REST: {@link ChargingController},
 * SOAP: {@code ChargingEndpoint}). Each adapter has its own message types (JSON records, JAXB classes
 * generated from the XSD), so they are type parameters; both adapters delegate to {@link ChargingService}.
 *
 * @param <StartReq>   start request: msisdn, app, requested MB
 * @param <StartRes>   start answer: session id + first grant (ALLOW / DENY)
 * @param <UpdateReq>  update request: used MB, requested MB
 * @param <UpdateRes>  update answer: next grant (ALLOW / DENY)
 * @param <ReleaseReq> release request: used MB
 * @param <ReleaseRes> release answer: total MB used in the session
 */
public interface ChargingApi<StartReq, StartRes, UpdateReq, UpdateRes, ReleaseReq, ReleaseRes> {

    /** CH-1: open a session for one subscriber and one app, and grant the first chunk. */
    StartRes startSession(StartReq request);

    /** CH-2: charge what was used from the current grant, then grant the next chunk. */
    UpdateRes updateSession(String sessionId, UpdateReq request);

    /** CH-3: charge what was used, give the rest of the grant back and close the session. */
    ReleaseRes releaseSession(String sessionId, ReleaseReq request);
}
