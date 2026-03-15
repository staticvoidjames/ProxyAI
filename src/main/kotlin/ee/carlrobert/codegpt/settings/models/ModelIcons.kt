package ee.carlrobert.codegpt.settings.models

import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.settings.service.ServiceType
import javax.swing.Icon

object ModelIcons {
    fun getIconForModel(model: ModelSelection): Icon? {
        return getIconForProvider(model.provider)
    }

    fun getIconForProvider(provider: ServiceType): Icon? {
        return when (provider) {
            ServiceType.OPENAI -> Icons.OpenAI
            ServiceType.ANTHROPIC -> Icons.Anthropic
            ServiceType.GOOGLE -> Icons.Google
            ServiceType.OLLAMA -> Icons.Ollama
            ServiceType.CUSTOM_OPENAI -> Icons.OpenAI
            ServiceType.LLAMA_CPP -> Icons.Llama
            ServiceType.INCEPTION -> Icons.Inception
        }
    }
}
