package com.vyoog.attachments;

import com.vyoog.attachments.AttachmentRejectedException.Reason;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * VYB-0901 (F06): what may be uploaded, and under what name.
 *
 * <ul>
 *   <li><b>Size</b>: at most {@code vyoog.attachments.max-bytes} (default 10 MiB). The
 *       multipart limits in application.yml stop an oversized request before it is buffered;
 *       this is the check the service itself enforces, whoever calls it.</li>
 *   <li><b>Type</b>: the extension <em>and</em> the declared content type must both be on
 *       an allowlist. Active content (html, svg, js, executables, archives) is not on it.
 *       The content type is the client's claim, not a sniffed one — this is a filter, not
 *       a malware scan; downloads are additionally served as attachments with
 *       {@code nosniff}.</li>
 *   <li><b>Name</b>: the filename is untrusted input that ends up in an object-store key
 *       and a Content-Disposition header. {@link #sanitiseFilename} keeps only the last
 *       path segment and a safe character set, so it cannot climb out of its key prefix.</li>
 * </ul>
 */
@Component
public class AttachmentPolicy {

    static final int MAX_FILENAME_LENGTH = 120;

    static final Set<String> EXTENSIONS = Set.of(
        "pdf", "png", "jpg", "jpeg", "gif", "txt", "md", "csv", "json", "xml", "reqif",
        "doc", "docx", "xls", "xlsx", "ppt", "pptx");

    static final Set<String> CONTENT_TYPES = Set.of(
        "application/pdf", "image/png", "image/jpeg", "image/gif",
        "text/plain", "text/markdown", "text/x-markdown", "text/csv", "application/csv",
        "application/json", "application/xml", "text/xml", "application/reqif+xml",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation");

    private final long maxBytes;

    public AttachmentPolicy(@Value("${vyoog.attachments.max-bytes:10485760}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public long maxBytes() {
        return maxBytes;
    }

    /** Validates the upload and returns the sanitised filename to store and use in the object key. */
    public String check(String rawFilename, String contentType, long sizeBytes) {
        if (sizeBytes <= 0) {
            throw new AttachmentRejectedException(Reason.EMPTY, "The uploaded file is empty");
        }
        if (sizeBytes > maxBytes) {
            throw new AttachmentRejectedException(Reason.TOO_LARGE,
                "The file is %d bytes; the limit is %d bytes".formatted(sizeBytes, maxBytes));
        }
        String filename = sanitiseFilename(rawFilename);
        String ext = extensionOf(filename);
        if (!EXTENSIONS.contains(ext)) {
            throw new AttachmentRejectedException(Reason.TYPE_NOT_ALLOWED,
                "Files of type '." + ext + "' are not accepted");
        }
        String baseType = contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!CONTENT_TYPES.contains(baseType)) {
            throw new AttachmentRejectedException(Reason.TYPE_NOT_ALLOWED,
                "The content type '" + baseType + "' is not accepted");
        }
        return filename;
    }

    /**
     * The last path segment (either separator), control characters dropped, everything
     * outside {@code [A-Za-z0-9._ -]} replaced by {@code _}, leading dots removed (no
     * hidden files, no {@code ..}), truncated to 120 characters while keeping the extension.
     */
    public static String sanitiseFilename(String raw) {
        if (raw == null) {
            throw new AttachmentRejectedException(Reason.BAD_FILENAME, "The file has no name");
        }
        String name = raw.substring(Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\')) + 1);
        name = name.replaceAll("\\p{Cntrl}", "");
        name = name.replaceAll("[^A-Za-z0-9._ -]", "_");
        name = name.replaceAll("^[.\\s]+", "").replaceAll("[.\\s]+$", "");
        name = name.replaceAll("\\.{2,}", ".");
        if (name.isEmpty()) {
            throw new AttachmentRejectedException(Reason.BAD_FILENAME, "The file name is not usable");
        }
        if (name.length() > MAX_FILENAME_LENGTH) {
            String ext = extensionOf(name);
            String suffix = ext.isEmpty() ? "" : "." + ext;
            name = name.substring(0, MAX_FILENAME_LENGTH - suffix.length()) + suffix;
        }
        return name;
    }

    static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
