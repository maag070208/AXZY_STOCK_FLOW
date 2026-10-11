package dev.axzy.stock.mobile.feature.receiving

import dev.axzy.stock.domain.error.DomainError

/**
 * Why a count was not accepted: the text the operator typed, or the domain's rule. The screen turns it
 * into words with [text]; the ViewModel never holds a sentence.
 */
sealed interface CountError {
    /** The operator typed something that is not a whole number, zero or more. */
    data object InvalidInput : CountError

    /** The domain refused the capture. */
    data class Rejected(val error: DomainError) : CountError
}
