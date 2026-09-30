package com.yizhaoqi.docmind.config;

import com.yizhaoqi.docmind.model.OrganizationTag;
import com.yizhaoqi.docmind.model.User;
import com.yizhaoqi.docmind.repository.OrganizationTagRepository;
import com.yizhaoqi.docmind.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrgTagInitializerTest {
    @Mock OrganizationTagRepository organizationTagRepository;
    @Mock UserRepository userRepository;
    @Mock JdbcTemplate jdbcTemplate;
    @InjectMocks OrgTagInitializer initializer;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(initializer, "adminUsername", "admin");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(new User()));
        when(organizationTagRepository.existsByTagId("admin")).thenReturn(true);
    }

    @Test
    void createsCanonicalDefaultOnly() throws Exception {
        when(organizationTagRepository.existsByTagId("DEFAULT")).thenReturn(false);
        when(organizationTagRepository.findAll()).thenReturn(List.of(tag("DEFAULT")));
        initializer.run();
        ArgumentCaptor<OrganizationTag> created = ArgumentCaptor.forClass(OrganizationTag.class);
        verify(organizationTagRepository).save(created.capture());
        assertEquals("DEFAULT", created.getValue().getTagId());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void legacyCleanupGuardsAllDatabaseAssociations() throws Exception {
        when(organizationTagRepository.existsByTagId("DEFAULT")).thenReturn(true);
        when(organizationTagRepository.findAll()).thenReturn(List.of(tag("DEFAULT"), tag("default")));
        // 未删除时保留旧记录，不调用无条件的 repository.delete。
        initializer.run();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq("default"), eq("默认组织"),
                eq("系统默认组织标签，自动分配给所有新用户"));
        assertTrue(sql.getValue().contains("BINARY legacy.tag_id = BINARY ?"));
        assertTrue(sql.getValue().contains("child.tag_id IS NULL AND u.id IS NULL AND f.id IS NULL"));
        assertTrue(sql.getValue().contains("u.primary_org"));
        assertTrue(sql.getValue().contains("u.org_tags"));
        verify(organizationTagRepository, never()).delete(any(OrganizationTag.class));
    }

    @Test
    void preservesLegacyWhenCanonicalIdIsMissing() throws Exception {
        when(organizationTagRepository.existsByTagId("DEFAULT")).thenReturn(true);
        when(organizationTagRepository.findAll()).thenReturn(List.of(tag("default")));
        initializer.run();
        verifyNoInteractions(jdbcTemplate);
    }

    private OrganizationTag tag(String id) {
        OrganizationTag tag = new OrganizationTag();
        tag.setTagId(id);
        return tag;
    }
}
