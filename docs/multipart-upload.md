# MinIO原生Multipart上传

## 新链路

1. `POST /api/v1/upload/multipart/init` 创建MySQL业务记录和MinIO `uploadId`。
2. `POST /{fileUploadId}/parts/{partNumber}/presign` 获取短期PUT地址。
3. 浏览器使用PUT直接将Blob上传到MinIO，默认同时上传3个Part。
4. `GET /{fileUploadId}/status` 调用MinIO `ListParts`，只补传缺失Part。
5. `POST /{fileUploadId}/complete` 校验Part编号、大小和总字节数，在同一MySQL事务中将文件标记为
   `UPLOADED`并写入`PENDING` Outbox事件。
6. Outbox投递器使用数据库租约领取事件，Kafka确认成功后标记为`PUBLISHED`；临时故障按上限
   5分钟的指数退避持续重试，非法Payload等不可重试错误标记为`DEAD`。
7. `DELETE /{fileUploadId}` 终止MinIO会话并彻底删除未完成任务记录。

Redis不参与该链路。MySQL保存文件归属和业务状态，MinIO保存实际Part状态。

## 文档处理事务与重试

Kafka消费者不会在一个数据库事务中包住文件读取、PDF解析、Embedding和Elasticsearch调用：

1. 从MinIO读取并解析PDF，此阶段不持有数据库事务。
2. 使用短事务原子删除旧Section/Chunk、写入新数据，并将状态更新为`CHUNKED`。
3. 调用Embedding和Elasticsearch，此阶段不持有数据库事务；成功后用短事务更新为`READY`。
4. 临时故障写入`RETRYING`并抛出异常，由Kafka按指数退避重试；若当前版本的Chunk已落库，重试会直接从向量化继续。
5. 明确不可重试的错误，或重试耗尽且消息确认写入DLT后，才更新为`FAILED`。

因此，任务状态用于观察进度、定位失败阶段和恢复检查点；Kafka重试负责再次触发执行，两者职责不同。

## 上线准备

依次执行 [v2_s3_multipart_upload.sql](databases/v2_s3_multipart_upload.sql)、
[v3_transactional_outbox.sql](databases/v3_transactional_outbox.sql) 和
[v4_outbox_dispatcher.sql](databases/v4_outbox_dispatcher.sql)，或在开发环境继续使用
`spring.jpa.hibernate.ddl-auto=update`。生产环境建议显式执行迁移脚本。

`minio.publicUrl`必须是浏览器可访问的MinIO API地址，不能填写仅Docker容器内部可解析的主机名。

浏览器跨域直传需要给bucket配置CORS。下面示例中的前端地址应替换为实际域名：

```xml
<CORSConfiguration>
  <CORSRule>
    <AllowedOrigin>http://localhost:5173</AllowedOrigin>
    <AllowedMethod>PUT</AllowedMethod>
    <AllowedMethod>GET</AllowedMethod>
    <AllowedHeader>*</AllowedHeader>
    <ExposeHeader>ETag</ExposeHeader>
    <MaxAgeSeconds>3600</MaxAgeSeconds>
  </CORSRule>
</CORSConfiguration>
```

保存为`cors.xml`后，可使用MinIO Client配置：

```bash
mc cors set local/uploads cors.xml
```

还应为未完成Multipart任务配置生命周期清理，例如一天后自动中止：

```xml
<LifecycleConfiguration>
  <Rule>
    <ID>abort-incomplete-multipart</ID>
    <Status>Enabled</Status>
    <Filter><Prefix></Prefix></Filter>
    <AbortIncompleteMultipartUpload>
      <DaysAfterInitiation>1</DaysAfterInitiation>
    </AbortIncompleteMultipartUpload>
  </Rule>
</LifecycleConfiguration>
```

上线后按“小文件、超过一个Part的大文件、中途断网续传、预签名URL过期、重复完成、取消上传”六种场景验收。
