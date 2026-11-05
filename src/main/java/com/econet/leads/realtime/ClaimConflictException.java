package com.econet.leads.realtime;

import com.econet.leads.exception.ApiException;
import org.springframework.http.HttpStatus;

/** 409: the lead is claimed by another user; rendered as {error, claimedBy: {userId, username}, expiresAt}. */
public class ClaimConflictException extends ApiException {

    private final transient ClaimRegistry.Claim claim;

    public ClaimConflictException(ClaimRegistry.Claim claim) {
        super(HttpStatus.CONFLICT, "Lead already claimed by " + claim.username());
        this.claim = claim;
    }

    public ClaimRegistry.Claim getClaim() {
        return claim;
    }
}
