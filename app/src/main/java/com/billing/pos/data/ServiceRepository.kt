package com.billing.pos.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class ServiceRepository(context: Context) {
    private val dao = AppDatabase.get(context).serviceDao()
    private val repo = Repository(context)

    val types: Flow<List<ServiceType>> = dao.types()
    val statuses: Flow<List<ServiceStatus>> = dao.statuses()
    val jobs: Flow<List<ServiceJobMaster>> = dao.jobs()
    val cards: Flow<List<ServiceJobCard>> = dao.cards()
    val allLines: Flow<List<ServiceJobLine>> = dao.allLines()

    /** Seeds the status master the first time the module is opened. */
    suspend fun ensureDefaults() {
        if (dao.statusCount() == 0) {
            listOf("Pending", "Partial Complete", "Completed", "Rejected")
                .forEach { dao.upsertStatus(ServiceStatus(name = it)) }
        }
    }

    // masters
    suspend fun saveType(t: ServiceType) { if (t.name.isNotBlank()) dao.upsertType(t.copy(name = t.name.trim())) }
    suspend fun deleteType(t: ServiceType) = dao.deleteType(t)
    suspend fun saveStatus(s: ServiceStatus) { if (s.name.isNotBlank()) dao.upsertStatus(s.copy(name = s.name.trim())) }
    suspend fun deleteStatus(s: ServiceStatus) = dao.deleteStatus(s)
    suspend fun saveJob(j: ServiceJobMaster) { if (j.name.isNotBlank()) dao.upsertJob(j.copy(name = j.name.trim())) }
    suspend fun deleteJob(j: ServiceJobMaster) = dao.deleteJob(j)

    // job cards
    suspend fun nextJobNo(): String = "JC-" + (dao.cardCount() + 1).toString().padStart(4, '0')
    suspend fun cardById(id: Long): ServiceJobCard? = dao.cardById(id)
    suspend fun linesFor(id: Long): List<ServiceJobLine> = dao.linesFor(id)
    suspend fun saveCard(c: ServiceJobCard, lines: List<ServiceJobLine>): Long = dao.save(c, lines)
    suspend fun updateCard(c: ServiceJobCard, lines: List<ServiceJobLine>) = dao.update(c, lines)
    suspend fun setCardStatus(id: Long, status: String) = dao.setCardStatus(id, status)

    suspend fun deleteCard(c: ServiceJobCard) {
        dao.attachmentsFor(c.id).forEach { ServiceAttachmentStore.delete(it) }
        dao.delete(c)
    }

    // attachments — rewritten wholesale after a save, when the card id is known
    suspend fun attachmentsFor(cardId: Long): List<ServiceJobAttachment> = dao.attachmentsFor(cardId)
    suspend fun replaceAttachments(cardId: Long, list: List<ServiceJobAttachment>) {
        val keep = list.map { it.path }.toSet()
        dao.attachmentsFor(cardId).filter { it.path !in keep }.forEach { ServiceAttachmentStore.delete(it) }
        dao.deleteAttachmentsFor(cardId)
        dao.insertAttachments(list.map { it.copy(id = 0, cardId = cardId) })
    }

    // customers (shared master)
    val customers: Flow<List<Customer>> = repo.customers
    suspend fun addCustomer(name: String, phone: String, address: String): Customer {
        val id = repo.addCustomer(name, phone, address)
        return Customer(id = id, name = name.trim(), phone = phone.trim(), address = address.trim())
    }
}
