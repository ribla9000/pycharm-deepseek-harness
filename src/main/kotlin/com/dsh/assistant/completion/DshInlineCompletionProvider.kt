package com.dsh.assistant.completion

import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionVariant
import com.intellij.openapi.util.UserDataHolderBase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Provider of DSH gray inline suggestions.
 *
 * The platform's stock Tab accepts a suggestion; Esc hides it, after which the service
 * repeats the request after a configured delay. Registered with `order="first"` so that it
 * wins over the full-line provider.
 */
class DshInlineCompletionProvider : InlineCompletionProvider {

    override val id: InlineCompletionProviderID = InlineCompletionProviderID("dsh-tab")

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        val request = try {
            event.toRequest()
        } catch (e: Exception) {
            null
        } ?: return false
        val project = request.file.project
        if (project.isDisposed) return false
        // A suggestion cannot be inserted in viewers/read-only editors.
        if (request.editor.isViewer || !request.document.isWritable) return false
        val service = project.getService(DshInlineCompletionService::class.java) ?: return false
        if (!service.isEnabled()) return false
        // After Esc, auto-suggestions stay silent until a manual retry — the user can type in peace.
        val manual = event is InlineCompletionEvent.DirectCall ||
            event is InlineCompletionEvent.ManualCall
        if (!manual && service.isSuppressed()) return false
        return true
    }

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val project = request.file.project
        if (project.isDisposed) return InlineCompletionSuggestion.Empty
        val service = project.getService(DshInlineCompletionService::class.java)
            ?: return InlineCompletionSuggestion.Empty
        val text = service.suggestion(request) ?: return InlineCompletionSuggestion.Empty
        return SingleVariantSuggestion(text)
    }

    private class SingleVariantSuggestion(private val text: String) : InlineCompletionSuggestion {
        override suspend fun getVariants(): List<InlineCompletionVariant> =
            listOf(SingleVariant(text))
    }

    private class SingleVariant(private val text: String) : InlineCompletionVariant {
        override val data: UserDataHolderBase = UserDataHolderBase()
        override val elements: Flow<InlineCompletionElement> =
            flowOf(InlineCompletionGrayTextElement(text))
    }
}
