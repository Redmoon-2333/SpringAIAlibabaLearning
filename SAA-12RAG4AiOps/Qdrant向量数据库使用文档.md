# Qdrant 向量数据库使用文档

## 一、Qdrant 向量数据库原理

### 1.1 什么是 Qdrant？

Qdrant 是一个高性能的开源向量数据库，专为向量相似性搜索而设计。它特别适用于：
- RAG（检索增强生成）应用
- 语义搜索
- 推荐系统
- 图像/音频检索

### 1.2 核心概念

#### Collection（集合）
- 类似于传统数据库的"表"
- 存储向量及其关联的 payload（元数据）
- 每个 collection 有固定的向量维度

#### Vector（向量）
- 文本通过 Embedding 模型转换而来的数值表示
- 通常是一个浮点数数组，如 `[0.123, -0.456, 0.789, ...]`
- 维度由 Embedding 模型决定（如 text-embedding-v3 是 1024 维）

#### Payload（负载/元数据）
- 与向量关联的结构化数据
- 可以是任何 JSON 对象
- 支持过滤查询

#### Point（点）
- Qdrant 中的基本存储单元
- 包含：ID + Vector + Payload

### 1.3 工作原理

```
文本 → Embedding模型 → 向量 → 存入Qdrant
                                    ↓
用户查询 → Embedding模型 → 查询向量 → 相似度搜索 → 返回最相似的文档
```

#### 相似度计算

Qdrant 支持多种相似度度量：
- **Cosine（余弦相似度）**：最常用，范围 [-1, 1]
- **Euclidean（欧氏距离）**：衡量空间距离
- **Dot Product（点积）**：速度快但需要归一化

### 1.4 架构特点

- **高性能**：基于 HNSW（分层可导航小世界图）算法
- **可扩展**：支持分布式部署
- **持久化**：数据自动持久化到磁盘
- **实时更新**：支持动态增删改查
- **过滤查询**：支持复杂的元数据过滤

---

## 二、项目中的代码实现

### 2.1 项目架构

```
SAA-12RAG4AiOps/
├── config/
│   ├── QdrantConfig.java              # Qdrant 客户端配置
│   ├── VectorStoreConfig.java         # VectorStore Bean 配置
│   ├── InitVectorDatabaseConfig.java  # 自动初始化配置（含去重）
│   └── SaaLLMConfig.java              # LLM 模型配置
├── controller/
│   └── RAGController.java             # RAG 接口
└── resources/
    ├── aiops-knowledge.txt            # 知识库文件
    └── application.properties         # 配置文件
```

### 2.2 核心配置代码

#### 2.2.1 QdrantConfig.java - Qdrant 客户端配置

```java
@Configuration
@Slf4j
public class QdrantConfig {

    @Value("${spring.ai.vectorstore.qdrant.host:localhost}")
    private String qdrantHost;

    @Value("${spring.ai.vectorstore.qdrant.port:6334}")
    private int qdrantPort;

    @Bean
    public QdrantClient qdrantClient() {
        log.info("初始化 Qdrant 客户端: {}:{}", qdrantHost, qdrantPort);
        
        // 创建 gRPC 客户端（Qdrant 使用 gRPC 通信）
        QdrantGrpcClient grpcClient = QdrantGrpcClient.newBuilder(qdrantHost, qdrantPort, false)
                .build();
        
        return new QdrantClient(grpcClient);
    }
}
```

**说明：**
- Qdrant 默认使用 **6334 端口**进行 gRPC 通信（高性能）
- 6333 端口是 HTTP API（用于管理和 Web UI）

#### 2.2.2 VectorStoreConfig.java - VectorStore 配置

```java
@Configuration
public class VectorStoreConfig {

    @Value("${spring.ai.vectorstore.qdrant.collection-name:aiops-knowledge-base}")
    private String collectionName;

    @Value("${spring.ai.vectorstore.qdrant.initialize-schema:true}")
    private boolean initializeSchema;

    @Bean
    public VectorStore vectorStore(QdrantClient qdrantClient, EmbeddingModel embeddingModel) {
        return QdrantVectorStore.builder(qdrantClient, embeddingModel)
                .collectionName(collectionName)
                .initializeSchema(initializeSchema)  // 自动创建 collection
                .build();
    }
}
```

**说明：**
- `QdrantVectorStore` 是 Spring AI 对 Qdrant 的抽象封装
- `initializeSchema=true` 会自动创建 collection（如果不存在）
- 向量维度由 `EmbeddingModel` 自动确定

#### 2.2.3 application.properties 配置

```properties
# Qdrant 连接配置
spring.ai.vectorstore.qdrant.host=localhost
spring.ai.vectorstore.qdrant.port=6334
spring.ai.vectorstore.qdrant.collection-name=aiops-knowledge-base
spring.ai.vectorstore.qdrant.initialize-schema=true

# DashScope Embedding 模型配置
spring.ai.dashscope.api-key=${aliQwen_api}
spring.ai.dashscope.embedding.options.model=text-embedding-v3

# 向量数据库初始化配置
vector.init.enabled=true                      # 是否启用自动初始化
vector.init.clear-before-init=false           # 是否清空旧数据

# 文本分词器配置
vector.splitter.enabled=true                  # 启用文本分割
vector.splitter.default-chunk-size=800        # 每块大小（token 数）
vector.splitter.min-chunk-size-chars=350      # 最小块大小（字符数）
vector.splitter.min-chunk-length-to-embed=5   # 最小嵌入长度
vector.splitter.max-num-chunks=10000          # 最大块数
vector.splitter.keep-separator=true           # 保留分隔符
```

---

## 三、去重机制详细解析

### 3.1 为什么需要去重？

在实际应用中，可能会多次重启应用，如果每次都重新加载知识库，会导致：
- ❌ 数据库中存在大量重复数据
- ❌ 浪费存储空间
- ❌ 降低检索效率
- ❌ 返回重复的搜索结果

### 3.2 去重策略

项目采用 **基于文件内容 Hash 的去重机制**：

```
文件内容 → MD5 Hash → 作为唯一标识 → 查询是否存在 → 决定是否插入
```

### 3.3 去重实现代码（InitVectorDatabaseConfig.java）

#### 3.3.1 完整流程

```java
@PostConstruct
public void init() {
    if (!initEnabled) {
        log.info("向量数据库初始化已禁用");
        return;
    }

    try {
        log.info("开始初始化向量数据库...");

        // 0. 如果配置了清空，则先删除 collection
        if (clearBeforeInit) {
            try {
                log.warn("正在清空向量数据库 collection: {}", collectionName);
                qdrantClient.deleteCollectionAsync(collectionName).get();
                log.info("成功清空 collection: {}", collectionName);
                Thread.sleep(1000);  // 等待删除完成
            } catch (Exception e) {
                log.warn("清空 collection 失败（可能不存在）: {}", e.getMessage());
            }
        }

        // 1. 读取文件
        TextReader textReader = new TextReader(knowledgeFile);
        textReader.setCharset(Charset.forName("UTF-8"));
        List<Document> documents = textReader.read();
        log.info("读取文档数量: {}", documents.size());

        // 2. 文本分割（分词）
        TokenTextSplitter splitter = new TokenTextSplitter(
                defaultChunkSize,
                minChunkSizeChars,
                minChunkLengthToEmbed,
                maxNumChunks,
                true  // keepSeparator
        );
        List<Document> splitDocuments = splitter.transform(documents);
        log.info("分割后文档数量: {}", splitDocuments.size());

        // 3. 去重处理
        String sourceMetadata = (String) textReader.getCustomMetadata().get("source");
        String textHash = SecureUtil.md5(sourceMetadata);  // 计算文件路径的 MD5
        String payloadKey = "vector-init-hash";

        // 判断是否已存入过
        Boolean retFlag = checkIfDataNotExists(textHash, payloadKey);
        log.info("****retFlag: {}", retFlag);

        if (Boolean.TRUE.equals(retFlag)) {
            // 数据不存在，首次插入
            // 重要：为每个文档添加 hash 标记到 metadata 中
            splitDocuments.forEach(doc -> {
                doc.getMetadata().put(payloadKey, textHash);
            });
            
            vectorStore.add(splitDocuments);
            log.info("向量数据库初始化完成，共添加 {} 条向量数据", splitDocuments.size());
        } else {
            // 数据已存在，跳过
            log.warn("------向量初始化数据已经加载过，请不要重复操作");
        }

    } catch (Exception e) {
        log.error("向量数据库初始化失败", e);
        throw new RuntimeException("向量数据库初始化失败", e);
    }
}
```

#### 3.3.2 去重核心逻辑

```java
/**
 * 检查数据是否不存在（Qdrant 版本去重逻辑）
 * @param textHash 文件内容的 MD5 哈希值
 * @param payloadKey payload 中存储 hash 的键名
 * @return true-数据不存在，可以插入；false-数据已存在，跳过
 */
private Boolean checkIfDataNotExists(String textHash, String payloadKey) {
    try {
        // 使用 Qdrant 的 scroll 功能查询是否存在相同 hash 的记录
        Points.ScrollResponse response = qdrantClient.scrollAsync(
                Points.ScrollPoints.newBuilder()
                        .setCollectionName(collectionName)
                        .setLimit(1)  // 只需要查一条记录
                        .setFilter(Points.Filter.newBuilder()
                                .addMust(Points.Condition.newBuilder()
                                        .setField(Points.FieldCondition.newBuilder()
                                                .setKey(payloadKey)  // 查询 "vector-init-hash" 字段
                                                .setMatch(Points.Match.newBuilder()
                                                        .setKeyword(textHash)  // 匹配具体的 hash 值
                                                        .build())
                                                .build())
                                        .build())
                                .build())
                        .build()
        ).get();

        // 如果查询结果为空，说明数据不存在
        boolean notExists = response.getResultList().isEmpty();
        log.info("查询 hash={} 的记录，数据不存在={}", textHash, notExists);
        return notExists;

    } catch (InterruptedException | ExecutionException e) {
        log.error("查询 Qdrant 数据失败", e);
        // 查询失败时，为了安全起见，认为数据不存在（允许插入）
        return true;
    }
}
```

### 3.4 去重机制工作流程

#### 第一次启动应用

```
1. 读取 aiops-knowledge.txt
   ↓
2. 计算文件路径的 MD5: "1589bf52c37a326bba88302412d568d8"
   ↓
3. 查询 Qdrant: 
   WHERE payload["vector-init-hash"] = "1589bf52c37a326bba88302412d568d8"
   ↓
4. 结果为空 → 数据不存在 → retFlag = true
   ↓
5. 为每个分割后的文档添加 metadata:
   {
     "vector-init-hash": "1589bf52c37a326bba88302412d568d8",
     "source": "aiops-knowledge.txt",
     "doc_content": "文档内容..."
   }
   ↓
6. 插入 Qdrant
   ↓
7. 日志: "向量数据库初始化完成，共添加 2 条向量数据"
```

#### 第二次启动应用

```
1. 读取 aiops-knowledge.txt（内容相同）
   ↓
2. 计算文件路径的 MD5: "1589bf52c37a326bba88302412d568d8"（相同）
   ↓
3. 查询 Qdrant: 
   WHERE payload["vector-init-hash"] = "1589bf52c37a326bba88302412d568d8"
   ↓
4. 找到记录 → 数据已存在 → retFlag = false
   ↓
5. 跳过插入
   ↓
6. 日志: "------向量初始化数据已经加载过，请不要重复操作"
```

### 3.5 去重机制的关键点

#### ✅ 关键点 1：Hash 值的计算

```java
String sourceMetadata = (String) textReader.getCustomMetadata().get("source");
String textHash = SecureUtil.md5(sourceMetadata);
```

- 使用 **文件路径** 的 MD5 作为唯一标识
- 也可以改用 **文件内容** 的 MD5（更准确）

#### ✅ 关键点 2：Metadata 的添加

```java
splitDocuments.forEach(doc -> {
    doc.getMetadata().put(payloadKey, textHash);
});
```

- **必须在插入前添加**，否则下次查询时找不到
- 每个分割后的文档块都要添加相同的 hash

#### ✅ 关键点 3：Qdrant 的过滤查询

```java
.setFilter(Points.Filter.newBuilder()
    .addMust(Points.Condition.newBuilder()
        .setField(Points.FieldCondition.newBuilder()
            .setKey(payloadKey)
            .setMatch(Points.Match.newBuilder()
                .setKeyword(textHash)
                .build())
            .build())
        .build())
    .build())
```

- `addMust`: 必须满足的条件（类似 SQL 的 WHERE）
- `setKey`: 查询的字段名
- `setKeyword`: 精确匹配字符串

### 3.6 数据在 Qdrant 中的存储结构

```json
{
  "id": "auto-generated-uuid",
  "vector": [0.123, -0.456, 0.789, ...],  // 1024 维向量
  "payload": {
    "vector-init-hash": "1589bf52c37a326bba88302412d568d8",
    "source": "aiops-knowledge.txt",
    "charset": "UTF-8",
    "doc_content": "Kubernetes 是一个开源的容器编排平台..."
  }
}
```

### 3.7 清空数据重新初始化

如果发现数据重复或需要重新加载：

**方法 1：配置文件方式**

```properties
# 临时设置为 true
vector.init.clear-before-init=true
```

重启应用后，会自动删除 collection 并重新初始化。

**完成后记得改回：**
```properties
vector.init.clear-before-init=false
```

**方法 2：手动删除 Collection**

```bash
# 使用 curl 删除
curl -X DELETE 'http://localhost:6333/collections/aiops-knowledge-base'

# 或访问 Web UI
http://localhost:6333/dashboard
```

---

## 四、RAG 问答实现

### 4.1 RAG 流程

```
用户问题 → Embedding → 向量检索 → 找到相关文档 → 构建上下文 → LLM 生成答案
```

### 4.2 核心代码（RAGController.java）

```java
@GetMapping("/rag4aiops")
public Flux<String> rag4aiops(String msg) {
    // 1. 从向量数据库检索相关知识
    SearchRequest searchRequest = SearchRequest.builder()
            .query(msg)
            .topK(3)  // 检索最相关的 3 条
            .build();

    List<Document> relevantDocs = vectorStore.similaritySearch(searchRequest);
    log.info("检索到 {} 条相关知识", relevantDocs.size());

    // 2. 构建上下文
    String context = relevantDocs.stream()
            .map(Document::getText)
            .collect(Collectors.joining("\n\n"));

    // 3. 构建完整提示词
    String systemInfo = """
            你是一个专业的 AiOps 运维工程师助手。
            请根据以下知识库内容回答用户的问题。
            如果知识库中没有相关信息，请明确告知用户找不到相关信息。
            
            知识库内容：
            """ + context;

    // 4. 流式返回答案
    return chatClient.prompt()
            .system(systemInfo)
            .user(msg)
            .stream()
            .content();
}
```

### 4.3 使用示例

```bash
# 流式问答
GET http://localhost:8012/rag4aiops?msg=什么是Kubernetes

# 非流式问答
GET http://localhost:8012/rag/qa?msg=Prometheus的作用是什么

# 仅检索知识
GET http://localhost:8012/rag/search?query=容器&topK=5
```

---

## 五、最佳实践

### 5.1 向量维度选择

| Embedding 模型 | 维度 | 特点 |
|---------------|------|------|
| text-embedding-v3 | 1024 | 阿里云，中文效果好 |
| text-embedding-ada-002 | 1536 | OpenAI，多语言 |
| bge-large-zh | 1024 | 开源，中文优化 |

### 5.2 文本分割策略

```properties
# 推荐配置
vector.splitter.default-chunk-size=800        # 适合大多数场景
vector.splitter.min-chunk-size-chars=350      # 避免太小的块
vector.splitter.min-chunk-length-to-embed=5   # 过滤太短的文本
```

**分割原则：**
- 每个块要有完整的语义
- 块之间适当重叠（保留上下文）
- 不要太大（影响检索精度）
- 不要太小（损失上下文）

### 5.3 检索参数优化

```java
SearchRequest searchRequest = SearchRequest.builder()
    .query(question)
    .topK(3)                          // 通常 3-5 条即可
    .similarityThreshold(0.7)         // 相似度阈值（可选）
    .build();
```

**topK 选择：**
- 问答场景：3-5 条
- 推荐场景：10-20 条
- 检索场景：根据需求调整

### 5.4 性能优化

#### 1. 批量操作

```java
// ✅ 推荐：批量添加
vectorStore.add(documents);

// ❌ 不推荐：逐条添加
for (Document doc : documents) {
    vectorStore.add(List.of(doc));
}
```

#### 2. 索引优化

Qdrant 默认使用 HNSW 索引，可以调整参数：

```json
{
  "hnsw_config": {
    "m": 16,              // 连接数（越大越精确但内存占用更多）
    "ef_construct": 100   // 构建时的搜索范围
  }
}
```

#### 3. 使用过滤减少搜索范围

```java
SearchRequest searchRequest = SearchRequest.builder()
    .query(query)
    .topK(5)
    .filterExpression("metadata.category == 'kubernetes'")
    .build();
```

### 5.5 监控与维护

#### 查看 Collection 信息

```bash
# 获取 collection 详情
curl http://localhost:6333/collections/aiops-knowledge-base

# 查看数据量
curl http://localhost:6333/collections/aiops-knowledge-base | jq '.result.points_count'
```

#### 定期备份

```bash
# 创建快照
curl -X POST 'http://localhost:6333/collections/aiops-knowledge-base/snapshots'

# 恢复快照
curl -X PUT 'http://localhost:6333/collections/aiops-knowledge-base/snapshots/upload' \
  --data-binary @snapshot.dat
```

---

## 六、常见问题

### Q1: 为什么检索结果不准确？

**可能原因：**
1. Embedding 模型不适合
2. 文本分割策略不当
3. topK 设置过小

**解决方案：**
- 尝试不同的 Embedding 模型
- 调整分割参数
- 增加 topK 数量
- 添加相似度阈值过滤

### Q2: 数据重复怎么办？

**解决方案：**
```properties
# 设置清空标志
vector.init.clear-before-init=true
```

重启一次后改回 `false`

### Q3: Qdrant 连接失败？

**检查清单：**
1. Qdrant 是否启动：`docker ps`
2. 端口是否正确：6334（gRPC）
3. 防火墙是否开放
4. 配置文件中的地址是否正确

### Q4: 向量维度不匹配？

**错误信息：**
```
Vector dimension mismatch: expected 1024, got 1536
```

**解决方案：**
- 确保使用相同的 Embedding 模型
- 删除旧的 collection 重新创建

### Q5: 内存占用太大？

**优化方案：**
1. 减少向量维度（使用更小的模型）
2. 调整 HNSW 参数（减小 m 值）
3. 使用量化（Scalar Quantization）
4. 分片存储（Sharding）

---

## 七、总结

### 7.1 核心要点

1. **Qdrant 是专业的向量数据库**，性能优于通用数据库
2. **去重机制**通过 hash + metadata 实现
3. **文本分割**是提高检索精度的关键
4. **批量操作**可显著提升性能

### 7.2 完整流程回顾

```
知识文件 → 读取 → 分割 → 去重检查 → 向量化 → 存入Qdrant
                                              ↓
                                    用户查询 → 向量检索 → RAG生成
```

### 7.3 项目亮点

- ✅ 自动初始化向量数据库
- ✅ 基于 Hash 的智能去重
- ✅ 支持文本分词优化
- ✅ 流式/非流式 RAG 问答
- ✅ 完整的配置管理

---

## 八、参考资源

- **Qdrant 官方文档**: https://qdrant.tech/documentation/
- **Spring AI 文档**: https://docs.spring.io/spring-ai/reference/
- **DashScope Embedding**: https://help.aliyun.com/zh/dashscope/
- **项目 GitHub**: （如果有的话）

---

**文档版本**: 1.0  
**最后更新**: 2025-12-26  
**作者**: Spring AI Alibaba Team
