/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * This Source Code Form is "Incompatible With Secondary Licenses", as
 * defined by the Mozilla Public License, v. 2.0.
 */
package org.epilink.bot.ratelimiting

import java.time.Instant

/**
 * Represents a single rate limit state. Immutable.
 */
data class Rate(
    /**
     * The number of remaining requests + 1. So, if this is 1, then this is the last allowed request and the next
     * request will trigger a rate limit.
     */
    val remainingRequests: Long,
    /**
     * The instant at which the rate limit is invalid and reset.
     */
    val resetAt: Instant
)

/**
 * Returns true if the rate has expired (we have passed its reset instant)
 */
fun Rate.hasExpired() = resetAt < Instant.now()

/**
 * Consumes a single request and returns the modified rate.
 */
fun Rate.consume() = copy(remainingRequests = (remainingRequests - 1).coerceAtLeast(0))

/**
 * Returns true if the 429 HTTP error should be returned, as in, this rate has not expired and there are no remaining
 * requests
 */
fun Rate.shouldLimit() = !hasExpired() && remainingRequests == 0L