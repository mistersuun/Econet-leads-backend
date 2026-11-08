package com.econet.leads.exception;

import com.econet.leads.dto.BusinessDTO;

/** An exact duplicate of the lead being created exists; rendered as 409 {error, duplicate: BusinessDTO}. */
public class DuplicateLeadException extends RuntimeException {

    private final transient BusinessDTO duplicate;

    public DuplicateLeadException(String message, BusinessDTO duplicate) {
        super(message);
        this.duplicate = duplicate;
    }

    public BusinessDTO getDuplicate() {
        return duplicate;
    }
}
