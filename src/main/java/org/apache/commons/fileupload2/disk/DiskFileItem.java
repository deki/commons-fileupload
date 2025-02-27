/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.fileupload2.disk;


import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.fileupload2.FileItem;
import org.apache.commons.fileupload2.FileItemHeaders;
import org.apache.commons.fileupload2.FileUploadException;
import org.apache.commons.fileupload2.InvalidFileNameException;
import org.apache.commons.fileupload2.ParameterParser;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.DeferredFileOutputStream;

/**
 * The default implementation of the
 * {@link org.apache.commons.fileupload2.FileItem FileItem} interface.
 *
 * <p>
 * After retrieving an instance of this class from a {@link
 * DiskFileItemFactory} instance (see
 * {@link org.apache.commons.fileupload2.servlet.ServletFileUpload
 * #parseRequest(javax.servlet.http.HttpServletRequest)}), you may
 * either request all contents of file at once using {@link #get()} or
 * request an {@link java.io.InputStream InputStream} with
 * {@link #getInputStream()} and process the file without attempting to load
 * it into memory, which may come handy with large files.
 * </p>
 * <p>
 * Temporary files, which are created for file items, should be
 * deleted later on. The best way to do this is using a
 * {@link org.apache.commons.io.FileCleaningTracker}, which you can set on the
 * {@link DiskFileItemFactory}. However, if you do use such a tracker,
 * then you must consider the following: Temporary files are automatically
 * deleted as soon as they are no longer needed. (More precisely, when the
 * corresponding instance of {@link java.io.File} is garbage collected.)
 * This is done by the so-called reaper thread, which is started and stopped
 * automatically by the {@link org.apache.commons.io.FileCleaningTracker} when
 * there are files to be tracked.
 * It might make sense to terminate that thread, for example, if
 * your web application ends. See the section on "Resource cleanup"
 * in the users guide of commons-fileupload.
 * </p>
 *
 * @since 1.1
 */
public class DiskFileItem implements FileItem {

    /**
     * Default content charset to be used when no explicit charset
     * parameter is provided by the sender. Media subtypes of the
     * "text" type are defined to have a default charset value of
     * "ISO-8859-1" when received via HTTP.
     */
    public static final String DEFAULT_CHARSET = StandardCharsets.ISO_8859_1.name();

    /**
     * UID used in unique file name generation.
     */
    private static final String UID = UUID.randomUUID().toString().replace('-', '_');

    /**
     * Counter used in unique identifier generation.
     */
    private static final AtomicInteger COUNTER = new AtomicInteger(0);

    /**
     * Returns an identifier that is unique within the class loader used to
     * load this class, but does not have random-like appearance.
     *
     * @return A String with the non-random looking instance identifier.
     */
    private static String getUniqueId() {
        final int limit = 100000000;
        final int current = COUNTER.getAndIncrement();
        String id = Integer.toString(current);

        // If you manage to get more than 100 million of ids, you'll
        // start getting ids longer than 8 characters.
        if (current < limit) {
            id = ("00000000" + id).substring(id.length());
        }
        return id;
    }

    /**
     * The name of the form field as provided by the browser.
     */
    private String fieldName;

    /**
     * The content type passed by the browser, or {@code null} if
     * not defined.
     */
    private final String contentType;

    /**
     * Whether or not this item is a simple form field.
     */
    private boolean isFormField;

    /**
     * The original file name in the user's file system.
     */
    private final String fileName;

    /**
     * The size of the item, in bytes. This is used to cache the size when a
     * file item is moved from its original location.
     */
    private long size = -1;

    /**
     * The threshold above which uploads will be stored on disk.
     */
    private final int sizeThreshold;

    /**
     * The directory in which uploaded files will be stored, if stored on disk.
     */
    private final File repository;

    /**
     * Cached contents of the file.
     */
    private byte[] cachedContent;

    /**
     * Output stream for this item.
     */
    private transient DeferredFileOutputStream dfos;

    /**
     * The temporary file to use.
     */
    private transient File tempFile;

    /**
     * The file items headers.
     */
    private FileItemHeaders headers;

    /**
     * Default content charset to be used when no explicit charset
     * parameter is provided by the sender.
     */
    private String defaultCharset = DEFAULT_CHARSET;

    /**
     * Constructs a new {@code DiskFileItem} instance.
     *
     * @param fieldName     The name of the form field.
     * @param contentType   The content type passed by the browser or
     *                      {@code null} if not specified.
     * @param isFormField   Whether or not this item is a plain form field, as
     *                      opposed to a file upload.
     * @param fileName      The original file name in the user's file system, or
     *                      {@code null} if not specified.
     * @param sizeThreshold The threshold, in bytes, below which items will be
     *                      retained in memory and above which they will be
     *                      stored as a file.
     * @param repository    The data repository, which is the directory in
     *                      which files will be created, should the item size
     *                      exceed the threshold.
     */
    public DiskFileItem(final String fieldName,
            final String contentType, final boolean isFormField, final String fileName,
            final int sizeThreshold, final File repository) {
        this.fieldName = fieldName;
        this.contentType = contentType;
        this.isFormField = isFormField;
        this.fileName = fileName;
        this.sizeThreshold = sizeThreshold;
        this.repository = repository;
    }

    /**
     * Deletes the underlying storage for a file item, including deleting any associated temporary disk file.
     * This method can be used to ensure that this is done at an earlier time, thus preserving system resources.
     */
    @Override
    public void delete() {
        cachedContent = null;
        final File outputFile = getStoreLocation();
        if (outputFile != null && !isInMemory() && outputFile.exists()) {
            if (!outputFile.delete()) {
                final String desc = "Cannot delete " + outputFile.toString();
                throw new UncheckedIOException(desc, new IOException(desc));
            }
        }
    }

    /**
     * Gets the contents of the file as an array of bytes.  If the
     * contents of the file were not yet cached in memory, they will be
     * loaded from the disk storage and cached.
     *
     * @return The contents of the file as an array of bytes
     * or {@code null} if the data cannot be read
     * @throws UncheckedIOException if an I/O error occurs
     */
    @Override
    public byte[] get() throws UncheckedIOException {
        if (isInMemory()) {
            if (cachedContent == null && dfos != null) {
                cachedContent = dfos.getData();
            }
            return cachedContent != null ? cachedContent.clone() : new byte[0];
        }

        final byte[] fileData = new byte[(int) getSize()];

        try (InputStream fis = Files.newInputStream(dfos.getFile().toPath())) {
            IOUtils.readFully(fis, fileData);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return fileData;
    }

    /**
     * Gets the content charset passed by the agent or {@code null} if
     * not defined.
     *
     * @return The content charset passed by the agent or {@code null} if
     *         not defined.
     */
    public String getCharSet() {
        final ParameterParser parser = new ParameterParser();
        parser.setLowerCaseNames(true);
        // Parameter parser can handle null input
        final Map<String, String> params = parser.parse(getContentType(), ';');
        return params.get("charset");
    }

    /**
     * Gets the content type passed by the agent or {@code null} if
     * not defined.
     *
     * @return The content type passed by the agent or {@code null} if
     *         not defined.
     */
    @Override
    public String getContentType() {
        return contentType;
    }

    /**
     * Gets the default charset for use when no explicit charset
     * parameter is provided by the sender.
     *
     * @return the default charset
     */
    public String getDefaultCharset() {
        return defaultCharset;
    }

    /**
     * Gets the name of the field in the multipart form corresponding to
     * this file item.
     *
     * @return The name of the form field.
     * @see #setFieldName(String)
     */
    @Override
    public String getFieldName() {
        return fieldName;
    }

    /**
     * Gets the file item headers.
     *
     * @return The file items headers.
     */
    @Override
    public FileItemHeaders getHeaders() {
        return headers;
    }

    /**
     * Gets an {@link java.io.InputStream InputStream} that can be
     * used to retrieve the contents of the file.
     *
     * @return An {@link java.io.InputStream InputStream} that can be
     *         used to retrieve the contents of the file.
     * @throws IOException if an error occurs.
     */
    @Override
    public InputStream getInputStream()
        throws IOException {
        if (!isInMemory()) {
            return Files.newInputStream(dfos.getFile().toPath());
        }

        if (cachedContent == null) {
            cachedContent = dfos.getData();
        }
        return new ByteArrayInputStream(cachedContent);
    }

    /**
     * Gets the original file name in the client's file system.
     *
     * @return The original file name in the client's file system.
     * @throws org.apache.commons.fileupload2.InvalidFileNameException The file name contains a NUL character,
     *   which might be an indicator of a security attack. If you intend to
     *   use the file name anyways, catch the exception and use
     *   {@link org.apache.commons.fileupload2.InvalidFileNameException#getName()}.
     */
    @Override
    public String getName() {
        return DiskFileItem.checkFileName(fileName);
    }

    /**
     * Gets an {@link java.io.OutputStream OutputStream} that can
     * be used for storing the contents of the file.
     *
     * @return An {@link java.io.OutputStream OutputStream} that can be used
     *         for storing the contents of the file.
     */
    @Override
    public OutputStream getOutputStream() {
        if (dfos == null) {
            final File outputFile = getTempFile();
            dfos = new DeferredFileOutputStream(sizeThreshold, outputFile);
        }
        return dfos;
    }

    /**
     * Gets the size of the file.
     *
     * @return The size of the file, in bytes.
     */
    @Override
    public long getSize() {
        if (size >= 0) {
            return size;
        }
        if (cachedContent != null) {
            return cachedContent.length;
        }
        if (dfos.isInMemory()) {
            return dfos.getData().length;
        }
        return dfos.getFile().length();
    }

    /**
     * Gets the {@link java.io.File} object for the {@code FileItem}'s
     * data's temporary location on the disk. Note that for
     * {@code FileItem}s that have their data stored in memory,
     * this method will return {@code null}. When handling large
     * files, you can use {@link java.io.File#renameTo(java.io.File)} to
     * move the file to new location without copying the data, if the
     * source and destination locations reside within the same logical
     * volume.
     *
     * @return The data file, or {@code null} if the data is stored in
     *         memory.
     */
    public File getStoreLocation() {
        if (dfos == null) {
            return null;
        }
        if (isInMemory()) {
            return null;
        }
        return dfos.getFile();
    }

    /**
     * Gets the contents of the file as a String, using the default
     * character encoding.  This method uses {@link #get()} to retrieve the
     * contents of the file.
     * <p>
     * <b>TODO</b> Consider making this method throw UnsupportedEncodingException.
     * </p>
     * @return The contents of the file, as a string.
     */
    @Override
    public String getString() {
        try {
            final byte[] rawData = get();
            String charset = getCharSet();
            if (charset == null) {
                charset = defaultCharset;
            }
            return new String(rawData, charset);
        } catch (final IOException e) {
            return "";
        }
    }

    /**
     * Gets the contents of the file as a String, using the specified
     * encoding.  This method uses {@link #get()} to retrieve the
     * contents of the file.
     *
     * @param charset The charset to use.
     * @return The contents of the file, as a string.
     * @throws UnsupportedEncodingException if the requested character
     *                                      encoding is not available.
     */
    @Override
    public String getString(final String charset)
        throws UnsupportedEncodingException, IOException {
        return new String(get(), charset);
    }

    /**
     * Creates and returns a {@link java.io.File File} representing a uniquely
     * named temporary file in the configured repository path. The lifetime of
     * the file is tied to the lifetime of the {@code FileItem} instance;
     * the file will be deleted when the instance is garbage collected.
     * <p>
     * <b>Note: Subclasses that override this method must ensure that they return the
     * same File each time.</b>
     * </p>
     * @return The {@link java.io.File File} to be used for temporary storage.
     */
    protected File getTempFile() {
        if (tempFile == null) {
            File tempDir = repository;
            if (tempDir == null) {
                tempDir = FileUtils.getTempDirectory();
            }
            final String tempFileName = String.format("upload_%s_%s.tmp", UID, getUniqueId());
            tempFile = new File(tempDir, tempFileName);
        }
        return tempFile;
    }

    /**
     * Tests whether or not a {@code FileItem} instance represents
     * a simple form field.
     *
     * @return {@code true} if the instance represents a simple form
     *         field; {@code false} if it represents an uploaded file.
     * @see #setFormField(boolean)
     */
    @Override
    public boolean isFormField() {
        return isFormField;
    }

    /**
     * Provides a hint as to whether or not the file contents will be read
     * from memory.
     *
     * @return {@code true} if the file contents will be read
     *         from memory; {@code false} otherwise.
     */
    @Override
    public boolean isInMemory() {
        if (cachedContent != null) {
            return true;
        }
        return dfos.isInMemory();
    }

    /**
     * Sets the default charset for use when no explicit charset
     * parameter is provided by the sender.
     *
     * @param charset the default charset
     */
    public void setDefaultCharset(final String charset) {
        defaultCharset = charset;
    }

    /**
     * Sets the field name used to reference this file item.
     *
     * @param fieldName The name of the form field.
     * @see #getFieldName()
     */
    @Override
    public void setFieldName(final String fieldName) {
        this.fieldName = fieldName;
    }

    /**
     * Specifies whether or not a {@code FileItem} instance represents
     * a simple form field.
     *
     * @param state {@code true} if the instance represents a simple form
     *              field; {@code false} if it represents an uploaded file.
     * @see #isFormField()
     */
    @Override
    public void setFormField(final boolean state) {
        isFormField = state;
    }

    /**
     * Sets the file item headers.
     *
     * @param headers The file items headers.
     */
    @Override
    public void setHeaders(final FileItemHeaders headers) {
        this.headers = headers;
    }

    /**
     * Returns a string representation of this object.
     *
     * @return a string representation of this object.
     */
    @Override
    public String toString() {
        return String.format("name=%s, StoreLocation=%s, size=%s bytes, isFormField=%s, FieldName=%s", getName(), getStoreLocation(), getSize(), isFormField(),
                getFieldName());
    }

    /**
     * Writes an uploaded item to disk.
     * <p>
     * The client code is not concerned with whether or not the item is stored in memory, or on disk in a temporary location. They just want to write the
     * uploaded item to a file.
     * </p>
     * <p>
     * This implementation first attempts to rename the uploaded item to the specified destination file, if the item was originally written to disk. Otherwise,
     * the data will be copied to the specified file.
     * </p>
     * <p>
     * This method is only guaranteed to work <em>once</em>, the first time it is invoked for a particular item. This is because, in the event that the method
     * renames a temporary file, that file will no longer be available to copy or rename again at a later time.
     * </p>
     * @param file The {@code File} into which the uploaded item should be stored.
     * @throws IOException if an error occurs.
     */
    @Override
    public void write(final File file) throws IOException {
        if (isInMemory()) {
            try (OutputStream fout = Files.newOutputStream(file.toPath())) {
                fout.write(get());
            } catch (final IOException e) {
                throw new IOException("Unexpected output data", e);
            }
        } else {
            final File outputFile = getStoreLocation();
            if (outputFile == null) {
                /*
                 * For whatever reason we cannot write the file to disk.
                 */
                throw new FileUploadException("Cannot write uploaded file to disk.");
            }
            // Save the length of the file
            size = outputFile.length();
            /*
             * The uploaded file is being stored on disk in a temporary location so move it to the desired file.
             */
            if (file.exists() && !file.delete()) {
                throw new FileUploadException("Cannot write uploaded file to disk.");
            }
            FileUtils.moveFile(outputFile, file);
        }
    }

    /**
     * Checks, whether the given file name is valid in the sense,
     * that it doesn't contain any NUL characters. If the file name
     * is valid, it will be returned without any modifications. Otherwise,
     * an {@link InvalidFileNameException} is raised.
     *
     * @param fileName The file name to check
     * @return Unmodified file name, if valid.
     * @throws InvalidFileNameException The file name was found to be invalid.
     */
    public static String checkFileName(final String fileName) {
        if (fileName != null  &&  fileName.indexOf('\u0000') != -1) {
            // fileName.replace("\u0000", "\\0")
            final StringBuilder sb = new StringBuilder();
            for (int i = 0;  i < fileName.length();  i++) {
                final char c = fileName.charAt(i);
                switch (c) {
                    case 0:
                        sb.append("\\0");
                        break;
                    default:
                        sb.append(c);
                        break;
                }
            }
            throw new InvalidFileNameException(fileName,
                    "Invalid file name: " + sb);
        }
        return fileName;
    }
}
