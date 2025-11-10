package dev.hcs.jobbfogas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.errors.NotFoundException;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Does not score item, only extracts the parts
 */
public class JobbfogasDeepseekInterpreter implements JobbfogasAiAssistant {

    private static final Logger LOGGER = LoggerFactory.getLogger(JobbfogasDeepseekInterpreter.class);

    private static final int RETRIES = 3;

    private final static String CHAT_MODEL = "deepseek-chat";
    private final static String SYSTEM_MESSAGE = """
            You are a computer builder assistant, that extracts basic info from second-hand notebook ads and outputs database
            ready, comparable data about the machine.
            Output must be a raw JSON string object with fields: {model, screen type, screen size, CPU, GPU, RAM, SSD, HDD, Extra}.
            """;

    /**
     * Deepseek API is compatible with OpenAI, see https://api-docs.deepseek.com/
     */
    private final OpenAIClient client;
    private final ChatCompletionCreateParams baseParams;

    public JobbfogasDeepseekInterpreter(String apiKey) {
        this.client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl("https://api.deepseek.com")
                .build();

        baseParams = ChatCompletionCreateParams.builder()
                .addSystemMessage(SYSTEM_MESSAGE)
                .model(CHAT_MODEL)
                .temperature(0.2) // more deterministic
                .build();
    }

    @Override
    public String analyzeItem(Map<String, String> itemAttributes) {

        ChatCompletionCreateParams params = baseParams.toBuilder()
                .addUserMessage("Extract parts info from this item:\n" + itemAttributes)
                .build();

        ChatCompletion completion = null;
        for (int tryCount = 0; tryCount < RETRIES; tryCount++) {
            try {
                completion = client.chat().completions().create(params);
            } catch (NotFoundException e) {
                LOGGER.warn("NotFoundException on try #" + tryCount);
            }
        }
        if (completion == null) {
            throw new RuntimeException("AI response failed " + RETRIES + " times");
        }

        return completion.choices().getFirst().message().content().orElseThrow(
                () -> new RuntimeException("Failed to get DeepSeek response"));
    }
}
