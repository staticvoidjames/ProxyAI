package ee.carlrobert.codegpt.mcp

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.diagnostic.thisLogger
import ee.carlrobert.codegpt.completions.*
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.codegpt.events.CodeGPTEvent
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.llm.client.openai.completion.ErrorDetails
import ee.carlrobert.llm.client.openai.completion.response.ToolCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.swing.JPanel

class McpContinuationRunner(
    private val callParameters: ChatCompletionParameters,
    private val wrappedListener: CompletionResponseEventListener,
    private val requestHandler: ToolwindowChatCompletionRequestHandler,
    private val setProcessing: (Boolean) -> Unit,
    private val resetProgress: () -> Unit,
    private val onToolCallUIUpdate: ((JPanel) -> Unit)? = null
) {
    private val logger = thisLogger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun run(toolCallResults: List<Pair<ToolCall, String>>) {
        val toolsToUse = callParameters.message.toolCallResults?.size?.let {
            if (it >= 10) {
                logger.info("Tool call limit of 10 reached. Continuing without tools for natural conclusion.")
                emptyList()
            } else {
                callParameters.mcpTools
            }
        }

        val continuationParams =
            ChatCompletionParameters.builder(callParameters.conversation, callParameters.message)
                .sessionId(callParameters.sessionId)
                .conversationType(callParameters.conversationType)
                .mcpTools(toolsToUse)
                .mcpAttachedServerIds(callParameters.mcpAttachedServerIds)
                .toolApprovalMode(callParameters.toolApprovalMode)
                .toolResults(toolCallResults)
                .project(callParameters.project)
                .requestType(RequestType.TOOL_CALL_CONTINUATION)
                .build()

        scope.launch(Dispatchers.IO) {
            try {
                val service = CompletionRequestService.getInstance()
                val serviceType =
                    ModelSelectionService.getInstance().getServiceForFeature(FeatureType.INLINE_EDIT)
                val factory = CompletionRequestFactory.getFactory(serviceType)
                val request = factory.createChatRequest(continuationParams)
                val continuationListener = McpToolCallEventListener(
                    project = requireNotNull(callParameters.project) { "Project is required for MCP continuation" },
                    callParameters = continuationParams,
                    wrappedListener = object : CompletionResponseEventListener {
                        override fun handleRequestOpen() {
                            wrappedListener.handleRequestOpen()
                        }

                        override fun handleMessage(message: String) {
                            wrappedListener.handleMessage(message)
                        }

                        override fun handleError(error: ErrorDetails, ex: Throwable) {
                            setProcessing(false)
                            resetProgress()
                            wrappedListener.handleError(error, ex)
                        }

                        override fun handleCompleted(
                            fullMessage: String,
                            callParameters: ChatCompletionParameters
                        ) {
                            setProcessing(false)
                            resetProgress()
                            wrappedListener.handleCompleted(fullMessage, callParameters)
                        }

                        override fun handleTokensExceeded(
                            conversation: Conversation,
                            message: Message
                        ) {
                            wrappedListener.handleTokensExceeded(conversation, message)
                        }

                        override fun handleCodeGPTEvent(event: CodeGPTEvent) {
                            wrappedListener.handleCodeGPTEvent(event)
                        }
                    },
                    onToolCallUIUpdate = { panel -> onToolCallUIUpdate?.invoke(panel) ?: Unit },
                    requestHandler = requestHandler
                )

                service.getChatCompletionAsync(request, continuationListener)
                    ?.let { requestHandler.addEventSource(it) }
            } catch (e: Exception) {
                logger.error("Failed to continue conversation after tool execution", e)
                runInEdt {
                    setProcessing(false)
                    wrappedListener.handleError(
                        ErrorDetails("Failed to continue conversation after tool execution: ${e.message}"),
                        e
                    )
                }
            }
        }
    }
}
