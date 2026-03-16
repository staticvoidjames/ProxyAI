package ee.carlrobert.codegpt.settings.service

import ai.koog.prompt.llm.LLModel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import ee.carlrobert.codegpt.settings.models.ModelRegistry
import ee.carlrobert.codegpt.settings.models.ModelSelection
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.llm.client.codegpt.PricingPlan

@Service
class ModelSelectionService {

    fun getAgentModel(): LLModel {
        val modelDetailsState = service<ModelSettings>().state.getModelSelection(FeatureType.CHAT)
        val model = modelDetailsState?.model
        val provider = modelDetailsState?.provider
        val models = service<ModelRegistry>().getAgentModels()[provider]

        return models?.find { it.id == model || it.model.id == model }?.model
            ?: throw IllegalArgumentException("Model '$model' not found for provider $provider")
    }

    fun getModelSelectionForFeature(
        featureType: FeatureType,
        pricingPlan: PricingPlan? = null
    ): ModelSelection {
        return try {
            val modelSettings = service<ModelSettings>()
            val modelDetailsState = modelSettings.state.getModelSelection(featureType)

            // DEBUG LOG
            println("=== DEBUG getModelSelectionForFeature ===")
            println("featureType: $featureType")
            println("modelDetailsState.model: ${modelDetailsState?.model}")
            println("modelDetailsState.provider: ${modelDetailsState?.provider}")

            if (modelDetailsState != null && modelDetailsState.model != null && modelDetailsState.provider != null) {
                val allModels = service<ModelRegistry>().getAllModelsForFeature(featureType)
                println("Available models for $featureType:")
                allModels.filter { it.provider == modelDetailsState.provider }.forEach {
                    println("  - id: ${it.id}, model: ${it.model}, displayName: ${it.displayName}")
                }

                val foundModel =
                    service<ModelRegistry>().findModel(
                        modelDetailsState.provider!!,
                        modelDetailsState.model!!
                    )
                println("foundModel: $foundModel")
                println("==========================================")

                if (foundModel != null) {
                    return foundModel
                }
            }

            println("Using default model for $featureType")
            println("==========================================")
            service<ModelRegistry>().getDefaultModelForFeature(featureType, pricingPlan)
        } catch (exception: Exception) {
            logger.warn(
                "Error getting model selection for feature: $featureType, using default",
                exception
            )
            service<ModelRegistry>().getDefaultModelForFeature(featureType, pricingPlan)
        }
    }

    fun getServiceForFeature(featureType: FeatureType): ServiceType {
        return getServiceForFeature(featureType, null)
    }

    fun getServiceForFeature(featureType: FeatureType, pricingPlan: PricingPlan?): ServiceType {
        return try {
            getModelSelectionForFeature(featureType, pricingPlan).provider
        } catch (exception: Exception) {
            logger.warn("Error getting service for feature: $featureType, using default", exception)
            ServiceType.OPENAI
        }
    }

    fun getModelForFeature(featureType: FeatureType, pricingPlan: PricingPlan? = null): String {
        return try {
            getModelSelectionForFeature(featureType, pricingPlan).model
        } catch (exception: Exception) {
            logger.warn("Error getting model for feature: $featureType, using default", exception)
            service<ModelRegistry>().getDefaultModelForFeature(featureType, pricingPlan).model
        }
    }

    fun syncWithAvailableCustomOpenAIModels(preferredServiceId: String? = null) {
        val registry = service<ModelRegistry>()
        val settings = service<ModelSettings>()

        FeatureType.entries.forEach { featureType ->
            if (!registry.isFeatureSupportedByProvider(
                    featureType,
                    ServiceType.CUSTOM_OPENAI
                )
            ) return@forEach

            val current = settings.getModelSelection(featureType)
            if (current?.provider != ServiceType.CUSTOM_OPENAI) return@forEach

            val available = registry.getAllModelsForFeature(featureType)
                .filter { it.provider == ServiceType.CUSTOM_OPENAI }

            val isCurrentValid = available.any { it.model == current.model }

            if (!isCurrentValid) {
                val newId = when {
                    !preferredServiceId.isNullOrBlank() && available.any { it.model == preferredServiceId } -> preferredServiceId
                    available.isNotEmpty() -> available.first().model
                    else -> null
                }
                settings.setModelWithProvider(featureType, newId, ServiceType.CUSTOM_OPENAI)
            }
        }
    }

    companion object {

        private val logger = thisLogger()

        @JvmStatic
        fun getInstance(): ModelSelectionService {
            return ApplicationManager.getApplication().getService(ModelSelectionService::class.java)
        }
    }
}
