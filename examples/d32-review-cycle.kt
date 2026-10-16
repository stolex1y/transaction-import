package d32.review.fixture

/** Returns the integer average for positive entry counts; fractional cents are discarded. */
fun averageCentsPerEntry(totalCents: Long, entryCount: Int): Long {
    require(entryCount > 0) { "entryCount must be positive" }
    return totalCents / entryCount
}
