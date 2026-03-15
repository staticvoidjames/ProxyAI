package ee.carlrobert.codegpt.ui.textarea.lookup.action

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.header.tag.WebTagDetails

class WebActionItem(private val tagManager: TagManager) : AbstractLookupActionItem() {

    override val displayName: String =
        CodeGPTBundle.get("suggestionActionItem.webSearch.displayName")
    override val icon = AllIcons.General.Web
    override val enabled: Boolean
        get() = false

    override fun execute(project: Project, userInputPanel: UserInputPanel) {
        userInputPanel.addTag(WebTagDetails())
    }
}