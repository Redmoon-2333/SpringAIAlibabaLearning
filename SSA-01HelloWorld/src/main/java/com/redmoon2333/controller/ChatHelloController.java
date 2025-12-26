package com.redmoon2333.controller;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class ChatHelloController {

    @Resource //对话模型
    private ChatModel chatModel;
    @GetMapping("/hello/dochat")
    public String doChat(@RequestParam(name = "msg",defaultValue = "你是谁") String msg) {
        String result = chatModel.call(msg);
        return result;
    }

    @GetMapping("/hello/streamchat")
    public Flux<String> stream(@RequestParam(name = "msg", defaultValue = "你是谁") String msg) {
        return chatModel.stream(msg);
    }
}
