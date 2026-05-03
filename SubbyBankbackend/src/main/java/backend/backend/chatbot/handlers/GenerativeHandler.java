package backend.backend.chatbot.handlers;

import backend.backend.chatbot.ChatIntent;
import backend.backend.chatbot.ChatResponse;
import backend.backend.service.GeminiService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GenerativeHandler {

    private final GeminiService geminiService;

    public ChatResponse handle(ChatIntent intent) {

        String prompt = """
        Explain the banking process clearly.
        Do not use user-specific data.
        Provide steps only.
        for
        transfer: go to transfer section->put your account number->
        receivers account number->amount->password again then succesfull

        for
        loan: first go to the loan section->enter monthly income+requiered amount ->click on apply->if eligible then apply for the loan
        or else denined

        for
        loan rejection:
        reasons:
        1)loan eligibilty check is based on various factors such as average transactions amount,creditscore,account balance etc

        reply one of these based on the query and add your explanation to each one of those:
        %s
        """.formatted(intent.getKeyword());

        return ChatResponse.generative(
                geminiService.chatWithGemini(prompt)
        );
    }
}
