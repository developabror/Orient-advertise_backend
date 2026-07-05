package uz.orientadvertise.services.common.util;

import java.util.Set;

import uz.orientadvertise.services.common.exception.InvalidUploadException;

/**
 * Pre-upload validation: MIME type prefix, extension whitelist, non-zero size.
 * Throws {@link InvalidUploadException} (mapped to 400) on any failure.
 *
 * This is the cheap front-line check. The deep check (FFprobe) runs after
 * the bytes are persisted to MinIO.
 */
public final class VideoUploadValidator {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "mp4", "mov", "m4v", "mkv", "webm", "avi", "mpeg", "mpg", "wmv");

    private static final String VIDEO_MIME_PREFIX = "video/";

    private VideoUploadValidator() {
    }

    public static void validate(String filename, String contentType, long sizeBytes) {
        if (sizeBytes <= 0) {
            throw new InvalidUploadException("File size must be greater than zero");
        }

        if (contentType == null || !contentType.toLowerCase().startsWith(VIDEO_MIME_PREFIX)) {
            throw new InvalidUploadException(
                    "Content type must be video/*, got: " + contentType);
        }

        var extension = extractExtension(filename);
        if (extension == null) {
            throw new InvalidUploadException("Filename must have a recognised extension: " + filename);
        }
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new InvalidUploadException(
                    "Extension '%s' not allowed. Allowed: %s".formatted(extension, ALLOWED_EXTENSIONS));
        }
    }

    private static String extractExtension(String filename) {
        if (filename == null || filename.isBlank()) return null;
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return null;
        return filename.substring(dot + 1).toLowerCase();
    }
}
