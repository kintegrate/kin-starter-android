package com.kin.kin

import android.content.Context
import android.util.Log
import org.kin.sdk.base.KinAccountContext
import org.kin.sdk.base.KinEnvironment
import org.kin.sdk.base.ObservationMode
import org.kin.sdk.base.models.*
import org.kin.sdk.base.models.Invoice
import org.kin.sdk.base.models.KinBinaryMemo
import org.kin.sdk.base.network.services.AppInfoProvider
import org.kin.sdk.base.stellar.models.NetworkEnvironment
import org.kin.sdk.base.storage.KinFileStorage
import org.kin.sdk.base.tools.Base58
import org.kin.sdk.base.tools.DisposeBag
import org.kin.sdk.base.tools.Observer
import org.kin.sdk.base.tools.Optional
import kotlin.reflect.KFunction2


/**
 * Performs operations for a [KinAccount].
 * @param appContext Context object [Context] for the app
 * @param production  Boolean indicating if [NetworkEnvironment] is in production or test
 * @param appIndex App Index assigned by the Kin Foundation
 * @param appAddress Blockchain address for the app in stellarBase32Encoded format
 * @param credentialsUser User id of [AppUserCreds] sent to your webhook for authentication
 * @param credentialsPass Password of [AppUserCreds] sent to your webhook for authentication
 * @param balanceChanged Callback [balanceChanged] to notify the app of balance changes
 * @param paymentHappened  Callback [paymentHappened] to notify the app of balance changes
 */

class Kin(
        private val appContext: Context,
        private val production: Boolean,
        private val appIndex: Int,
        private val appAddress: String,
        private val credentialsUser: String,
        private val credentialsPass: String,
        private val balanceChanged: ((balance: KinBalance) -> Unit)? = null,
        private val paymentHappened: ((payments: List<KinPayment>) -> Unit)? = null
) {
    private val lifecycle = DisposeBag()

    private val environment: KinEnvironment.Agora = getEnvironment()
    private lateinit var context: KinAccountContext
    private var observerPayments: Observer<List<KinPayment>>? = null
    private var observerBalance: Observer<KinBalance>? = null

    init {
        //fetch the account and set the context
        environment.allAccountIds().then {
            //First get (or create) an account id for this device
            val accountId = if (it.count() == 0) {
                createAccount()
            } else {
                it[0].stellarBase32Encode()
            }
            //Then set the context with that single account
            context = getKinContext(accountId)
        }
    }

    init {
        //handle listeners
        balanceChanged?.let {
            watchBalance() //watch for changes in balance
        }

        paymentHappened?.let {
            watchPayments() //watch for changes in balance
        }
    }


    /**
     * Return the device's blockchain address
     */
    fun address(): String = context.accountId.base58Encode()

    /**
     * Force the balance and payment listeners to refresh, to get transactions not initiated by this device
     */
    fun checkTransactions() {
        observerBalance?.requestInvalidation()
        observerPayments?.requestInvalidation()
    }

    /**
     * Sends Kin to the designated address
     * @param paymentItems List of items and costs in a single transaction.
     * @param address  Destination address
     * @param paymentType [KinBinaryMemo.TransferType] of Earn, Spend or P2P (for record keeping)
     * @param paymentSucceeded callback to indicate completion or failure of a payment
     */
    fun sendKin(
            paymentItems: List<Pair<String, Double>>,
            address: String,
            paymentType: KinBinaryMemo.TransferType,
            paymentSucceeded: KFunction2<KinPayment?, Throwable?, Unit>? = null
    ) {
        val kinAccount: KinAccount.Id = kinAccount(address)
        val invoice = buildInvoice(paymentItems)
        val amount = invoiceTotal(paymentItems)

        context.sendKinPayment(
                KinAmount(amount),
                kinAccount,
                buildMemo(invoice, paymentType),
                Optional.of(invoice)
        )
                .then({ payment: KinPayment ->
                    paymentSucceeded?.invoke(payment, null)
                }) { error: Throwable ->
                    paymentSucceeded?.invoke(null, error)
                }
    }

    private fun invoiceTotal(paymentItems: List<Pair<String, Double>>): Double {
        var total = 0.0
        paymentItems.forEach {
            total += it.second
        }

        return total
    }

    private fun buildInvoice(paymentItems: List<Pair<String, Double>>): Invoice {

        val invoiceBuilder = Invoice.Builder()

        paymentItems.forEach {
            invoiceBuilder.addLineItems(
                    listOf(
                            LineItem.Builder(it.first, KinAmount(it.second)).build()
                    )
            )
        }

        return invoiceBuilder.build()
    }

    private fun buildMemo(
            invoice: Invoice,
            transferType: KinBinaryMemo.TransferType
    ): KinMemo {
        val memo = KinBinaryMemo.Builder(appIndex).setTranferType(transferType)
        val invoiceList = InvoiceList.Builder().addInvoice(invoice).build()

        memo.setForeignKey(invoiceList.id.invoiceHash.decode())

        return memo.build().toKinMemo()
    }

    private fun kinAccount(accountId: String): KinAccount.Id {
        //resolve between Solana and Stellar format addresses
        return try {
            KinAccount.Id(Base58.decode(accountId))//Solana format
        } catch (ex: Exception) {
            KinAccount.Id(accountId) //Stellar format
        }
    }

    private fun watchPayments() {
        observerPayments = context.observePayments(ObservationMode.Passive)
                .add { payments: List<KinPayment> ->
                    paymentHappened?.invoke(payments)
                }
                .disposedBy(lifecycle)
    }

    private fun watchBalance() {
        //watch for changes to this account
        observerBalance = context.observeBalance(ObservationMode.Passive)
                .add { kinBalance: KinBalance ->
                    balanceChanged?.invoke(kinBalance)
                }.disposedBy(lifecycle)
    }

    private fun getKinContext(accountId: String): KinAccountContext {
        return KinAccountContext.Builder(environment)
                .useExistingAccount(KinAccount.Id(accountId))
                .build()
    }

    private fun createAccount(): String {
        val kinContext = KinAccountContext.Builder(environment)
                .createNewAccount()
                .build()
        return kinContext.accountId.stellarBase32Encode()
    }

    private fun getEnvironment(): KinEnvironment.Agora {
        val storageLoc = appContext.filesDir.toString() + "/kin"

        val networkEnv: NetworkEnvironment = if (production) {
            NetworkEnvironment.MainNet
        } else {
            NetworkEnvironment.TestNet
        }

        return KinEnvironment.Agora.Builder(networkEnv)
                .setAppInfoProvider(object : AppInfoProvider {
                    override val appInfo: AppInfo =
                            AppInfo(
                                    AppIdx(appIndex),
                                    KinAccount.Id(appAddress),
                                    appContext.applicationInfo.loadLabel(appContext.packageManager).toString(),
                                    R.drawable.app_icon
                            )

                    override fun getPassthroughAppUserCredentials(): AppUserCreds {
                        return AppUserCreds(
                                credentialsUser,
                                credentialsPass
                        )
                    }
                })
                .setStorage(KinFileStorage.Builder(storageLoc))
                .build()
    }
}