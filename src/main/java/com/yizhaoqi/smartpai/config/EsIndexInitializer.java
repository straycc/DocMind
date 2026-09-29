package com.yizhaoqi.smartpai.config;

import co.elastic.clients.transport.endpoints.BooleanResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import org.apache.http.ConnectionClosedException;
import com.yizhaoqi.smartpai.service.ElasticsearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.io.StringReader;

@Component
@ConditionalOnProperty(name = "elasticsearch.index-initializer.enabled", havingValue = "true", matchIfMissing = true)
public class EsIndexInitializer implements CommandLineRunner { //CommandLineRunner 是 Spring Boot 提供的一个接口，它的作用是在 Spring Boot 应用启动完成后，执行一些自定义的逻辑。

    private static final Logger logger = LoggerFactory.getLogger(EsIndexInitializer.class);

    @Autowired
    private ElasticsearchClient esClient;

    @Value("classpath:es-mappings/knowledge_base.json") // 加载 JSON 文件
    private org.springframework.core.io.Resource mappingResource;

    @Override
    public void run(String... args) throws Exception {
        try {
            initializeIndex();
        } catch (Exception exception) {
            // 特别处理连接关闭异常，尝试重新连接
            if (exception instanceof ConnectionClosedException || (exception.getCause() != null && exception.getCause() instanceof ConnectionClosedException)) {
                logger.error("Elasticsearch连接已关闭，等待5秒后重试...");
                try {
                    Thread.sleep(5000); // 等待5秒后重试
                    // 重新尝试初始化索引
                    initializeIndex();
                } catch (Exception retryException) {
                    logger.error("重试初始化索引失败，请检查Elasticsearch连接配置，比如说是否开启了 HTTPS 模式: {}", retryException.getMessage());
                    throw new RuntimeException("初始化索引失败，重试也未能成功", retryException);
                }
            } else {
                throw new RuntimeException("初始化索引失败", exception);
            }
        }
    }

    /**
     * 初始化索引的核心逻辑
     * @throws Exception
     */
    private void initializeIndex() throws Exception {
        // 检查索引是否存在
        BooleanResponse existsResponse = esClient.indices().exists(ExistsRequest.of(e -> e.index(ElasticsearchService.INDEX_NAME)));
        if (!existsResponse.value()) {
            createIndex();
        } else {
            logger.info("索引 '{}' 已存在", ElasticsearchService.INDEX_NAME);
        }
    }

    /**
     * 创建索引
     * @throws Exception
     */
    private void createIndex() throws Exception {
        // 读取 JSON 文件内容
        String mappingJson = new String(Files.readAllBytes(mappingResource.getFile().toPath()), StandardCharsets.UTF_8);

        // 创建索引并应用映射
        CreateIndexRequest createIndexRequest = CreateIndexRequest.of(c -> c
                .index(ElasticsearchService.INDEX_NAME) // 索引名称
                .withJson(new StringReader(mappingJson)) // 使用 JSON 文件定义映射
        );
        esClient.indices().create(createIndexRequest);
        logger.info("索引 '{}' 已创建", ElasticsearchService.INDEX_NAME);
    }
}
