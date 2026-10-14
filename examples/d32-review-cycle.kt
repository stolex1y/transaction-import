package d32.review.fixture

/** Returns the integer average; fractional cents are intentionally discarded. */
fun averageCentsPerEntry(totalCents: Long, entryCount: Int): Long =
    totalCents / entryCount
