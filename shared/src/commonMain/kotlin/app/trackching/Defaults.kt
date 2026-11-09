package app.trackching

import app.trackching.db.TrackchingDatabase

/** A category offered during setup. Its subcategories come with it. */
class StarterCategory(
    val name: String,
    val kind: String, // "EXPENSE" or "INCOME"
    val color: Long,
    val subs: List<Pair<String, Long>> = emptyList(),
)

/** Everything setup offers, expenses first. All ticked to start with. */
val StarterCategories: List<StarterCategory> = listOf(
    StarterCategory(
        "Food & Beverages", "EXPENSE", 0xFFE07A00,
        listOf("Meals" to 0xFFE07A00, "Coffee" to 0xFFA0662B, "Snacks" to 0xFFC99700, "Groceries" to 0xFF3F9A33),
    ),
    StarterCategory(
        "Transportation", "EXPENSE", 0xFF1663FF,
        listOf("Jeep & Bus" to 0xFF1663FF, "Taxi & Grab" to 0xFF1D9BB3),
    ),
    StarterCategory(
        "Bills & Utilities", "EXPENSE", 0xFF7B5CD6,
        listOf("Mobile load" to 0xFF7B5CD6),
    ),
    StarterCategory("Salary", "INCOME", 0xFF1B8A3A),
    StarterCategory("Allowance", "INCOME", 0xFF3A9D6B),
    StarterCategory(
        "Side income", "INCOME", 0xFF2C8F8F,
        listOf("Freelance" to 0xFF2C8F8F, "Selling" to 0xFFC0569A),
    ),
    StarterCategory("Gifts received", "INCOME", 0xFFB0478A),
    StarterCategory(
        "Interest", "INCOME", 0xFF4D7CC9,
        listOf("Bank interest" to 0xFF4D7CC9, "From IOUs" to 0xFF6B93D6),
    ),
    StarterCategory("Other", "INCOME", 0xFF5C616C),
)

/** Adds the given starter categories and their subcategories, in list order. */
fun TrackchingDatabase.seedCategories(picked: List<StarterCategory>) {
    transaction {
        var order = 0L
        picked.forEach { main ->
            categoryQueries.insert(main.name, main.kind, null, main.color, order++)
            val parentId = categoryQueries.lastId().executeAsOne()
            main.subs.forEach { (name, color) ->
                categoryQueries.insert(name, main.kind, parentId, color, order++)
            }
        }
    }
}

/** Everything at once: "Cash on hand" and every starter category. Tests use it; the app uses setup. */
fun TrackchingDatabase.seedDefaults() {
    transaction {
        if (accountQueries.countAll().executeAsOne() == 0L) {
            accountQueries.insert("Cash on hand", 0)
        }
        if (categoryQueries.countAll().executeAsOne() == 0L) {
            seedCategories(StarterCategories)
        }
    }
}
