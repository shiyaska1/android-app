package com.billing.pos.print

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.FileOutputStream
import java.io.IOException

/**
 * Prints an already-generated PDF through Android's system Print framework — the same
 * "Print" dialog used by every other app. It discovers WiFi/network printers on its own via
 * whatever print service is installed on the phone (Mopria, HP Smart, Canon, Epson, etc.),
 * so a WiFi laser printer works with no setup or pairing in this app, unlike the Bluetooth
 * thermal path in [ThermalPrinter] which only talks to paired ESC/POS receipt printers.
 */
object SystemPrint {
    fun printPdf(context: Context, uri: Uri, jobName: String) {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        printManager.print(jobName, PdfDocumentAdapter(context, uri, jobName), null)
    }

    private class PdfDocumentAdapter(
        private val context: Context,
        private val uri: Uri,
        private val jobName: String
    ) : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(jobName)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .build()
            callback.onLayoutFinished(info, oldAttributes != newAttributes)
        }

        override fun onWrite(
            pages: Array<out PageRange>,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback
        ) {
            try {
                context.contentResolver.openInputStream(uri).use { input ->
                    if (input == null) {
                        callback.onWriteFailed("Could not open the PDF")
                        return
                    }
                    FileOutputStream(destination.fileDescriptor).use { output ->
                        val buf = ByteArray(8192)
                        while (true) {
                            if (cancellationSignal?.isCanceled == true) {
                                callback.onWriteCancelled()
                                return
                            }
                            val len = input.read(buf)
                            if (len < 0) break
                            output.write(buf, 0, len)
                        }
                    }
                }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: IOException) {
                callback.onWriteFailed(e.message)
            }
        }
    }
}
