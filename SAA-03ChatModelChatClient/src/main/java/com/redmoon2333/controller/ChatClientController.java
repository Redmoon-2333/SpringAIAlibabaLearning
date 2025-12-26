package com.redmoon2333.controller;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatClientController
{
    private final ChatClient dashScopeChatClient;

    /**
     * ChatClient不支持自动输入，依赖ChatModel对象接口，ChatClient.builder(dashScopeChatModel).build();
     * @param dashScopeChatModel
     */
    public ChatClientController(ChatModel dashScopeChatModel)
    {
        this.dashScopeChatClient = ChatClient.builder(dashScopeChatModel).build();
    }

    @GetMapping("/chatclient/dochat")
    public String doChat(@RequestParam(name = "msg",defaultValue = "2加9等于几") String msg)
    {
        return dashScopeChatClient.prompt().user(msg).call().content();
    }
}
