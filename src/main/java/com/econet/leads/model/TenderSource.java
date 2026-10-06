package com.econet.leads.model;

/** Publisher of a tender notice. */
public enum TenderSource {
    /** Government of Canada, canadabuys.canada.ca open data CSV */
    CANADABUYS,
    /** Quebec public bodies, SEAO open data (OCDS JSON on Données Québec) */
    SEAO
}
