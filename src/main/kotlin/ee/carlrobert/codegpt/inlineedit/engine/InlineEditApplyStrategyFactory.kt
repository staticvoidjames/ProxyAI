package ee.carlrobert.codegpt.inlineedit.engine

object InlineEditApplyStrategyFactory {
    fun get(): ApplyStrategy {
        return SearchReplaceApplyStrategy()
    }
}
