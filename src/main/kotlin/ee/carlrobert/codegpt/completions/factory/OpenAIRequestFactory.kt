package ee.carlrobert.codegpt.completions.factory

import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.readText
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.ReferencedFile
import ee.carlrobert.codegpt.completions.*
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.conversations.ConversationsState
import ee.carlrobert.codegpt.mcp.McpToolConverter
import ee.carlrobert.codegpt.mcp.McpToolPromptFormatter
import ee.carlrobert.codegpt.psistructure.models.ClassStructure
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings.Companion.getState
import ee.carlrobert.codegpt.settings.prompts.CoreActionsState
import ee.carlrobert.codegpt.settings.prompts.FilteredPromptsService
import ee.carlrobert.codegpt.settings.prompts.PromptsSettings
import ee.carlrobert.codegpt.settings.prompts.addProjectPath
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.ui.textarea.ConversationTagProcessor
import ee.carlrobert.codegpt.util.file.FileUtil.getImageMediaType
import ee.carlrobert.llm.client.openai.completion.OpenAIChatCompletionModel.*
import ee.carlrobert.llm.client.openai.completion.request.*
import ee.carlrobert.llm.completion.CompletionRequest

class OpenAIRequestFactory : BaseRequestFactory() {

    override fun createChatRequest(params: ChatCompletionParameters): OpenAIChatCompletionRequest {
        val model = ModelSelectionService.getInstance().getModelForFeature(params.featureType)
        val configuration = service<ConfigurationSettings>().state
        val requestBuilder: OpenAIChatCompletionRequest.Builder =
            OpenAIChatCompletionRequest.Builder(buildOpenAIChatMessages(model, params))
                .setModel(model)
                .setStream(true)
                .setMaxTokens(null)
                .setMaxCompletionTokens(configuration.maxTokens)
        if (isReasoningModel(model)) {
            requestBuilder
                .setTemperature(null)
                .setPresencePenalty(null)
                .setFrequencyPenalty(null)
        } else {
            requestBuilder.setTemperature(configuration.temperature.toDouble())
        }
        if (!params.mcpTools.isNullOrEmpty() && params.toolApprovalMode != ToolApprovalMode.BLOCK_ALL) {
            val openAITools = params.mcpTools!!.map { McpToolConverter.convertToOpenAITool(it) }
            requestBuilder.setTools(openAITools)

            when (params.toolApprovalMode) {
                ToolApprovalMode.AUTO_APPROVE -> {
                    requestBuilder.setToolChoice("auto")
                }

                ToolApprovalMode.REQUIRE_APPROVAL -> {
                    requestBuilder.setToolChoice("auto")
                }

                else -> {}
            }
        }
        return requestBuilder.build()
    }

    override fun createInlineEditRequest(params: InlineEditCompletionParameters): OpenAIChatCompletionRequest {
        val model = ModelSelectionService.getInstance().getModelForFeature(FeatureType.INLINE_EDIT)
        val systemPrompt = prepareInlineEditSystemPrompt(params)
        val messages = buildInlineEditMessages(systemPrompt, params.conversation)

        val configuration = service<ConfigurationSettings>().state
        return if (isReasoningModel(model)) {
            val collapsed = messages.joinToString("\n\n") { msg ->
                when (msg) {
                    is OpenAIChatCompletionStandardMessage -> msg.content
                    else -> ""
                }
            }
            buildBasicO1Request(model, collapsed, systemPrompt = "", stream = true)
        } else {
            OpenAIChatCompletionRequest.Builder(messages)
                .setModel(model)
                .setStream(true)
                .setTemperature(configuration.temperature.toDouble())
                .build()
        }
    }

    override fun createAutoApplyRequest(params: AutoApplyParameters): CompletionRequest {
        val model = ModelSelectionService.getInstance().getModelForFeature(FeatureType.AUTO_APPLY)
        val systemPrompt =
            service<FilteredPromptsService>().getFilteredAutoApplyPrompt(params.chatMode)
                .replace("{{changes_to_merge}}", CompletionRequestUtil.formatCode(params.source))
                .replace(
                    "{{destination_file}}",
                    CompletionRequestUtil.formatCode(
                        params.destination.readText(),
                        params.destination.path
                    )
                )
        val prompt = "Merge the following changes to the destination file."

        if (isReasoningModel(model)) {
            return buildBasicO1Request(model, prompt, systemPrompt, stream = true)
        }
        return createBasicCompletionRequest(systemPrompt, prompt, model, true)
    }

    override fun createBasicCompletionRequest(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        stream: Boolean,
        featureType: FeatureType
    ): CompletionRequest {
        val model = ModelSelectionService.getInstance().getModelForFeature(featureType)
        return if (isReasoningModel(model)) {
            buildBasicO1Request(
                model,
                userPrompt,
                systemPrompt,
                maxCompletionTokens = maxTokens,
                stream = stream
            )
        } else {
            OpenAIChatCompletionRequest.Builder(
                listOf(
                    OpenAIChatCompletionStandardMessage("system", systemPrompt),
                    OpenAIChatCompletionStandardMessage("user", userPrompt)
                )
            )
                .setModel(model)
                .setStream(stream)
                .build()
        }
    }

    override fun createLookupRequest(params: LookupCompletionParameters): OpenAIChatCompletionRequest {
        val model = ModelSelectionService.getInstance().getModelForFeature(FeatureType.LOOKUP)
        val (prompt) = params
        if (isReasoningModel(model)) {
            return buildBasicO1Request(
                model,
                prompt,
                service<PromptsSettings>().state.coreActions.generateNameLookups.instructions
                    ?: CoreActionsState.DEFAULT_GENERATE_NAME_LOOKUPS_PROMPT
            )
        }
        return createBasicCompletionRequest(
            service<PromptsSettings>().state.coreActions.generateNameLookups.instructions
                ?: CoreActionsState.DEFAULT_GENERATE_NAME_LOOKUPS_PROMPT, prompt, model
        )
    }

    companion object {
        fun buildInlineEditMessages(
            systemPrompt: String,
            conversation: Conversation?
        ): MutableList<OpenAIChatCompletionMessage> {
            val messages = mutableListOf<OpenAIChatCompletionMessage>()
            messages.add(OpenAIChatCompletionStandardMessage("system", systemPrompt))
            conversation?.messages?.forEach { m ->
                val p = m.prompt?.trim().orEmpty()
                if (p.isNotEmpty()) messages.add(OpenAIChatCompletionStandardMessage("user", p))
                val r = m.response?.trim().orEmpty()
                if (r.isNotEmpty()) messages.add(
                    OpenAIChatCompletionStandardMessage(
                        "assistant",
                        r
                    )
                )
            }
            messages.add(OpenAIChatCompletionStandardMessage("user", "Implement."))
            return messages
        }

        fun isReasoningModel(model: String?) =
            listOf(
                O_4_MINI.code,
                O_3.code,
                O_3_MINI.code,
                O_1_MINI.code,
                O_1_PREVIEW.code,
                GPT_5_MINI.code,
                GPT_5.code,
            ).contains(model)

        fun buildBasicO1Request(
            model: String,
            prompt: String,
            systemPrompt: String = "",
            maxCompletionTokens: Int = 4096,
            stream: Boolean = false,
        ): OpenAIChatCompletionRequest {
            val messages = if (systemPrompt.isEmpty()) {
                listOf(OpenAIChatCompletionStandardMessage("user", prompt))
            } else {
                listOf(
                    OpenAIChatCompletionStandardMessage("user", systemPrompt),
                    OpenAIChatCompletionStandardMessage("user", prompt)
                )
            }
            return OpenAIChatCompletionRequest.Builder(messages)
                .setModel(model)
                .setMaxCompletionTokens(maxCompletionTokens)
                .setMaxTokens(null)
                .setStream(stream)
                .setTemperature(null)
                .setFrequencyPenalty(null)
                .setPresencePenalty(null)
                .build()
        }

        fun buildOpenAIMessages(
            model: String?,
            callParameters: ChatCompletionParameters,
            referencedFiles: List<ReferencedFile>? = null,
            conversationsHistory: List<Conversation>? = null,
            psiStructure: Set<ClassStructure>? = null
        ): List<OpenAIChatCompletionMessage> {
            val messages = buildOpenAIChatMessages(
                model = model,
                callParameters = callParameters,
                referencedFiles = referencedFiles ?: callParameters.referencedFiles,
                conversationsHistory = conversationsHistory ?: callParameters.history,
                psiStructure = psiStructure,
            )

            if (model == null) {
                return messages
            }

            val encodingManager = EncodingManager.getInstance()
            val totalUsage = messages.parallelStream()
                .mapToInt { message: OpenAIChatCompletionMessage? ->
                    when (message) {
                        is OpenAIChatCompletionToolMessage -> {
                            countToolMessageTokens(message, encodingManager)
                        }

                        is OpenAIChatCompletionAssistantMessage -> {
                            countAssistantMessageTokens(message, encodingManager)
                        }

                        else -> encodingManager.countMessageTokens(message)
                    }
                }
                .sum() + getState().maxTokens
            val modelMaxTokens: Int
            try {
                modelMaxTokens = findByCode(model).maxTokens

                if (totalUsage <= modelMaxTokens) {
                    return messages
                }
            } catch (ex: NoSuchElementException) {
                return messages
            }
            return tryReducingMessagesOrThrow(
                messages,
                callParameters.conversation.isDiscardTokenLimit,
                totalUsage,
                modelMaxTokens
            )
        }

        private fun buildOpenAIChatMessages(
            model: String?,
            callParameters: ChatCompletionParameters,
            referencedFiles: List<ReferencedFile>? = null,
            conversationsHistory: List<Conversation>? = null,
            psiStructure: Set<ClassStructure>? = null
        ): MutableList<OpenAIChatCompletionMessage> {
            val messages = mutableListOf<OpenAIChatCompletionMessage>()
            val role = if (isReasoningModel(model)) "user" else "system"

            val effectiveHistory = conversationsHistory ?: callParameters.history
            val effectiveRefs = referencedFiles ?: callParameters.referencedFiles
            val effectivePsi = psiStructure ?: callParameters.psiStructure

            addSystemMessage(messages, callParameters, effectiveHistory, role, effectiveRefs)

            when (callParameters.requestType) {
                RequestType.TOOL_CALL_REQUEST -> {
                    handleToolCallRequest(messages, callParameters, effectiveRefs, effectivePsi)
                }

                RequestType.TOOL_CALL_CONTINUATION -> {
                    handleToolCallContinuation(messages, callParameters)
                }

                RequestType.NORMAL_REQUEST -> {
                    handleNormalRequest(messages, callParameters, effectiveRefs, effectivePsi)
                }
            }

            return messages
        }

        private fun addSystemMessage(
            messages: MutableList<OpenAIChatCompletionMessage>,
            callParameters: ChatCompletionParameters,
            conversationsHistory: List<Conversation>?,
            role: String,
            referencedFiles: List<ReferencedFile>?
        ) {
            val selectedPersona = service<PromptsSettings>().state.personas.selectedPersona
            if (callParameters.conversationType == ConversationType.DEFAULT && !selectedPersona.disabled) {
                val sessionPersonaDetails = callParameters.personaDetails
                val baseInstructions = sessionPersonaDetails?.instructions?.addProjectPath()
                    ?: service<FilteredPromptsService>()
                        .getFilteredPersonaPrompt(callParameters.chatMode)
                        .addProjectPath()
                val instructions = service<FilteredPromptsService>()
                    .applyClickableLinks(baseInstructions)
                val history = if (conversationsHistory.isNullOrEmpty()) {
                    ""
                } else {
                    conversationsHistory.joinToString("\n\n") {
                        ConversationTagProcessor.formatConversation(it)
                    }
                }

                if (instructions.isNotEmpty()) {
                    val filesBlock = buildString {
                        val unique = mutableSetOf<String>()
                        val hasRefs = referencedFiles
                            ?.any { !it.fileContent().isNullOrBlank() && unique.add(it.filePath()) } == true
                        if (hasRefs) {
                            append("\n\n<referenced_files>")
                            referencedFiles!!.forEach { rf ->
                                if (!rf.fileContent().isNullOrBlank()) {
                                    append("\n")
                                    append(CompletionRequestUtil.formatCode(rf.fileContent(), rf.filePath()))
                                }
                            }
                            append("\n</referenced_files>")
                        }
                    }

                    val base = if (history.isBlank()) instructions else instructions.trimEnd() + "\n" + history
                    val content = base + filesBlock
                    messages.add(OpenAIChatCompletionStandardMessage(role, content))
                }
            }
            if (callParameters.conversationType == ConversationType.REVIEW_CHANGES) {
                val base = service<PromptsSettings>().state.coreActions.reviewChanges.instructions
                messages.add(OpenAIChatCompletionStandardMessage(role, base))
            }
            if (callParameters.conversationType == ConversationType.FIX_COMPILE_ERRORS) {
                val base =
                    service<PromptsSettings>().state.coreActions.fixCompileErrors.instructions
                messages.add(OpenAIChatCompletionStandardMessage(role, base))
            }

            if (!callParameters.mcpTools.isNullOrEmpty() && callParameters.toolApprovalMode != ToolApprovalMode.BLOCK_ALL) {
                val toolsPrompt =
                    McpToolPromptFormatter().formatToolsForSystemPrompt(callParameters.mcpTools!!)
                if (toolsPrompt.isNotEmpty()) {
                    val lastMessage = messages.lastOrNull()
                    if (lastMessage is OpenAIChatCompletionStandardMessage && lastMessage.role == "system") {
                        messages[messages.size - 1] = OpenAIChatCompletionStandardMessage(
                            "system",
                            lastMessage.content + "\n" + toolsPrompt
                        )
                    } else {
                        messages.add(OpenAIChatCompletionStandardMessage("system", toolsPrompt))
                    }
                }
            }
        }

        private fun addConversationHistory(
            messages: MutableList<OpenAIChatCompletionMessage>,
            callParameters: ChatCompletionParameters
        ) {
            callParameters.conversation.messages.forEach { msg ->
                val isCurrent = msg.id == callParameters.message.id

                if (msg.prompt.isNotEmpty() && !isCurrent) {
                    messages.add(OpenAIChatCompletionStandardMessage("user", msg.prompt))
                }

                if (msg.hasToolCalls()) {
                    if (!isCurrent || !callParameters.retry) {
                        messages.add(
                            OpenAIChatCompletionAssistantMessage(
                                msg.response ?: "",
                                msg.toolCalls
                            )
                        )
                        msg.toolCallResults?.forEach { result ->
                            val functionName =
                                msg.toolCalls?.find { it.id == result.key }?.function?.name
                                    ?: "unknown-function"
                            messages.add(
                                OpenAIChatCompletionToolMessage(
                                    result.key,
                                    functionName,
                                    result.value
                                )
                            )
                        }
                    }
                } else {
                    if (!isCurrent || !callParameters.retry) {
                        messages.add(
                            OpenAIChatCompletionStandardMessage(
                                "assistant",
                                msg.response ?: ""
                            )
                        )
                    }
                }
            }
        }

        private fun handleNormalRequest(
            messages: MutableList<OpenAIChatCompletionMessage>,
            callParameters: ChatCompletionParameters,
            referencedFiles: List<ReferencedFile>?,
            psiStructure: Set<ClassStructure>?
        ) {
            addConversationHistory(messages, callParameters)

            val message = callParameters.message
            val imageDetails = callParameters.imageDetails
            if (imageDetails != null) {
                messages.add(
                    OpenAIChatCompletionDetailedMessage(
                        "user",
                        listOf(
                            OpenAIMessageImageURLContent(
                                OpenAIImageUrl(
                                    imageDetails.mediaType,
                                    imageDetails.data
                                )
                            ),
                            OpenAIMessageTextContent(message.prompt)
                        )
                    )
                )
            } else {
                if (message.prompt.isNotEmpty()) {
                    messages.add(OpenAIChatCompletionStandardMessage("user", message.prompt))
                }
            }
        }

        private fun handleToolCallRequest(
            messages: MutableList<OpenAIChatCompletionMessage>,
            callParameters: ChatCompletionParameters,
            referencedFiles: List<ReferencedFile>?,
            psiStructure: Set<ClassStructure>?
        ) {
            addConversationHistory(messages, callParameters)

            val message = callParameters.message
            if (message.prompt.isNotEmpty()) {
                messages.add(OpenAIChatCompletionStandardMessage("user", message.prompt))
            }

            if (message.hasToolCalls()) {
                messages.add(
                    OpenAIChatCompletionAssistantMessage(
                        message.response ?: "",
                        message.toolCalls
                    )
                )
            }
        }

        private fun handleToolCallContinuation(
            messages: MutableList<OpenAIChatCompletionMessage>,
            callParameters: ChatCompletionParameters
        ) {
            addConversationHistory(messages, callParameters)

            val message = callParameters.message
            if (message.prompt.isNotEmpty()) {
                messages.add(OpenAIChatCompletionStandardMessage("user", message.prompt))
            }

            if (!message.toolCalls.isNullOrEmpty()) {
                messages.add(
                    OpenAIChatCompletionAssistantMessage(
                        message.response,
                        message.toolCalls
                    )
                )
            }

            message.toolCallResults?.forEach { (callId, result) ->
                val functionName =
                    message.toolCalls?.find { it.id == callId }?.function?.name
                        ?: "unknown-function"
                messages.add(OpenAIChatCompletionToolMessage(callId, functionName, result))
            }

            if (!callParameters.retry && message.response != null && message.response.isNotEmpty()) {
                messages.add(OpenAIChatCompletionStandardMessage("assistant", message.response))
            }
        }

        private fun tryReducingMessagesOrThrow(
            messages: MutableList<OpenAIChatCompletionMessage>,
            discardTokenLimit: Boolean,
            totalInputUsage: Int,
            modelMaxTokens: Int
        ): List<OpenAIChatCompletionMessage> {
            val result: MutableList<OpenAIChatCompletionMessage?> = messages.toMutableList()
            var totalUsage = totalInputUsage
            if (!ConversationsState.getInstance().discardAllTokenLimits) {
                if (!discardTokenLimit) {
                    throw TotalUsageExceededException()
                }
            }
            val encodingManager = EncodingManager.getInstance()
            // skip the system prompt
            for (i in 1 until result.size - 1) {
                if (totalUsage <= modelMaxTokens) {
                    break
                }

                val message = result[i]
                when (message) {
                    is OpenAIChatCompletionStandardMessage -> {
                        totalUsage -= encodingManager.countMessageTokens(message)
                        result[i] = null
                    }

                    is OpenAIChatCompletionToolMessage -> {
                        totalUsage -= encodingManager.countMessageTokens(message)
                    }

                    is OpenAIChatCompletionDetailedMessage -> {
                        totalUsage -= encodingManager.countMessageTokens(message)
                        result[i] = null
                    }
                }
            }

            return result.filterNotNull()
        }

        fun createBasicCompletionRequest(
            systemPrompt: String,
            userPrompt: String,
            model: String? = null,
            isStream: Boolean = false
        ): OpenAIChatCompletionRequest {
            return OpenAIChatCompletionRequest.Builder(
                listOf(
                    OpenAIChatCompletionStandardMessage("system", systemPrompt),
                    OpenAIChatCompletionStandardMessage("user", userPrompt)
                )
            )
                .setModel(model)
                .setStream(isStream)
                .build()
        }

        private fun countToolMessageTokens(
            message: OpenAIChatCompletionToolMessage,
            encodingManager: EncodingManager
        ): Int {
            val content = message.content ?: ""
            val toolCallId = message.callId ?: ""

            val contentTokens = encodingManager.countTokens(content)
            val toolCallIdTokens = encodingManager.countTokens(toolCallId)

            return contentTokens + toolCallIdTokens + 10
        }

        private fun countAssistantMessageTokens(
            message: OpenAIChatCompletionAssistantMessage,
            encodingManager: EncodingManager
        ): Int {
            val content = message.content ?: ""
            var totalTokens = encodingManager.countMessageTokens("assistant", content)

            message.toolCalls?.let { toolCalls ->
                toolCalls.forEach { toolCall ->
                    val toolCallId = toolCall.id ?: ""
                    val toolType = toolCall.type ?: ""
                    val functionName = toolCall.function?.name ?: ""
                    val functionArgs = toolCall.function?.arguments ?: ""

                    totalTokens += encodingManager.countTokens(toolCallId)
                    totalTokens += encodingManager.countTokens(toolType)
                    totalTokens += encodingManager.countTokens(functionName)
                    totalTokens += encodingManager.countTokens(functionArgs)

                    totalTokens += 8
                }
            }

            return totalTokens
        }
    }
}
