package ee.carlrobert.codegpt.codecompletions

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import ee.carlrobert.llm.client.openai.completion.ErrorDetails
import ee.carlrobert.llm.completion.CompletionEventListener
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener

/**
 * Debug EventSourceListener that prints raw SSE data
 * 支持智谱AI 的 reasoning_content 字段
 */
class DebugEventSourceListener(
    private val delegate: CompletionEventListener<String>
) : EventSourceListener() {

    companion object {
        private val MAPPER = ObjectMapper()
    }

    private val messageBuilder = StringBuilder()
    private var completed = false

    override fun onOpen(eventSource: EventSource, response: Response) {
        println("=== DEBUG SSE Connection Opened ===")
        println("Response code: ${response.code}")
        println("Response headers: ${response.headers}")
        println("=========================================")
        delegate.onOpen()
    }

    override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
        println("=== DEBUG SSE Event ===")
        println("id: $id")
        println("type: $type")
        println("data: $data")
        println("======================")

        if (data == "[DONE]") {
            println("=== DEBUG [DONE] - Final accumulated message ===")
            println("accumulated: $messageBuilder")
            println("================================================")
            if (!completed) {
                completed = true
                delegate.onComplete(messageBuilder)
            }
            return
        }

        try {
            val rootNode = MAPPER.readTree(data)
            println("=== DEBUG Parsed Response ===")

            val choices = rootNode.get("choices")
            if (choices != null && choices.isArray && choices.size() > 0) {
                val firstChoice = choices.get(0)

                // 格式1: OpenAI Chat/智谱AI - delta.content 或 delta.reasoning_content
                val delta = firstChoice.get("delta")
                // 格式2: DeepSeek/OpenAI Text - text 字段
                val textNode = firstChoice.get("text")

                val content = when {
                    // DeepSeek/OpenAI Text 格式: choices[0].text
                    textNode != null && !textNode.isNull && textNode.asText().isNotEmpty() -> {
                        println("text: ${textNode.asText()}")
                        textNode.asText()
                    }
                    // OpenAI Chat 格式: choices[0].delta.content
                    delta != null -> {
                        val contentNode = delta.get("content")
                        val reasoningContentNode = delta.get("reasoning_content")
                        println("delta.content: ${contentNode?.asText()}")
                        println("delta.reasoning_content: ${reasoningContentNode?.asText()}")
                        when {
                            contentNode != null && !contentNode.isNull && contentNode.asText().isNotEmpty() -> contentNode.asText()
                            reasoningContentNode != null && !reasoningContentNode.isNull -> reasoningContentNode.asText()
                            else -> null
                        }
                    }
                    else -> null
                }

                println("最终内容: $content")
                println("==============================")

                if (content != null) {
                    messageBuilder.append(content)
                    delegate.onMessage(content, eventSource)
                }
            }
        } catch (e: Exception) {
            println("=== DEBUG Parse Error ===")
            println("error: ${e.message}")
            println("raw data: $data")
            println("============================")
        }
    }

    override fun onClosed(eventSource: EventSource) {
        println("=== DEBUG SSE Connection Closed ===")
        println("=== DEBUG Final accumulated message ===")
        println("accumulated: $messageBuilder")
        println("=========================================")
        if (!completed) {
            completed = true
            delegate.onComplete(messageBuilder)
        }
    }

    override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
        println("=== DEBUG SSE Failure ===")
        println("error: ${t?.message}")
        println("response: $response")
        println("===============================")

        // 忽略取消相关的错误(用户快速输入时会触发新请求取消旧请求)
        val errorMessage = t?.message ?: ""
        val isCancellation = errorMessage.contains("CANCEL", ignoreCase = true) ||
                errorMessage.contains("Canceled", ignoreCase = true) ||
                errorMessage.contains("stream was reset", ignoreCase = true)

        if (isCancellation) {
            println("=== DEBUG Ignoring cancellation error ===")
            delegate.onComplete(messageBuilder)
        } else {
            delegate.onError(ErrorDetails(errorMessage.ifEmpty { "Unknown error" }), t ?: RuntimeException("Unknown error"))
        }
    }
}
