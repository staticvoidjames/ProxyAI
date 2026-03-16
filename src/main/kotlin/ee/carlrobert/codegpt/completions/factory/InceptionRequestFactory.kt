package ee.carlrobert.codegpt.completions.factory

import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.completions.*
import ee.carlrobert.codegpt.settings.prompts.CoreActionsState
import ee.carlrobert.codegpt.settings.prompts.PromptsSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.util.EditWindowFormatter.FormatResult
import ee.carlrobert.llm.client.inception.request.InceptionNextEditRequest
import ee.carlrobert.llm.client.openai.completion.request.OpenAIChatCompletionRequest
import ee.carlrobert.llm.client.openai.completion.request.OpenAIChatCompletionStandardMessage
import ee.carlrobert.llm.completion.CompletionRequest

class InceptionRequestFactory : BaseRequestFactory() {

    override fun createChatRequest(params: ChatCompletionParameters): OpenAIChatCompletionRequest {
        val model = ModelSelectionService.getInstance().getModelForFeature(FeatureType.CHAT)
        val messages = OpenAIRequestFactory.buildOpenAIMessages(
            model = model,
            callParameters = params
        )
        return OpenAIChatCompletionRequest.Builder(messages)
            .setModel(model)
            .setStream(true)
            .build()
    }

    override fun createInlineEditRequest(params: InlineEditCompletionParameters): OpenAIChatCompletionRequest {
        val model = ModelSelectionService.getInstance().getModelForFeature(FeatureType.INLINE_EDIT)
        val systemPrompt = prepareInlineEditSystemPrompt(params)
        val messages =
            OpenAIRequestFactory.buildInlineEditMessages(systemPrompt, params.conversation)
        return OpenAIChatCompletionRequest.Builder(messages)
            .setModel(model)
            .setStream(true)
            .build()
    }

    override fun createNextEditRequest(params: NextEditParameters, formatResult: FormatResult): InceptionNextEditRequest {
        val model = ModelSelectionService.getInstance().getModelForFeature(FeatureType.NEXT_EDIT)
        val content = composeNextEditMessage(params, formatResult)
        val message = OpenAIChatCompletionStandardMessage("user", content)
        return InceptionNextEditRequest.Builder()
            .setModel(model)
            .setMessages(listOf(message))
            .build()
    }

    override fun createBasicCompletionRequest(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        stream: Boolean,
        featureType: FeatureType
    ): CompletionRequest {
        val model = ModelSelectionService.getInstance().getModelForFeature(featureType)
        return OpenAIChatCompletionRequest.Builder(
            listOf(
                OpenAIChatCompletionStandardMessage("system", systemPrompt),
                OpenAIChatCompletionStandardMessage("user", userPrompt)
            )
        )
            .setModel(model)
            .setStream(stream)
            .build()
    }

    override fun createLookupRequest(params: LookupCompletionParameters): OpenAIChatCompletionRequest {
        val model = ModelSelectionService.getInstance().getModelForFeature(FeatureType.LOOKUP)
        val (prompt) = params
        val systemPrompt =
            service<PromptsSettings>().state.coreActions.generateNameLookups.instructions
                ?: CoreActionsState.DEFAULT_GENERATE_NAME_LOOKUPS_PROMPT

        return OpenAIChatCompletionRequest.Builder(
            listOf(
                OpenAIChatCompletionStandardMessage("system", systemPrompt),
                OpenAIChatCompletionStandardMessage("user", prompt)
            )
        )
            .setModel(model)
            .setStream(false)
            .build()
    }
}
