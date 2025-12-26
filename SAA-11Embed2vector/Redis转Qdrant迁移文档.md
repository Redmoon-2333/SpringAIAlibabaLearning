# Redis 转 Qdrant 向量数据库迁移文档

## 一、概述

本文档介绍如何将 Spring AI 项目中的向量存储从 Redis 迁移到 Qdrant。

### 为什么选择 Qdrant？

- **专为向量搜索优化**：Qdrant 是专门设计用于高效向量相似性搜索的数据库
- **更好的性能**：在大规模向量数据场景下，性能优于通用型数据库
- **丰富的过滤功能**：支持复杂的元数据过滤和混合搜索
- **可扩展性**：支持分布式部署和水平扩展

## 二、环境准备

### 2.1 安装 Qdrant

#### 使用 Docker 安装（推荐）

```bash
docker run -p 6333:6333 -p 6334:6334 \
    -v $(pwd)/qdrant_storage:/qdrant/storage \
    qdrant/qdrant
```

#### 使用 Docker Compose

创建 `docker-compose.yml`：

```yaml
version: '3.8'
services:
  qdrant:
    image: qdrant/qdrant:latest
    ports:
      - "6333:6333"  # HTTP API
      - "6334:6334"  # gRPC API
    volumes:
      - ./qdrant_storage:/qdrant/storage
    restart: unless-stopped
```

启动服务：
```bash
docker-compose up -d
```

### 2.2 验证 Qdrant 运行状态

访问 Qdrant Web UI：
```
http://localhost:6333/dashboard
```

或使用 API 检查：
```bash
curl http://localhost:6333/
```

## 三、项目依赖配置

### 3.1 移除 Redis 依赖

从 `pom.xml` 中移除以下依赖：

```xml
<!-- 移除这些 Redis 相关依赖 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-redis-store</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

### 3.2 添加 Qdrant 依赖

在 `pom.xml` 中添加：

```xml
<!-- Qdrant 向量数据库依赖 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-qdrant-store</artifactId>
</dependency>

<!-- gRPC 依赖 -->
<dependency>
    <groupId>io.grpc</groupId>
    <artifactId>grpc-netty-shaded</artifactId>
    <version>1.58.0</version>
</dependency>
<dependency>
    <groupId>io.grpc</groupId>
    <artifactId>grpc-protobuf</artifactId>
    <version>1.58.0</version>
</dependency>
<dependency>
    <groupId>io.grpc</groupId>
    <artifactId>grpc-stub</artifactId>
    <version>1.58.0</version>
</dependency>

<!-- Protocol Buffers 依赖 -->
<dependency>
    <groupId>com.google.protobuf</groupId>
    <artifactId>protobuf-java</artifactId>
    <version>3.25.1</version>
</dependency>
```

## 四、配置文件修改

### 4.1 移除 Redis 配置

从 `application.yml` 中移除：

```yaml
# 删除 Redis 配置
spring:
  redis:
    host: localhost
    port: 6379
  ai:
    vectorstore:
      redis:
        # ...
```

### 4.2 添加 Qdrant 配置

在 `application.yml` 中添加：

```yaml
spring:
  ai:
    vectorstore:
      qdrant:
        host: localhost
        port: 6334              # gRPC 端口
        # api-key: your-api-key  # 如果启用了认证
        collection-name: my-documents
        initialize-schema: true
```

## 五、代码迁移

### 5.1 创建 Qdrant 配置类

创建 `config/VectorStoreConfig.java`：

```java
package com.redmoon2333.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VectorStoreConfig {

    @Value("${spring.ai.vectorstore.qdrant.host:localhost}")
    private String qdrantHost;

    @Value("${spring.ai.vectorstore.qdrant.port:6334}")
    private int qdrantPort;

    @Value("${spring.ai.vectorstore.qdrant.collection-name:my-documents}")
    private String collectionName;

    @Value("${spring.ai.vectorstore.qdrant.initialize-schema:true}")
    private boolean initializeSchema;

    @Bean
    public QdrantClient qdrantClient() {
        return new QdrantClient(
                QdrantGrpcClient.newBuilder(qdrantHost, qdrantPort, false)
                        .build()
        );
    }

    @Bean
    public VectorStore vectorStore(QdrantClient qdrantClient, EmbeddingModel embeddingModel) {
        return QdrantVectorStore.builder(qdrantClient, embeddingModel)
                .collectionName(collectionName)
                .initializeSchema(initializeSchema)
                .build();
    }
}
```

### 5.2 业务代码调整

业务代码基本不需要修改，因为都是使用 `VectorStore` 接口：

```java
@RestController
public class VectorController {
    
    @Resource
    private VectorStore vectorStore;  // 自动注入 Qdrant 实现
    
    // 添加文档（无需修改）
    @GetMapping("/add")
    public void add() {
        List<Document> documents = List.of(
            new Document("i study LLM"),
            new Document("i love java")
        );
        vectorStore.add(documents);
    }
    
    // 相似性搜索（无需修改）
    @GetMapping("/search")
    public List<Document> search(@RequestParam String query) {
        SearchRequest request = SearchRequest.builder()
            .query(query)
            .topK(5)
            .build();
        return vectorStore.similaritySearch(request);
    }
}
```

## 六、数据迁移

### 6.1 从 Redis 导出数据

创建迁移工具类：

```java
@Component
public class DataMigrationTool {
    
    // 从 Redis 导出
    public List<Document> exportFromRedis(RedisVectorStore redisStore) {
        // 使用相似性搜索获取所有文档
        SearchRequest request = SearchRequest.builder()
            .query("")
            .topK(10000)
            .build();
        return redisStore.similaritySearch(request);
    }
    
    // 导入到 Qdrant
    public void importToQdrant(QdrantVectorStore qdrantStore, List<Document> documents) {
        // 批量导入
        int batchSize = 100;
        for (int i = 0; i < documents.size(); i += batchSize) {
            int end = Math.min(i + batchSize, documents.size());
            List<Document> batch = documents.subList(i, end);
            qdrantStore.add(batch);
        }
    }
}
```

### 6.2 执行迁移

```java
@Service
public class MigrationService {
    
    @Autowired
    private DataMigrationTool migrationTool;
    
    public void migrate() {
        // 1. 从 Redis 导出
        List<Document> documents = migrationTool.exportFromRedis(redisStore);
        
        // 2. 导入到 Qdrant
        migrationTool.importToQdrant(qdrantStore, documents);
        
        System.out.println("迁移完成，共迁移 " + documents.size() + " 条记录");
    }
}
```

## 七、功能对比

### 7.1 API 差异

| 功能 | Redis | Qdrant | 说明 |
|------|-------|--------|------|
| 添加文档 | `add(List<Document>)` | `add(List<Document>)` | 相同 |
| 相似性搜索 | `similaritySearch()` | `similaritySearch()` | 相同 |
| 过滤搜索 | 支持 | 支持（更强大） | Qdrant 过滤功能更丰富 |
| 删除文档 | `delete(List<String>)` | `delete(List<String>)` | 相同 |

### 7.2 性能对比

- **小规模数据（< 10万）**：性能相近
- **中规模数据（10万 - 100万）**：Qdrant 性能优势明显
- **大规模数据（> 100万）**：Qdrant 性能优势显著，且支持分布式

## 八、常见问题

### 8.1 连接问题

**问题**：无法连接到 Qdrant
**解决**：
- 检查 Qdrant 服务是否启动：`docker ps`
- 检查端口是否正确：HTTP 端口 6333，gRPC 端口 6334
- 检查防火墙设置

### 8.2 依赖冲突

**问题**：`ClassNotFoundException: com.google.protobuf.MapFieldReflectionAccessor`
**解决**：
```xml
<dependency>
    <groupId>com.google.protobuf</groupId>
    <artifactId>protobuf-java</artifactId>
    <version>3.25.1</version>
</dependency>
```

### 8.3 集合初始化失败

**问题**：集合创建失败或维度不匹配
**解决**：
- 确保 `initialize-schema: true`
- 检查 EmbeddingModel 的向量维度与集合配置一致

## 九、性能优化建议

### 9.1 批量操作

```java
// 推荐：批量添加
vectorStore.add(documents);  // 一次添加多个

// 不推荐：循环单个添加
for (Document doc : documents) {
    vectorStore.add(List.of(doc));  // 性能差
}
```

### 9.2 合理设置 topK

```java
SearchRequest request = SearchRequest.builder()
    .query(query)
    .topK(10)  // 根据实际需求设置，不要过大
    .build();
```

### 9.3 使用过滤器

```java
// 利用元数据过滤减少搜索范围
SearchRequest request = SearchRequest.builder()
    .query(query)
    .topK(5)
    .filterExpression("metadata.category == 'java'")
    .build();
```

## 十、验证迁移结果

### 10.1 数据完整性检查

```java
// 1. 检查文档数量
// 2. 抽样检查文档内容
// 3. 验证搜索结果准确性
```

### 10.2 功能测试

- 测试添加文档功能
- 测试相似性搜索功能
- 测试删除文档功能
- 测试带过滤的搜索

### 10.3 性能测试

- 测试批量导入性能
- 测试搜索响应时间
- 测试并发访问能力

## 十一、回滚方案

如果迁移出现问题，可以：

1. 保留 Redis 数据不删除
2. 在配置中切换回 Redis
3. 重新部署应用

```yaml
# 快速切换回 Redis（如需要）
spring:
  ai:
    vectorstore:
      redis:
        # 恢复 Redis 配置
```

## 十二、总结

迁移步骤总结：

1. ✅ 安装并启动 Qdrant
2. ✅ 修改 Maven 依赖
3. ✅ 更新配置文件
4. ✅ 创建配置类
5. ✅ 迁移数据（可选）
6. ✅ 测试验证
7. ✅ 上线部署

迁移完成后，你将获得：
- 更好的向量搜索性能
- 更强大的过滤功能
- 更好的可扩展性
- 专业的向量数据库支持
