package dev.axzy.stock.mobile.feature.receiving

import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.mobile.core.network.ApiFailure

/**
 * Why a count was not accepted: the text the operator typed, the domain's rule, or the API's answer. The screen
 * turns it into words with [text]; the ViewModel never holds a sentence.
 */
sealed interface CountError {
    /** The operator typed something that is not a whole number, zero or more. */
    data object InvalidInput : CountError

    /** The domain refused the capture, before anything was sent. */
    data class Rejected(val error: DomainError) : CountError

    /** The API did not record the count: it refused it, or it did not answer. */
    data class Failed(val failure: ApiFailure) : CountError
}
