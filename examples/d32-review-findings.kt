package d32.review.fixture

fun averageCentsPerOrder(totalCents: Long, orderCount: Int): Long =
    totalCents / orderCount

fun firstItemOrNull(items: List<String>): String? =
    items[0]

fun formatCents(cents: Long): String =
    "${cents / 100}.${cents % 100}"
