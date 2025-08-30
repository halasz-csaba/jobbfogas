package dev.hcs.jobbfogas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import kotlin.jvm.internal.TypeReference;

import java.util.Map;

public class JobbfogasDeepseekAssistant implements JobbfogasAiAssistant {

    private final static String CHAT_MODEL = "deepseek-chat";
    private final static String SYSTEM_MESSAGE = """
            You are an evaluator that extracts basic info from second-hand PC ads and compare them with user requirements.
            Output must be in JSON with fields: {CPU, GPU, RAM, Motherboard, SSD, HDD, Power, Case, Extra, AiMatchScore, AiHwScore, AiSummary}.
            AiMatchScore is an integer [0,100], showing how much you would suggest the user to buy this item.
            AiHwScore is an integer [0,100], showing how much the item is a good hardware.
            AiSummary should explain reasoning in the same language as user aspects. Summary must be kept short, around 100-150 characters. Avoid repeating the user search requirements.
            """;

    /**
     * Deepseek API is compatible with OpenAI, see https://api-docs.deepseek.com/
     */
    private final ObjectMapper mapper = new ObjectMapper();
    private final OpenAIClient client;
    private final ChatCompletionCreateParams baseParams;

    public JobbfogasDeepseekAssistant(String apiKey, String userAspects) {
        this.client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl("https://api.deepseek.com")
                .build();

        baseParams = ChatCompletionCreateParams.builder()
                .addSystemMessage(SYSTEM_MESSAGE)
                .addUserMessage("User is looking for:\\n" + userAspects)
                .model(CHAT_MODEL)
                .temperature(0.2) // more deterministic
                .build();
    }

    @Override
    public String analyzeItem(Map<String, String> itemAttributes) {

        ChatCompletionCreateParams params = baseParams.toBuilder()
                .addUserMessage("Evaluate this item:\n" + itemAttributes)
                .build();

        ChatCompletion completion = client.chat().completions().create(params);
        return completion.choices().getFirst().message().content().orElseThrow(
                ()-> new RuntimeException("Failed to get DeepSeek response"));
    }
}
