package com.knoxx.dailyflow.data

/**
 * Who owes whom. Extracted by Gemma alongside the summary (see the DIRECTION
 * field in the extraction prompt) and stored on [MemoryEntity].
 *
 * This drives the pill copy and colour on every commitment card, so it is a
 * first-class field rather than something inferred at render time: "You
 * promised Rahul" and "Rahul asked you" are different obligations, and
 * guessing between them from the summary text would get it wrong exactly when
 * it matters.
 */
enum class Direction {
    /** The owner committed to something. "You promised" — orange. */
    SELF_PROMISE,

    /** Someone asked the owner for something. "{name} asked you" — orange. */
    THEY_ASKED,

    /** Information from someone, no obligation attached. "From {name}". */
    FROM_THEM,

    /** Typed by the owner in AddTaskSheet, never captured. "Added by you". */
    MANUAL;

    /** Whether this represents an open obligation, as opposed to something
     *  merely worth remembering. Only these get the orange treatment and
     *  count toward the morning brief's kept/total ring. */
    val isObligation: Boolean
        get() = this == SELF_PROMISE || this == THEY_ASKED

    /** Card pill copy. [person] is only used where the direction names someone. */
    fun label(person: String?): String = when (this) {
        SELF_PROMISE -> "You promised"
        THEY_ASKED -> person?.let { "$it asked you" } ?: "Asked of you"
        FROM_THEM -> person?.let { "From $it" } ?: "Noted"
        MANUAL -> "Added by you"
    }

    companion object {
        /** Parses the model's DIRECTION line. Unknown or missing values fall
         *  back to [FROM_THEM] — the neutral reading. Inventing an obligation
         *  the model didn't actually assert is the worse failure. */
        fun parse(raw: String?): Direction = when (raw?.trim()?.lowercase()) {
            "self_promise", "self promise", "promise" -> SELF_PROMISE
            "they_asked", "they asked", "asked" -> THEY_ASKED
            "manual" -> MANUAL
            else -> FROM_THEM
        }
    }
}
