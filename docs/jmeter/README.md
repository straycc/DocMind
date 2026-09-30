# JMeter并发Multipart上传

测试计划：[concurrent-multipart-upload.jmx](concurrent-multipart-upload.jmx)

每个线程读取CSV中的一个不同文件，登录后在同步栅栏等待，再执行：

```text
init → 循环获取Part预签名URL并PUT到MinIO → complete → Outbox → Kafka
```

## 准备文件

参考[upload-files.example.csv](upload-files.example.csv)，创建`D:/jmeter-data/upload-files.csv`。
每行只放一个PDF绝对路径，不要写表头。文件内容必须不同，否则服务端会按MD5识别为历史任务。
CSV文件数量不能少于线程数，否则同步栅栏需要等待30秒超时。

## GUI运行

1. 使用JMeter 5.6.3打开`concurrent-multipart-upload.jmx`。
2. 在Test Plan的Variables中填写可登录的`username`和`password`。
3. 确认`base_url=http://localhost:8081`。
4. 默认6线程，点击运行。

## 命令行运行

在项目根目录执行，输出目录必须事先不存在：

```powershell
jmeter -n `
  -t docs/jmeter/concurrent-multipart-upload.jmx `
  -Jthreads=6 `
  -Jramp_seconds=1 `
  -Jcsv_file=D:/jmeter-data/upload-files.csv `
  -Jusername=你的用户名 `
  -Jpassword=你的密码 `
  -l jmeter-results/multipart-6.jtl `
  -e -o jmeter-report/multipart-6
```

JMeter统计的`Upload one complete file`只包含上传链路，登录耗时单独统计。`complete`返回表示文件进入
Outbox，不包含后续PDF解析、Embedding和ES写入耗时。

## 验证Kafka并行消费

Spring Boot日志中应交错出现以下三个线程：

```text
KafkaListenerEndpointContainer#0-0-C-1
KafkaListenerEndpointContainer#0-1-C-1
KafkaListenerEndpointContainer#0-2-C-1
```

也可以查看分区分配：

```bash
docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:9092 \
  --describe \
  --group file-processing-group
```

当前脚本适合验证多个独立文件同时上传及Kafka并行处理，不用于模拟同一个文件内部多个Part并发上传。
