package com.yizhaoqi.docmind.config;

import com.yizhaoqi.docmind.model.OrganizationTag;
import com.yizhaoqi.docmind.model.OrganizationTagDefaults;
import com.yizhaoqi.docmind.model.User;
import com.yizhaoqi.docmind.repository.OrganizationTagRepository;
import com.yizhaoqi.docmind.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;


/**
 * 组织标签初始化器
 * 在应用启动时自动创建默认组织标签（如果不存在）
 */
@Component
@Order(2) // 设置优先级，确保在管理员账号初始化器之后运行
public class OrgTagInitializer implements CommandLineRunner {
    private static final Logger logger = LoggerFactory.getLogger(OrgTagInitializer.class);
    
    private static final String DEFAULT_TAG = OrganizationTagDefaults.TAG_ID;
    private static final String DEFAULT_NAME = OrganizationTagDefaults.NAME;
    private static final String DEFAULT_DESCRIPTION = OrganizationTagDefaults.DESCRIPTION;

    private static final String ADMIN_TAG = "admin";
    private static final String ADMIN_NAME = "管理员组织";
    private static final String ADMIN_DESCRIPTION = "管理员专用组织标签，具有管理权限";

    @Autowired
    private OrganizationTagRepository organizationTagRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${admin.username:admin}")
    private String adminUsername;

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        // 查找管理员用户
        User adminUser = userRepository.findByUsername(adminUsername)
                .orElseThrow(() -> new RuntimeException("管理员账号未找到，无法创建组织标签"));

        // 创建默认组织标签
        createOrganizationTagIfNotExists(DEFAULT_TAG, DEFAULT_NAME, DEFAULT_DESCRIPTION, adminUser);

        // 仅清理未被使用的旧系统标签；绝不迁移或删除有关联的数据。
        removeUnusedLegacyDefaultTag();
        
        // 创建管理员组织标签
        createOrganizationTagIfNotExists(ADMIN_TAG, ADMIN_NAME, ADMIN_DESCRIPTION, adminUser);
        
        logger.info("组织标签初始化完成");
    }

    private void removeUnusedLegacyDefaultTag() {
        organizationTagRepository.flush();
        var tags = organizationTagRepository.findAll();
        if (tags.stream().noneMatch(tag -> "default".equals(tag.getTagId()))) {
            return;
        }
        if (tags.stream().noneMatch(tag -> DEFAULT_TAG.equals(tag.getTagId()))) {
            logger.warn("默认组织未使用标准标识 {}，请检查数据库大小写排序规则；保留旧 default 记录", DEFAULT_TAG);
            return;
        }
        int removed = jdbcTemplate.update("""
                DELETE legacy FROM organization_tags legacy
                LEFT JOIN organization_tags child ON BINARY child.parent_tag = BINARY legacy.tag_id
                LEFT JOIN users u ON BINARY u.primary_org = BINARY legacy.tag_id
                    OR FIND_IN_SET(BINARY legacy.tag_id, BINARY REPLACE(u.org_tags, ' ', '')) > 0
                LEFT JOIN file_upload f ON BINARY f.org_tag = BINARY legacy.tag_id
                WHERE BINARY legacy.tag_id = BINARY ? AND legacy.name = ?
                    AND legacy.description = ?
                    AND child.tag_id IS NULL AND u.id IS NULL AND f.id IS NULL
                """, "default", DEFAULT_NAME, DEFAULT_DESCRIPTION);
        if (removed > 0) {
            logger.info("已清理未被使用的旧默认组织 default，统一使用 {}", DEFAULT_TAG);
        } else {
            logger.warn("旧默认组织 default 仍存在，可能有关联数据或属性已修改；保留记录，请检查后迁移至 {}", DEFAULT_TAG);
        }
    }
    
    /**
     * 如果组织标签不存在，则创建
     */
    private void createOrganizationTagIfNotExists(String tagId, String name, String description, User creator) {
        logger.info("检查组织标签是否存在: {}", tagId);
        if (!organizationTagRepository.existsByTagId(tagId)) {
            logger.info("创建组织标签: {}", tagId);
            OrganizationTag tag = new OrganizationTag();
            tag.setTagId(tagId);
            tag.setName(name);
            tag.setDescription(description);
            tag.setCreatedBy(creator);
            organizationTagRepository.save(tag);
            logger.info("组织标签 '{}' 创建成功", tagId);
        } else {
            logger.info("组织标签 '{}' 已存在，跳过创建步骤", tagId);
        }
    }
}
