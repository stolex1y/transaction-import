package d32.review.fixture

fun averageCentsPerOrder(totalCents: Long, orderCount: Int): Long? =
    if (orderCount == 0) null else totalCents / orderCount

fun firstItemOrNull(items: List<String>): String? =
    items[0]

fun formatCents(cents: Long): String =
    "${cents / 100}.${cents % 100}"

fun main() {
    check(averageCentsPerOrder(100, 0) == null)
    check(firstItemOrNull(emptyList()) == null)
    check(formatCents(5) == "0.05")
}
