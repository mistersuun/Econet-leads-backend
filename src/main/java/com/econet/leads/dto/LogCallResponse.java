package com.econet.leads.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LogCallResponse {
    private BusinessDTO lead;
    private ContactDTO contact;
}
