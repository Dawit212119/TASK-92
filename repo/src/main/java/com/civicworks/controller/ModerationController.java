package com.civicworks.controller;

import com.civicworks.config.AuthUtils;
import com.civicworks.domain.entity.Comment;
import com.civicworks.domain.entity.SensitiveWord;
import com.civicworks.domain.entity.User;
import com.civicworks.dto.CreateSensitiveWordRequest;
import com.civicworks.dto.ModerateCommentRequest;
import com.civicworks.service.ModerationService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ModerationController {

    private final ModerationService moderationService;
    private final AuthUtils authUtils;

    public ModerationController(ModerationService moderationService, AuthUtils authUtils) {
        this.moderationService = moderationService;
        this.authUtils = authUtils;
    }

    @PostMapping("/comments/{commentId}/moderate")
    @PreAuthorize("hasRole('MODERATOR') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Comment> moderateComment(@PathVariable UUID commentId,
                                                    @Valid @RequestBody ModerateCommentRequest request,
                                                    Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        return ResponseEntity.ok(moderationService.moderateComment(commentId, request, actor));
    }

    @PostMapping("/moderation/sensitive-words")
    @PreAuthorize("hasRole('MODERATOR') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<SensitiveWord> addSensitiveWord(@Valid @RequestBody CreateSensitiveWordRequest request,
                                                           Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        SensitiveWord word = moderationService.addWord(request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(word);
    }

    @GetMapping("/moderation/sensitive-words")
    @PreAuthorize("hasRole('MODERATOR') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Map<String, Object>> listSensitiveWords(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        UUID orgId = actor.getOrganization() != null ? actor.getOrganization().getId() : null;
        Page<SensitiveWord> result = moderationService.listWordsPage(
                orgId, PageRequest.of(Math.max(0, page), Math.min(200, size)));
        return ResponseEntity.ok(Map.of(
                "data",  result.getContent(),
                "page",  result.getNumber(),
                "size",  result.getSize(),
                "total", result.getTotalElements()
        ));
    }
}
