package com.econet.leads.service;

import com.econet.leads.dto.LeadStatusUpdateRequest;
import com.econet.leads.dto.LogCallRequest;
import com.econet.leads.dto.LogCallResponse;
import com.econet.leads.exception.ApiException;
import com.econet.leads.model.Business;
import com.econet.leads.model.CallOutcome;
import com.econet.leads.model.Contact;
import com.econet.leads.model.LeadStatus;
import com.econet.leads.model.User;
import com.econet.leads.repository.BusinessRepository;
import com.econet.leads.repository.ContactRepository;
import com.econet.leads.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeadServiceTest {

    private static final ZoneId MONTREAL = ZoneId.of("America/Montreal");
    // Monday 2026-10-05 14:30 in Montreal
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 14, 30);

    @Mock BusinessRepository businessRepository;
    @Mock ContactRepository contactRepository;
    @Mock UserRepository userRepository;

    LeadService service;
    User caller;
    User otherUser;
    Business lead;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(MONTREAL).toInstant(), MONTREAL);
        service = new LeadService(businessRepository, contactRepository, userRepository, new DataQualityService(), clock);

        caller = user("alice");
        otherUser = user("bob");
        lead = new Business();
        lead.setId(UUID.randomUUID());
        lead.setBusinessName("CPE Les Petits Pas");
        lead.setBusinessType("CPE");
        lead.setDataSource("TEST");
        lead.setPhone("(514) 555-0101");
        lead.setLeadStatus(LeadStatus.NEW);
        lead.setContactCount(0);

        when(businessRepository.findByIdForUpdate(lead.getId())).thenReturn(Optional.of(lead));
        when(userRepository.findById(caller.getId())).thenReturn(Optional.of(caller));
        when(businessRepository.saveAndFlush(any(Business.class))).thenAnswer(inv -> inv.getArgument(0));
        when(contactRepository.saveAndFlush(any(Contact.class))).thenAnswer(inv -> {
            Contact c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });
        when(contactRepository.save(any(Contact.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static User user(String name) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setUsername(name);
        u.setEmail(name + "@example.com");
        u.setRole(User.UserRole.USER);
        u.setActive(true);
        return u;
    }

    private LogCallRequest call(CallOutcome outcome) {
        LogCallRequest r = new LogCallRequest();
        r.setOutcome(outcome);
        return r;
    }

    @Test
    void noAnswerOnNewLeadMarksContactedAssignsCallerAndSchedulesFollowUp() {
        LogCallResponse response = service.logCall(lead.getId(), call(CallOutcome.NO_ANSWER), caller.getId());

        assertThat(lead.getLeadStatus()).isEqualTo(LeadStatus.CONTACTED);
        assertThat(lead.getContactCount()).isEqualTo(1);
        assertThat(lead.getLastContactedAt()).isEqualTo(NOW);
        assertThat(lead.getAssignedTo()).isSameAs(caller);
        assertThat(lead.getNextFollowUpAt()).isEqualTo(LocalDateTime.of(2026, 10, 7, 10, 0));

        ArgumentCaptor<Contact> captor = ArgumentCaptor.forClass(Contact.class);
        verify(contactRepository).saveAndFlush(captor.capture());
        Contact contact = captor.getValue();
        assertThat(contact.getContactType()).isEqualTo(Contact.ContactType.APPEL);
        assertThat(contact.getContactDate()).isEqualTo(NOW);
        assertThat(contact.getUser()).isSameAs(caller);
        assertThat(contact.getOutcome()).isEqualTo(Contact.ContactOutcome.NO_ANSWER);
        assertThat(contact.getNextActionDate()).isEqualTo(lead.getNextFollowUpAt());

        assertThat(response.getLead().getLeadStatus()).isEqualTo(LeadStatus.CONTACTED);
        assertThat(response.getLead().getAssignedToName()).isEqualTo("alice");
        assertThat(response.getContact().getOutcome()).isEqualTo(Contact.ContactOutcome.NO_ANSWER);
    }

    @Test
    void voicemailKeepsInterestedStatus() {
        lead.setLeadStatus(LeadStatus.INTERESTED);
        service.logCall(lead.getId(), call(CallOutcome.VOICEMAIL), caller.getId());
        assertThat(lead.getLeadStatus()).isEqualTo(LeadStatus.INTERESTED);
        assertThat(lead.getNextFollowUpAt()).isEqualTo(LocalDateTime.of(2026, 10, 7, 10, 0));
    }

    @Test
    void callbackKeepsQuoteSentStatus() {
        lead.setLeadStatus(LeadStatus.QUOTE_SENT);
        service.logCall(lead.getId(), call(CallOutcome.CALLBACK), caller.getId());
        assertThat(lead.getLeadStatus()).isEqualTo(LeadStatus.QUOTE_SENT);
    }

    @Test
    void interestedSetsStatusEstimatedValueAndThreeDayFollowUp() {
        LogCallRequest request = call(CallOutcome.INTERESTED);
        request.setEstimatedValue(new BigDecimal("4500.00"));
        request.setContactPerson("  Mme Tremblay ");
        request.setNotes("Veut une soumission pour 3 bureaux");

        service.logCall(lead.getId(), request, caller.getId());

        assertThat(lead.getLeadStatus()).isEqualTo(LeadStatus.INTERESTED);
        assertThat(lead.getEstimatedValue()).isEqualByComparingTo("4500.00");
        assertThat(lead.getNextFollowUpAt()).isEqualTo(LocalDateTime.of(2026, 10, 8, 10, 0));
    }

    @Test
    void quoteSentSetsStatus() {
        service.logCall(lead.getId(), call(CallOutcome.QUOTE_SENT), caller.getId());
        assertThat(lead.getLeadStatus()).isEqualTo(LeadStatus.QUOTE_SENT);
        assertThat(lead.getNextFollowUpAt()).isEqualTo(LocalDateTime.of(2026, 10, 8, 10, 0));
    }

    @Test
    void wonClearsFollowUp() {
        lead.setNextFollowUpAt(NOW.minusDays(1));
        service.logCall(lead.getId(), call(CallOutcome.WON), caller.getId());
        assertThat(lead.getLeadStatus()).isEqualTo(LeadStatus.WON);
        assertThat(lead.getNextFollowUpAt()).isNull();
    }

    @Test
    void notInterestedMarksLost() {
        service.logCall(lead.getId(), call(CallOutcome.NOT_INTERESTED), caller.getId());
        assertThat(lead.getLeadStatus()).isEqualTo(LeadStatus.LOST);
        assertThat(lead.getNextFollowUpAt()).isNull();
    }

    @Test
    void wrongNumberAndDoNotCallMarkDoNotCall() {
        service.logCall(lead.getId(), call(CallOutcome.WRONG_NUMBER), caller.getId());
        assertThat(lead.getLeadStatus()).isEqualTo(LeadStatus.DO_NOT_CALL);
        assertThat(lead.getNextFollowUpAt()).isNull();

        lead.setLeadStatus(LeadStatus.CONTACTED);
        service.logCall(lead.getId(), call(CallOutcome.DO_NOT_CALL), caller.getId());
        assertThat(lead.getLeadStatus()).isEqualTo(LeadStatus.DO_NOT_CALL);
        assertThat(lead.getContactCount()).isEqualTo(2);
    }

    @Test
    void explicitFollowUpWins() {
        LogCallRequest request = call(CallOutcome.CALLBACK);
        LocalDateTime explicit = LocalDateTime.of(2026, 10, 20, 15, 45);
        request.setNextFollowUpAt(explicit);
        service.logCall(lead.getId(), request, caller.getId());
        assertThat(lead.getNextFollowUpAt()).isEqualTo(explicit);
    }

    @Test
    void existingAssigneeIsKept() {
        lead.setAssignedTo(otherUser);
        service.logCall(lead.getId(), call(CallOutcome.NO_ANSWER), caller.getId());
        assertThat(lead.getAssignedTo()).isSameAs(otherUser);
    }

    @Test
    void unknownLeadIs404() {
        UUID missing = UUID.randomUUID();
        when(businessRepository.findByIdForUpdate(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.logCall(missing, call(CallOutcome.WON), caller.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not found");
        verify(contactRepository, never()).saveAndFlush(any());
    }

    @Test
    void statusChangeRecordsNoteAndClearsFollowUpForTerminal() {
        lead.setLeadStatus(LeadStatus.QUOTE_SENT);
        lead.setNextFollowUpAt(NOW.plusDays(2));
        LeadStatusUpdateRequest request = new LeadStatusUpdateRequest();
        request.setStatus(LeadStatus.WON);
        request.setNote("Contrat signé");

        var dto = service.updateStatus(lead.getId(), request, caller.getId());

        assertThat(dto.getLeadStatus()).isEqualTo(LeadStatus.WON);
        assertThat(lead.getNextFollowUpAt()).isNull();
        assertThat(lead.getContactCount()).isZero(); // a status change is not a contact
        ArgumentCaptor<Contact> captor = ArgumentCaptor.forClass(Contact.class);
        verify(contactRepository).save(captor.capture());
        assertThat(captor.getValue().getContactType()).isEqualTo(Contact.ContactType.NOTE);
        assertThat(captor.getValue().getOutcome()).isEqualTo(Contact.ContactOutcome.WON);
        assertThat(captor.getValue().getNotes()).contains("QUOTE_SENT → WON").contains("Contrat signé");
    }

    @Test
    void unchangedStatusWithoutNoteIsANoOp() {
        LeadStatusUpdateRequest request = new LeadStatusUpdateRequest();
        request.setStatus(LeadStatus.NEW);
        service.updateStatus(lead.getId(), request, caller.getId());
        verify(contactRepository, never()).save(any());
    }
}
