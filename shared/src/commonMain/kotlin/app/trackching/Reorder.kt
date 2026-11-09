package app.trackching

/** A copy of the list with the item at `from` moved to `to`. */
fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from == to) return this
    val copy = toMutableList()
    val item = copy.removeAt(from)
    copy.add(to, item)
    return copy
}
