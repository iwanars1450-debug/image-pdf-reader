package jp.local.imagepdf

import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

class ShareProvider: ContentProvider() {
    override fun onCreate()=true
    private fun file(uri: Uri): File { val id=uri.pathSegments.firstOrNull()?:throw IllegalArgumentException();require(Regex("[a-fA-F0-9-]{36}").matches(id));return File(context!!.filesDir,"pdfs/$id.pdf") }
    override fun getType(uri: Uri)="application/pdf"
    override fun openFile(uri: Uri,mode: String): ParcelFileDescriptor { require(mode=="r");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY) }
    override fun query(uri: Uri,projection: Array<out String>?,selection: String?,args: Array<out String>?,sort: String?): Cursor {
        val columns=projection?:arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { when(it) { OpenableColumns.DISPLAY_NAME -> uri.pathSegments.getOrNull(1)?:"document.pdf";OpenableColumns.SIZE -> file(uri).length();else -> null } }.toTypedArray()) }
    }
    override fun insert(uri: Uri,values: ContentValues?): Uri?=throw UnsupportedOperationException()
    override fun update(uri: Uri,values: ContentValues?,selection: String?,args: Array<out String>?)=throw UnsupportedOperationException()
    override fun delete(uri: Uri,selection: String?,args: Array<out String>?)=throw UnsupportedOperationException()
}
