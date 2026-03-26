package dev.spikeysanju.expensetracker.repo

import dev.spikeysanju.expensetracker.data.local.AppDatabase
import dev.spikeysanju.expensetracker.model.Account
import javax.inject.Inject

class AccountRepo @Inject constructor(private val db: AppDatabase) {
    suspend fun deleteAll() = db.getAccountDao().deleteAllAccounts()

    // insert account
    suspend fun insert(account: Account) = db.getAccountDao().insertAccount(account)

    // update account
    suspend fun update(account: Account) = db.getAccountDao().updateAccount(account)

    // delete account
    suspend fun delete(account: Account) = db.getAccountDao().deleteAccount(account)

    // get all accounts
    fun getAllAccounts() = db.getAccountDao().getAllAccounts()

    // get account by ID
    fun getByID(id: Int) = db.getAccountDao().getAccountByID(id)

    // get account by ID synchronously
    suspend fun getByIDSync(id: Int) = db.getAccountDao().getAccountByIDSync(id)
}
