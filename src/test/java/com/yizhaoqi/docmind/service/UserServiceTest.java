package com.yizhaoqi.docmind.service;

import com.yizhaoqi.docmind.model.User;
import com.yizhaoqi.docmind.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {
    @Mock
    private UserRepository userRepository;
    @InjectMocks
    private UserService userService;

    @Test
    @SuppressWarnings("unchecked")
    void filtersRolesBeforePaginationAndReturnsExplicitRole() {
        when(userRepository.findAll()).thenReturn(List.of(
                user(1, User.Role.USER), user(2, User.Role.ADMIN), user(3, User.Role.ADMIN)));

        Map<String, Object> result = userService.getUserList(null, null, User.Role.ADMIN, 2, 1);
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");

        assertEquals(2L, result.get("totalElements"));
        assertEquals(2, result.get("totalPages"));
        assertEquals(1, content.size());
        assertEquals(3L, content.get(0).get("userId"));
        assertEquals("ADMIN", content.get(0).get("role"));
        assertFalse(content.get(0).containsKey("status"));
    }

    @Test
    void keepsRepositoryTotalWhenNoFiltersAreProvided() {
        when(userRepository.findAll(any(Pageable.class))).thenAnswer(invocation ->
                new PageImpl<>(List.of(user(1, User.Role.USER)), invocation.getArgument(0), 21));

        Map<String, Object> result = userService.getUserList(null, null, null, 1, 1);

        assertEquals(21L, result.get("totalElements"));
        assertEquals(21, result.get("totalPages"));
    }

    private User user(long id, User.Role role) {
        User user = new User();
        user.setId(id);
        user.setUsername("user-" + id);
        user.setRole(role);
        return user;
    }
}
