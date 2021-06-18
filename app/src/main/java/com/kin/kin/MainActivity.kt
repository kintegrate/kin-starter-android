package com.kin.kin

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import org.kin.sdk.base.models.KinBalance
import org.kin.sdk.base.models.KinBinaryMemo
import org.kin.sdk.base.models.KinPayment
import java.sql.Timestamp
import java.util.*
import kotlin.concurrent.schedule

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        /*Initialize Kin*/
        val kin = Kin(
            applicationContext,
            false,
            165,
            "GC6D6TCMFYRTQEECH4FPAS2DFUECCF3KTCXOIYE4NEX2NIDAKQNJ32XS",
            "MyUser",
            "MyPass",
            ::balanceChanged,
            ::paymentHappened
        )


        /*Show the device's address to receive payments*/
        findViewById<TextView>(R.id.address).text =
            "Address: " + kin.address() //Device's address to receive payments


        /*Send out a payment*/
        val btnSendKin: Button = findViewById<Button>(R.id.sendKin)
        btnSendKin.setOnClickListener {
            findViewById<TextView>(R.id.messages).text = " Sending Kin"

            //create a list to hold items being paid for
            var paymentItems = mutableListOf<Pair<String, Double>>()
            paymentItems.add(Pair("One Hamburger", 2.00))
            paymentItems.add(Pair("Tip the waitress", 0.50))

            /*Send a single transaction containing the payment items*/
            kin.sendKin(
                paymentItems,
                "Don8L4DTVrUrRAcVTsFoCRqei5Mokde3CV3K9Ut4nAGZ",
                KinBinaryMemo.TransferType.Spend,
                ::sentKin
            )
        }

        /*The balance and payment listeners activate whenever this app sends Kin
        * However, you can periodically force them to check for transactions that happened
        * I.e. someone sent it Kin
        **/
        val btnRefreshBalance: Button = findViewById<Button>(R.id.refresh)
        btnRefreshBalance.setOnClickListener {
            //NOTE: THERE IS A LAG BETWEEN RECEIVING A TRANSACTION AND THE LISTENERS DETECTING IT. (seconds to minutes)
            //Calling this too soon will give you THE WRONG BALANCE
            //So check approx 60 seconds after expecting a transaction
            //If you send a payment and force refresh too soon, you will get 'old' balance information
            kin.checkTransactions()
            findViewById<TextView>(R.id.messages).text = "Balance refreshed"
        }
    }

    private fun balanceChanged(balance: KinBalance) {
        //Use this to update your user's current balance
        findViewById<TextView>(R.id.balance).text =
            "Balance:" + balance.amount.toString() + " Kin"
    }

    private fun paymentHappened(payments: List<KinPayment>) {
        //Use this to know when a payment has taken place
        findViewById<TextView>(R.id.payments).text =
            "Last Payment: " + Date(Timestamp(payments.first().timestamp).time).toString()
    }

    private fun sentKin(payment: KinPayment?, error: Throwable?) {
        //Use this to know when the payment is sent
        if (error == null) {
            findViewById<TextView>(R.id.messages).text = "Sent Kin"
        } else {
            findViewById<TextView>(R.id.messages).text = "Error sending Kin"
        }
    }
}