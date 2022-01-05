package com.machiav3lli.backup.items

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.preference.PreferenceManager
import com.machiav3lli.backup.handler.LogsHandler
import com.machiav3lli.backup.handler.ShellHandler
import com.machiav3lli.backup.handler.ShellHandler.Companion.quote
import com.machiav3lli.backup.utils.*
import com.topjohnwu.superuser.ShellUtils
import com.topjohnwu.superuser.io.SuFileInputStream
import com.topjohnwu.superuser.io.SuFileOutputStream
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

// TODO MAYBE migrate at some point to FuckSAF

open class StorageFile {

    var parent: StorageFile? = null
    var context: Context? = null
    var uri: Uri? = null

    var parentFile: RootFile? = null
    var file: RootFile? = null

    constructor(parent: StorageFile?, context: Context?, uri: Uri?) {
        this.parent = parent
        this.context = context
        this.uri = uri
        if (PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("shadowRootFileForSAF", true)
        ) {
            parent ?: run {
                file ?: run {
                    uri?.let { uri ->
                        try {
                            uri.lastPathSegment?.let { last ->
                                if (last.startsWith('/') ?: false) {
                                    file = RootFile(last)
                                    Timber.i("using RootFile shadow tree at $file for SAF $last")
                                } else {
                                    val (storage, path) = last.split(":")
                                    file = RootFile(RootFile("/storage", storage), path)
                                    //file = RootFile(RootFile("/mnt/media_rw", storage), path)
                                    Timber.i("using RootFile shadow tree at $file for SAF $last")
                                }
                            }
                        } catch (e: Throwable) {
                            file = null
                        }
                    }
                }
            }
        }
    }

    constructor(file: RootFile) {
        this.file = file
    }

    constructor(file: File) {
        this.file = RootFile(file)
    }

    constructor(parent: StorageFile, file: RootFile) {
        this.parent = parent
        this.file = file
    }

    constructor(parent: StorageFile, path: String) {
        this.parent = parent
        file = RootFile(parent.file, path)
    }

    var name: String? = null
        get() {
            if (field == null) {
                field = file?.name ?: let {
                    context?.let { context -> uri?.getName(context) }
                }
            }
            return field
        }

    val path: String?
        get() = file?.path ?: uri?.path

    override fun toString(): String {
        return path ?: "null"
    }

    val isFile: Boolean
        get() = file?.isFile ?: context?.let { context -> uri?.isFile(context) } ?: false

    val isDirectory: Boolean
        get() = file?.isDirectory ?: context?.let { context -> uri?.isDirectory(context) } ?: false

    fun exists(): Boolean =
        file?.exists() ?: context?.let { context -> uri?.exists(context) } ?: false

    val isPropertyFile: Boolean
        get() = isFile && name?.endsWith(".properties") ?: false

    val inputStream: InputStream?
        get() {
            return file?.let { file ->
                SuFileInputStream.open(file)
            } ?: uri?.let { uri ->
                context?.contentResolver?.openInputStream(uri)
            }
        }

    val outputStream: OutputStream?
        get() {
            return file?.let { file ->
                SuFileOutputStream.open(file)
            } ?: uri?.let { uri ->
                context?.contentResolver?.openOutputStream(uri, "w")
            }
        }

    fun createDirectory(displayName: String): StorageFile {
        return file?.let {
            val newDir = RootFile(it, displayName)
            newDir.mkdirs()
            return StorageFile(this, newDir)
        } ?: run {
            return StorageFile(this, context!!, createFile(context!!, uri!!, DocumentsContract.Document.MIME_TYPE_DIR, displayName))
        }
    }

    fun createFile(mimeType: String, displayName: String): StorageFile {
        return file?.let {
            if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                val newDir = RootFile(it, displayName)
                newDir.mkdirs()
                return StorageFile(this, newDir)
            } else {
                val newFile = RootFile(it, displayName)
                newFile.createNewFile()
                return StorageFile(this, newFile)
            }
        } ?: run {
            StorageFile(this, context, createFile(context!!, uri!!, mimeType, displayName))
        }
    }

    fun delete(): Boolean {
        return try {
            file?.let {
                //it.delete()  does not work, becaujse using "rmdir -f"
                ShellUtils.fastCmdResult("rm -f ${quote(it)} || rmdir ${quote(it)}")
            } ?: DocumentsContract.deleteDocument(context!!.contentResolver, uri!!)
        } catch (e: FileNotFoundException) {
            false
        } catch (e: Throwable) {
            LogsHandler.unhandledException(e, uri)
            false
        }
    }

    fun renameTo(displayName: String): Boolean {
        var ok = false
        file?.let { oldFile ->
            val newFile = RootFile(oldFile.parent, displayName)
            ok = oldFile.renameTo(newFile)
            file = newFile
        } ?: try {
            val result =
                context?.let { context ->
                    uri?.let { uri ->
                        DocumentsContract.renameDocument(
                            context.contentResolver, uri, displayName
                        )
                    }
                }
            if (result != null) {
                uri = result
                ok = true
            }
        } catch (e: Throwable) {
            LogsHandler.unhandledException(e, uri)
            ok = false
        }
        return ok
    }

    fun findFile(displayName: String): StorageFile? {
        try {
            file?.let {
                var found = StorageFile(this, displayName)
                return if (found.exists()) found else null
            }
            for (file in listFiles()) {
                if (displayName == file.name) {
                    return file
                }
            }
        } catch (e: FileNotFoundException) {
        } catch (e: Throwable) {
            LogsHandler.unhandledException(e, uri)
        }
        return null
    }

    fun recursiveCopyFiles(files: List<ShellHandler.FileInfo>) {
        suRecursiveCopyFilesToDocument(context!!, files, uri!!)
    }

    @Throws(FileNotFoundException::class)
    fun listFiles(): List<StorageFile> {
        try {
            exists()
        } catch (e: Throwable) {
            throw FileNotFoundException("File $uri does not exist")
        }
        checkCache()
        val id = path
        id ?: return listOf()

        fileListCache[id] ?: run {
            file?.let { dir ->
                fileListCache[id] = dir.listFiles()?.map { child ->
                    StorageFile(this, child)
                }?.toList()
            } ?: run {
                context?.contentResolver?.let { resolver ->
                    val childrenUri = try {
                        DocumentsContract.buildChildDocumentsUriUsingTree(
                            this.uri,
                            DocumentsContract.getDocumentId(this.uri)
                        )
                    } catch (e: IllegalArgumentException) {
                        return listOf()
                    }
                    val results = ArrayList<Uri>()
                    var cursor: Cursor? = null
                    try {
                        cursor = resolver.query(
                            childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                            null, null, null
                        )
                        var documentUri: Uri
                        while (cursor?.moveToNext() == true) {
                            documentUri =
                                DocumentsContract.buildDocumentUriUsingTree(
                                    this.uri,
                                    cursor.getString(0)
                                )
                            results.add(documentUri)
                        }
                    } catch (e: Throwable) {
                        LogsHandler.unhandledException(e, uri)
                    } finally {
                        closeQuietly(cursor)
                    }
                    fileListCache[id] = results.map { uri ->
                        StorageFile(this, context, uri)
                    }
                }
            }
        }
        return fileListCache[id] ?: listOf()
    }

    fun ensureDirectory(dirName: String): StorageFile {
        return findFile(dirName)
            ?: createDirectory(dirName)
    }

    fun deleteRecursive(): Boolean = when {
        isFile ->
            delete()
        isDirectory -> try {
            val contents = listFiles()
            var result = true
            contents.forEach { file ->
                result = result && file.deleteRecursive()
            }
            if (result)
                delete()
            else
                result
        } catch (e: FileNotFoundException) {
            false
        } catch (e: Throwable) {
            LogsHandler.unhandledException(e, uri)
            false
        }
        else -> false
    }

    companion object {
        val fileListCache: MutableMap<String, List<StorageFile>?> = mutableMapOf()
        val uriStorageFileCache: MutableMap<String, StorageFile> = mutableMapOf()
        var cacheDirty = true

        fun fromUri(context: Context, uri: Uri): StorageFile {
            // Todo: Figure out what's wrong with the Uris coming from the intent and why they need to be processed
            //  with DocumentsContract.buildDocumentUriUsingTree(value, DocumentsContract.getTreeDocumentId(value)) first
            checkCache()
            val id = uri.toString()
            return uriStorageFileCache[id] ?:
                StorageFile(
                    null,
                    context,
                    uri
                ).also { uriStorageFileCache[id] = it }
        }

        fun createFile(context: Context, uri: Uri, mimeType: String, displayName: String): Uri? {
            return try {
                DocumentsContract.createDocument(
                    context.contentResolver,
                    uri,
                    mimeType,
                    displayName
                )
            } catch (e: FileNotFoundException) {
                null
            } catch (e: Throwable) {
                LogsHandler.unhandledException(e, uri)
                null
            }
        }

        fun invalidateCache() {
            cacheDirty = true
        }

        fun checkCache() {
            if (cacheDirty) {
                fileListCache.clear()
                uriStorageFileCache.clear()
                cacheDirty = false
            }
        }

        private fun closeQuietly(closeable: AutoCloseable?) {
            if (closeable != null) {
                try {
                    closeable.close()
                } catch (rethrown: RuntimeException) {
                    // noinspection ProhibitedExceptionThrown
                    throw rethrown
                } catch (e: Throwable) {
                    LogsHandler.unhandledException(e)
                }
            }
        }
    }
}
