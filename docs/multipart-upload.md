# MinIO原生Multipart上传

## 新链路

1. `POST /api/v1/upload/multipart/init` 创建MySQL业务记录和MinIO `uploadId`。
2. `POST /{fileUploadId}/parts/{partNumber}/presign` 获取短期PUT地址。
3. 浏览器使用PUT直接将Blob上传到MinIO，默认同时上传3个Part。
4. `GET /{fileUploadId}/status` 调用MinIO `ListParts`，只补传缺失Part。
5. `POST /{fileUploadId}/complete` 校验Part编号、大小和总字节数，完成对象后发送Kafka处理任务。
6. `DELETE /{fileUploadId}` 终止MinIO会话并彻底删除未完成任务记录。

Redis不参与该链路。MySQL保存文件归属和业务状态，MinIO保存实际Part状态。

## 上线准备

先执行 [v2_s3_multipart_upload.sql](databases/v2_s3_multipart_upload.sql)，或在开发环境继续使用
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
