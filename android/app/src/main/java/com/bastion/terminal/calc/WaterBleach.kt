package com.bastion.terminal.calc

import kotlin.math.ceil

/**
 * Household-bleach dose for drinking water, computed in code from the official tables (never by
 * the model). Sources, quoted in the kit:
 *  - EPA "Emergency Disinfection of Drinking Water" (2026): 1 quart/liter 2 drops (6% or 8.25%);
 *    1 gallon 8 drops of 6% or 6 drops of 8.25%; 16 drops = 1/4 tsp; double if cloudy, colored or
 *    very cold; stand 30 minutes.
 *  - CDC "Make Water Safe During an Emergency" (2024): 8 drops of 5%-9% bleach per gallon,
 *    16 drops for cloudy tap water; wait at least 30 minutes.
 * Where the two differ (8.25% bleach) both are shown; the larger dose is listed first.
 */
object WaterBleach {
    enum class Unit(val label: String, val gallons: Double) {
        QUART("quarts", 0.25), LITER("liters", 0.264172), GALLON("gallons", 1.0)
    }
    enum class Strength(val label: String, val epaPerGallon: Int, val cdcPerGallon: Int) {
        SIX("6%", 8, 8), EIGHT_25("8.25%", 6, 8)
    }

    data class Dose(val agency: String, val drops: Int)
    data class Result(
        val doses: List<Dose>,
        /** The EPA table row, word for word, when the volume is one of its rows. */
        val tableRow: String?,
        val notes: List<String>,
    )

    /** EPA table rows (gallons → text per strength), exactly as printed. */
    private val EPA_ROWS = listOf(
        0.25 to ("2 drops" to "2 drops"),
        1.0 to ("8 drops" to "6 drops"),
        2.0 to ("16 drops (1/4 tsp)" to "12 drops (1/8 teaspoon)"),
        4.0 to ("1/3 teaspoon" to "1/4 teaspoon"),
        8.0 to ("2/3 teaspoon" to "1/2 teaspoon"),
    )

    fun dose(amount: Double, unit: Unit, strength: Strength, cloudyOrCold: Boolean): Result? {
        if (!(amount > 0) || amount > 1000) return null
        // EPA lists 1 quart and 1 liter on one row (2 drops); above 1 liter the true volume is used.
        val gallons = if (unit == Unit.LITER && amount <= 1.0) amount * Unit.QUART.gallons else amount * unit.gallons
        val factor = if (cloudyOrCold) 2 else 1
        fun drops(perGallon: Int): Int {
            // EPA: 2 drops for a quart or a liter of either strength; never fewer than that.
            val raw = ceil(gallons * perGallon - 1e-9).toInt().coerceAtLeast(2)
            return raw * factor
        }
        val epa = drops(strength.epaPerGallon)
        val cdc = drops(strength.cdcPerGallon)
        val doses = if (epa == cdc) listOf(Dose("EPA and CDC", epa))
        else listOf(Dose("CDC", cdc), Dose("EPA", epa))

        val exact = EPA_ROWS.firstOrNull { (unit != Unit.LITER || it.first == 0.25) && kotlin.math.abs(it.first - gallons) < 1e-9 }
        val row = exact?.let { (g, t) ->
            val text = if (strength == Strength.SIX) t.first else t.second
            "EPA table, ${rowName(g, unit)}: $text of ${strength.label} bleach" +
                if (cloudyOrCold) " (double it: cloudy, colored or very cold water)" else ""
        }
        val notes = buildList {
            add("Use regular, unscented chlorine bleach only. Not scented, color-safe, or with added cleaners.")
            if (cloudyOrCold) add("Let cloudy water settle, then filter it through a clean cloth, paper towel or coffee filter before adding bleach.")
            add("Stir and let stand at least 30 minutes. It should smell slightly of chlorine; if not, repeat the dose and wait another 15 minutes.")
            add("Bleach does not remove chemicals, heavy metals or salts, and works poorly against Cryptosporidium. Boiling is the best method.")
            if (doses.size > 1) add("EPA and CDC differ for 8.25% bleach: CDC gives 8 drops per gallon for any 5%-9% bleach, EPA gives 6.")
            if (exact == null) add("Computed from the per-gallon rate and rounded up to whole drops; the EPA table has rows for 1 quart/liter and 1, 2, 4 and 8 gallons.")
        }
        return Result(doses, row, notes)
    }

    private fun rowName(g: Double, unit: Unit) = when {
        g == 0.25 -> if (unit == Unit.LITER) "1 liter" else "1 quart"
        g == 1.0 -> "1 gallon"
        else -> "${g.toInt()} gallons"
    }
}
