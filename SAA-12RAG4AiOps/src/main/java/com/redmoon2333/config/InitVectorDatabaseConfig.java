package com.redmoon2333.config;

import cn.hutool.crypto.SecureUtil;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.ExecutionException;


@Configuration
@Slf4j
public class InitVectorDatabaseConfig
{
    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private QdrantClient qdrantClient;

    @Value("${spring.ai.vectorstore.qdrant.collection-name:aiops-knowledge-base}")
    private String collectionName;

    @Value("${vector.init.enabled:true}")
    private boolean initEnabled;

    @Value("${vector.init.clear-before-init:false}")
    private boolean clearBeforeInit;

    @Value("classpath:aiops-knowledge.txt")
    private Resource knowledgeFile;

    @Value("${vector.splitter.default-chunk-size:800}")
    private int defaultChunkSize;

    @Value("${vector.splitter.min-chunk-size-chars:350}")
    private int minChunkSizeChars;

    @Value("${vector.splitter.min-chunk-length-to-embed:5}")
    private int minChunkLengthToEmbed;

    @Value("${vector.splitter.max-num-chunks:10000}")
    private int maxNumChunks;

    @PostConstruct
    public void init()
    {
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
                    // 等待一下让 Qdrant 完成删除
                    Thread.sleep(1000);
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

            // 3. 去重处理（参考 Redis 版本的去重逻辑）
            String sourceMetadata = (String) textReader.getCustomMetadata().get("source");
            String textHash = SecureUtil.md5(sourceMetadata);
            String payloadKey = "vector-init-hash";

            // 判断是否已存入过，通过查询 Qdrant 中是否存在该 hash 值的 payload
            Boolean retFlag = checkIfDataNotExists(textHash, payloadKey);

            log.info("****retFlag: {}", retFlag);

            if (Boolean.TRUE.equals(retFlag)) {
                // 键不存在，首次插入，可以保存进向量数据库
                // 重要：为每个文档添加 hash 标记到 metadata 中
                splitDocuments.forEach(doc -> {
                    doc.getMetadata().put(payloadKey, textHash);
                });
                
                vectorStore.add(splitDocuments);
                log.info("向量数据库初始化完成，共添加 {} 条向量数据", splitDocuments.size());
            } else {
                // 键已存在，跳过或者报错
                // throw new RuntimeException("---重复操作");
                log.warn("------向量初始化数据已经加载过，请不要重复操作");
            }

        } catch (Exception e) {
            log.error("向量数据库初始化失败", e);
            throw new RuntimeException("向量数据库初始化失败", e);
        }
    }

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
                            .setLimit(1)
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
                            .build()
            ).get();

            // 如果查询结果为空，说明数据不存在，返回 true
            boolean notExists = response.getResultList().isEmpty();
            log.info("查询 hash={} 的记录，数据不存在={}", textHash, notExists);
            return notExists;

        } catch (InterruptedException | ExecutionException e) {
            log.error("查询 Qdrant 数据失败", e);
            // 查询失败时，为了安全起见，认为数据不存在（允许插入）
            return true;
        }
    }
}
