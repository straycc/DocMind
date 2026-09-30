package com.yizhaoqi.docmind.controller;

import com.yizhaoqi.docmind.exception.CustomException;
import com.yizhaoqi.docmind.service.ConversationSessionService;
import com.yizhaoqi.docmind.utils.JwtUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users/conversations")
public class ConversationController {
    private final ConversationSessionService conversationSessionService;
    private final JwtUtils jwtUtils;

    public ConversationController(ConversationSessionService conversationSessionService, JwtUtils jwtUtils) {
        this.conversationSessionService = conversationSessionService;
        this.jwtUtils = jwtUtils;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader("Authorization") String token) {
        return ok(conversationSessionService.list(username(token)));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestHeader("Authorization") String token) {
        return ok(conversationSessionService.create(username(token)));
    }

    @GetMapping("/{conversationId}/messages")
    public ResponseEntity<?> messages(@RequestHeader("Authorization") String token,
                                      @PathVariable String conversationId) {
        return ok(conversationSessionService.messages(username(token), conversationId));
    }

    @DeleteMapping("/{conversationId}")
    public ResponseEntity<?> delete(@RequestHeader("Authorization") String token,
                                    @PathVariable String conversationId) {
        conversationSessionService.delete(username(token), conversationId);
        return ok(null);
    }

    private String username(String authorization) {
        String token = authorization == null ? "" : authorization.replace("Bearer ", "");
        String username = jwtUtils.extractUsernameFromToken(token);
        if (username == null || username.isBlank()) {
            throw new CustomException("无效的token", HttpStatus.UNAUTHORIZED);
        }
        return username;
    }

    private ResponseEntity<?> ok(Object data) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "操作成功");
        response.put("data", data == null ? Map.of() : data);
        return ResponseEntity.ok(response);
    }
}
