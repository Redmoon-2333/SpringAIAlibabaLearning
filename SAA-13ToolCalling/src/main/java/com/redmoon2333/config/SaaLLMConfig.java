package com.redmoon2333.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class SaaLLMConfig
{
    @Bean
    public DashScopeApi dashScopeApi()
    {
        return DashScopeApi.builder()
                .apiKey(System.getenv("aliQwen_api"))
                .build();
    }

    @Bean
    @Primary
    public ChatModel chatModel(DashScopeApi dashScopeApi)
    {
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .build();
    }

    @Bean
    public ChatClient chatClient(@Qualifier("chatModel") ChatModel chatModel)
    {
        return ChatClient.builder(chatModel).build();
    }
}
























