package com.billing.pos.data

/** Decides IGST vs CGST+SGST for a sale, shared by the invoice renderers and the GST exports. */
object GstTax {
    data class Split(val igst: Double, val cgst: Double, val sgst: Double, val interstate: Boolean)

    /** True only when both the company's and the customer's state are known and differ —
     *  an unknown customer state defaults to intra-state (CGST+SGST), same as before this
     *  ever tracked customer state at all. */
    fun isInterstate(companyGstin: String, customerState: String): Boolean {
        val companyCode = IndianStates.codeFromGstin(companyGstin)
        val customerCode = IndianStates.codeForState(customerState)
        return companyCode.isNotBlank() && customerCode.isNotBlank() && companyCode != customerCode
    }

    fun split(taxTotal: Double, companyGstin: String, customerState: String): Split {
        val interstate = isInterstate(companyGstin, customerState)
        return if (interstate) Split(igst = taxTotal, cgst = 0.0, sgst = 0.0, interstate = true)
        else Split(igst = 0.0, cgst = taxTotal / 2.0, sgst = taxTotal / 2.0, interstate = false)
    }
}
