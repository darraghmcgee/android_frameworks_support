/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.support.provider;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import android.util.Log;
import android.webkit.MimeTypeMap;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.IllegalArgumentException;
import java.lang.IllegalStateException;
import java.lang.UnsupportedOperationException;
import java.util.Collections;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Provides basic implementation for creating, extracting and accessing
 * files within archives exposed by a document provider. The id delimiter
 * must be a character which is not used in document ids generated by the
 * document provider.
 *
 * <p>This class is thread safe.
 *
 * @hide
 */
public class DocumentArchive implements Closeable {
    private static final String TAG = "DocumentArchive";

    private static final String[] DEFAULT_PROJECTION = new String[] {
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE
    };

    private final Context mContext;
    private final String mDocumentId;
    private final char mIdDelimiter;
    private final Uri mNotificationUri;
    private final ZipFile mZipFile;
    private final ExecutorService mExecutor;

    private DocumentArchive(
            Context context,
            File file,
            String documentId,
            char idDelimiter,
            Uri notificationUri)
            throws IOException {
        mContext = context;
        mDocumentId = documentId;
        mIdDelimiter = idDelimiter;
        mNotificationUri = notificationUri;
        mZipFile = new ZipFile(file);
        mExecutor = Executors.newSingleThreadExecutor();
    }

    /**
     * Creates a DocumentsArchive instance for opening, browsing and accessing
     * documents within the archive passed as a local file.
     *
     * @param context Context of the provider.
     * @param File Local file containing the archive.
     * @param documentId ID of the archive document.
     * @param idDelimiter Delimiter for constructing IDs of documents within the archive.
     *            The delimiter must never be used for IDs of other documents.
     * @param Uri notificationUri Uri for notifying that the archive file has changed.
     * @see createForParcelFileDescriptor(DocumentsProvider, ParcelFileDescriptor, String, char,
     *          Uri)
     */
    public static DocumentArchive createForLocalFile(
            Context context, File file, String documentId, char idDelimiter, Uri notificationUri)
            throws IOException {
        return new DocumentArchive(context, file, documentId, idDelimiter, notificationUri);
    }

    /**
     * Creates a DocumentsArchive instance for opening, browsing and accessing
     * documents within the archive passed as a file descriptor.
     *
     * <p>Note, that this method should be used only if the document does not exist
     * on the local storage. A snapshot file will be created, which may be slower
     * and consume significant resources, in contrast to using
     * {@see createForLocalFile(Context, File, String, char, Uri}.
     *
     * @param context Context of the provider.
     * @param descriptor File descriptor for the archive's contents.
     * @param documentId ID of the archive document.
     * @param idDelimiter Delimiter for constructing IDs of documents within the archive.
     *            The delimiter must never be used for IDs of other documents.
     * @param Uri notificationUri Uri for notifying that the archive file has changed.
     * @see createForLocalFile(Context, File, String, char, Uri)
     */
    public static DocumentArchive createForParcelFileDescriptor(
            Context context, ParcelFileDescriptor descriptor, String documentId,
            char idDelimiter, Uri notificationUri)
            throws IOException {
        File snapshotFile = null;
        try {
            // Create a copy of the archive, as ZipFile doesn't operate on streams.
            // Moreover, ZipInputStream would be inefficient for large files on
            // pipes.
            snapshotFile = File.createTempFile("android.support.provider.snapshot{",
                    "}.zip", context.getCacheDir());

            try (
                final FileOutputStream outputStream =
                        new ParcelFileDescriptor.AutoCloseOutputStream(
                                ParcelFileDescriptor.open(
                                        snapshotFile, ParcelFileDescriptor.MODE_WRITE_ONLY));
                final ParcelFileDescriptor.AutoCloseInputStream inputStream =
                        new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
            ) {
                final byte[] buffer = new byte[32 * 1024];
                int bytes;
                while ((bytes = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytes);
                }
                outputStream.flush();
                return new DocumentArchive(context, snapshotFile, documentId, idDelimiter,
                        notificationUri);
            }
        } finally {
            // On UNIX the file will be still available for processes which opened it, even
            // after deleting it. Remove it ASAP, as it won't be used by anyone else.
            if (snapshotFile != null) {
                snapshotFile.delete();
            }
        }
    }

    /**
     * Lists child documents of an archive or a directory within an
     * archive. Must be called only for archives with supported mime type,
     * or for documents within archives.
     *
     * @see DocumentsProvider.queryChildDocuments(String, String[], String)
     */
    public Cursor queryChildDocuments(String documentId, String[] projection, String sortOrder) {
        final ParsedDocumentId parsedParentId = ParsedDocumentId.fromDocumentId(
                documentId, mIdDelimiter);
        Preconditions.checkArgumentEquals(mDocumentId, parsedParentId.mArchiveId,
                "Mismatching document ID. Expected: %s, actual: %s.");

        final String parentPath = parsedParentId.mPath != null ? normalizePath(
                parsedParentId.mPath, true /* isDirectory */) : "/";
        final MatrixCursor result = new MatrixCursor(
                projection != null ? projection : DEFAULT_PROJECTION);
        if (mNotificationUri != null) {
            result.setNotificationUri(mContext.getContentResolver(), mNotificationUri);
        }

        File file;
        String maybeParentPath;
        // TODO: Build an in-memory tree for storing the directory structure.
        for (final ZipEntry entry : Collections.list(mZipFile.entries())) {
            file = new File(getPathForEntry(entry));
            maybeParentPath = normalizePath(file.getParent(), true /* isDirectory */);
            if (maybeParentPath.equals(parentPath)) {
                addCursorRow(result, entry);
            }
        }
        return result;
    }

    /**
     * Returns a MIME type of a document within an archive.
     *
     * @see DocumentsProvider.getDocumentType(String)
     */
    public String getDocumentType(String documentId) throws FileNotFoundException {
        final ParsedDocumentId parsedId = ParsedDocumentId.fromDocumentId(
                documentId, mIdDelimiter);
        Preconditions.checkArgumentEquals(mDocumentId, parsedId.mArchiveId,
                "Mismatching document ID. Expected: %s, actual: %s.");
        Preconditions.checkArgumentNotNull(parsedId.mPath, "Not a document within an archive.");

        final ZipEntry entry = mZipFile.getEntry(parsedId.mPath);
        if (entry == null) {
            throw new FileNotFoundException();
        }
        return getMimeTypeForEntry(entry);
    }

    /**
     * Returns true if a document within an archive is a child or any descendant of the archive
     * document or another document within the archive.
     *
     * @see DocumentsProvider.isChildDocument(String, String)
     */
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        final ParsedDocumentId parsedParentId = ParsedDocumentId.fromDocumentId(
                parentDocumentId, mIdDelimiter);
        final ParsedDocumentId parsedId = ParsedDocumentId.fromDocumentId(
                documentId, mIdDelimiter);
        Preconditions.checkArgumentEquals(mDocumentId, parsedParentId.mArchiveId,
                "Mismatching document ID. Expected: %s, actual: %s.");
        Preconditions.checkArgumentNotNull(parsedId.mPath,
                "Not a document within an archive.");

        final ZipEntry entry = mZipFile.getEntry(parsedId.mPath);
        if (entry == null) {
            return false;
        }

        if (parsedParentId.mPath == null) {
            // No need to compare paths. Every file in the archive is a child of the archive
            // file.
            return true;
        }

        final ZipEntry parentEntry = mZipFile.getEntry(parsedParentId.mPath);
        if (parentEntry == null || !parentEntry.isDirectory()) {
            return false;
        }

        final String parentPath = getPathForEntry(parentEntry);

        // Add a trailing slash even if it's not a directory, so it's easy to check if the
        // entry is a descendant.
        final String pathWithSlash = normalizePath(getPathForEntry(entry), true /* isDirectory */);
        return pathWithSlash.startsWith(parentPath) && !parentPath.equals(pathWithSlash);
    }

    /**
     * Returns metadata of a document within an archive.
     *
     * @see DocumentsProvider.queryDocument(String, String[])
     */
    public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        final ParsedDocumentId parsedId = ParsedDocumentId.fromDocumentId(
                documentId, mIdDelimiter);
        Preconditions.checkArgumentEquals(mDocumentId, parsedId.mArchiveId,
                "Mismatching document ID. Expected: %s, actual: %s.");
        Preconditions.checkArgumentNotNull(parsedId.mPath, "Not a document within an archive.");

        final ZipEntry entry = mZipFile.getEntry(parsedId.mPath);
        if (entry == null) {
            throw new FileNotFoundException();
        }

        final MatrixCursor result = new MatrixCursor(
                projection != null ? projection : DEFAULT_PROJECTION);
        if (mNotificationUri != null) {
            result.setNotificationUri(mContext.getContentResolver(), mNotificationUri);
        }
        addCursorRow(result, entry);
        return result;
    }

    /**
     * Opens a file within an archive.
     *
     * @see DocumentsProvider.openDocument(String, String, CancellationSignal))
     */
    public ParcelFileDescriptor openDocument(
            String documentId, String mode, final CancellationSignal signal)
            throws FileNotFoundException {
        Preconditions.checkArgumentEquals("r", mode,
                "Invalid mode. Only reading \"r\" supported, but got: \"%s\".");
        final ParsedDocumentId parsedId = ParsedDocumentId.fromDocumentId(
                documentId, mIdDelimiter);
        Preconditions.checkArgumentEquals(mDocumentId, parsedId.mArchiveId,
                "Mismatching document ID. Expected: %s, actual: %s.");
        Preconditions.checkArgumentNotNull(parsedId.mPath, "Not a document within an archive.");

        final ZipEntry entry = mZipFile.getEntry(parsedId.mPath);
        if (entry == null) {
            throw new FileNotFoundException();
        }

        ParcelFileDescriptor[] pipe;
        InputStream inputStream = null;
        try {
            pipe = ParcelFileDescriptor.createReliablePipe();
            inputStream = mZipFile.getInputStream(entry);
        } catch (IOException e) {
            if (inputStream != null) {
                IoUtils.closeQuietly(inputStream);
            }
            // Ideally we'd simply throw IOException to the caller, but for consistency
            // with DocumentsProvider::openDocument, converting it to IllegalStateException.
            throw new IllegalStateException("Failed to open the document.", e);
        }
        final ParcelFileDescriptor outputPipe = pipe[1];
        final InputStream finalInputStream = inputStream;
        mExecutor.execute(
                new Runnable() {
                    @Override
                    public void run() {
                        try (final ParcelFileDescriptor.AutoCloseOutputStream outputStream =
                                new ParcelFileDescriptor.AutoCloseOutputStream(outputPipe)) {
                            try {
                                final byte buffer[] = new byte[32 * 1024];
                                int bytes;
                                while ((bytes = finalInputStream.read(buffer)) != -1) {
                                    if (Thread.interrupted()) {
                                        throw new InterruptedException();
                                    }
                                    if (signal != null) {
                                        signal.throwIfCanceled();
                                    }
                                    outputStream.write(buffer, 0, bytes);
                                }
                            } catch (IOException | InterruptedException e) {
                                // Catch the exception before the outer try-with-resource closes the
                                // pipe with close() instead of closeWithError().
                                try {
                                    outputPipe.closeWithError(e.getMessage());
                                } catch (IOException e2) {
                                    Log.e(TAG, "Failed to close the pipe after an error.", e2);
                                }
                            }
                        } catch (IOException e) {
                            Log.e(TAG, "Failed to close the output stream gracefully.", e);
                        } finally {
                            IoUtils.closeQuietly(finalInputStream);
                        }
                    }
                });

        return pipe[0];
    }

    /**
     * Schedules a gracefully close of the archive after any opened files are closed.
     *
     * <p>This method does not block until shutdown. Once called, other methods should not be
     * called.
     */
    @Override
    public void close() {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                IoUtils.closeQuietly(mZipFile);
            }
        });
        mExecutor.shutdown();
    }

    private void addCursorRow(MatrixCursor cursor, ZipEntry entry) {
        final MatrixCursor.RowBuilder row = cursor.newRow();
        final ParsedDocumentId parsedId = new ParsedDocumentId(mDocumentId, entry.getName());
        row.add(Document.COLUMN_DOCUMENT_ID, parsedId.toDocumentId(mIdDelimiter));
        final File file = new File(entry.getName());
        row.add(Document.COLUMN_DISPLAY_NAME, file.getName());
        row.add(Document.COLUMN_SIZE, entry.getSize());
        row.add(Document.COLUMN_MIME_TYPE, getMimeTypeForEntry(entry));
    }

    private String getMimeTypeForEntry(ZipEntry entry) {
        if (entry.isDirectory()) {
            return Document.MIME_TYPE_DIR;
        }

        final int lastDot = entry.getName().lastIndexOf('.');
        if (lastDot >= 0) {
            final String extension = entry.getName().substring(lastDot + 1).toLowerCase();
            final String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mimeType != null) {
                return mimeType;
            }
        }

        return "application/octet-stream";
    }

    private static String normalizePath(String path, boolean isDirectory) {
        // TODO: Add support for different path separators.
        final StringBuilder result = new StringBuilder();
        if (!path.startsWith("/")) {
            result.append("/");
        }
        result.append(path);
        if (isDirectory && result.length() > 1 && !path.endsWith("/")) {
            result.append("/");
        }
        return result.toString();
    }

    private static String getPathForEntry(ZipEntry entry) {
        return normalizePath(entry.getName(), entry.isDirectory());
    }
};