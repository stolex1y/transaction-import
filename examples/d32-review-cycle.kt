package d32.review.fixture

/** Returns the integer average; fractional cents are intentionally discarded. */
fun averageCentsPerEntry(totalCents: Long, entryCount: Int): Long {
    require(entryCount > 0) { "entryCount must be positive" }
    return totalCents / entryCount
}
