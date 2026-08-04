package com.billing.pos.data

/**
 * Indian states/UTs with their official GST state code (the first 2 digits of a GSTIN).
 * Used to tell an intra-state sale (CGST+SGST) from an interstate one (IGST): the
 * customer's state name is compared against the 2-digit prefix of the company's own GSTIN.
 */
object IndianStates {
    /** (state/UT name, GST state code) in official code order. */
    val ALL: List<Pair<String, String>> = listOf(
        "Jammu and Kashmir" to "01", "Himachal Pradesh" to "02", "Punjab" to "03",
        "Chandigarh" to "04", "Uttarakhand" to "05", "Haryana" to "06", "Delhi" to "07",
        "Rajasthan" to "08", "Uttar Pradesh" to "09", "Bihar" to "10", "Sikkim" to "11",
        "Arunachal Pradesh" to "12", "Nagaland" to "13", "Manipur" to "14", "Mizoram" to "15",
        "Tripura" to "16", "Meghalaya" to "17", "Assam" to "18", "West Bengal" to "19",
        "Jharkhand" to "20", "Odisha" to "21", "Chhattisgarh" to "22", "Madhya Pradesh" to "23",
        "Gujarat" to "24", "Dadra and Nagar Haveli and Daman and Diu" to "26",
        "Maharashtra" to "27", "Karnataka" to "29", "Goa" to "30", "Lakshadweep" to "31",
        "Kerala" to "32", "Tamil Nadu" to "33", "Puducherry" to "34",
        "Andaman and Nicobar Islands" to "35", "Telangana" to "36", "Andhra Pradesh" to "37",
        "Ladakh" to "38", "Other Territory" to "97"
    )

    val NAMES: List<String> = ALL.map { it.first }
    private val codeByName: Map<String, String> = ALL.toMap()

    fun codeForState(name: String): String = codeByName[name.trim()].orEmpty()

    /** The 2-digit GST state code from the start of a GSTIN, or "" if it doesn't look like one. */
    fun codeFromGstin(gstin: String): String =
        gstin.trim().take(2).let { if (it.length == 2 && it.all(Char::isDigit)) it else "" }
}
