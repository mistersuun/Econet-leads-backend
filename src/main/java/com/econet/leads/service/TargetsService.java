package com.econet.leads.service;

import com.econet.leads.dto.TargetsDTO;
import com.econet.leads.exception.ApiException;
import com.econet.leads.model.User;
import com.econet.leads.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Daily call / conversation targets per user. */
@Service
@RequiredArgsConstructor
public class TargetsService {

    private final UserRepository userRepository;

    public static TargetsDTO.Targets of(User user) {
        return new TargetsDTO.Targets(
                user.getDailyCallTarget() != null ? user.getDailyCallTarget() : User.DEFAULT_DAILY_CALL_TARGET,
                user.getDailyConversationTarget() != null ? user.getDailyConversationTarget() : User.DEFAULT_DAILY_CONVERSATION_TARGET);
    }

    @Transactional
    public TargetsDTO.Targets update(UUID userId, TargetsDTO.Update request) {
        User user = userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("User not found: " + userId));
        if (request.effectiveCallTarget() == null && request.effectiveConversationTarget() == null) {
            throw ApiException.badRequest("callTarget or conversationTarget is required");
        }
        if (request.effectiveCallTarget() != null) {
            user.setDailyCallTarget(request.effectiveCallTarget());
        }
        if (request.effectiveConversationTarget() != null) {
            user.setDailyConversationTarget(request.effectiveConversationTarget());
        }
        return of(userRepository.saveAndFlush(user));
    }
}
