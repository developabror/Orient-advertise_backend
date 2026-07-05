package uz.orientadvertise.services.common.util;

import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.common.exception.InvalidUploadException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoUploadValidatorTest {

    @Test
    void validate_validMp4_passes() {
        assertDoesNotThrow(() ->
                VideoUploadValidator.validate("movie.mp4", "video/mp4", 1024));
    }

    @Test
    void validate_uppercaseExtension_passes() {
        assertDoesNotThrow(() ->
                VideoUploadValidator.validate("MOVIE.MP4", "video/mp4", 1024));
    }

    @Test
    void validate_zeroSize_throws() {
        var ex = assertThrows(InvalidUploadException.class, () ->
                VideoUploadValidator.validate("movie.mp4", "video/mp4", 0));
        assertTrue(ex.getMessage().contains("greater than zero"));
    }

    @Test
    void validate_negativeSize_throws() {
        assertThrows(InvalidUploadException.class, () ->
                VideoUploadValidator.validate("movie.mp4", "video/mp4", -1));
    }

    @Test
    void validate_imageContentType_throws() {
        var ex = assertThrows(InvalidUploadException.class, () ->
                VideoUploadValidator.validate("movie.mp4", "image/png", 1024));
        assertTrue(ex.getMessage().contains("video/*"));
    }

    @Test
    void validate_nullContentType_throws() {
        assertThrows(InvalidUploadException.class, () ->
                VideoUploadValidator.validate("movie.mp4", null, 1024));
    }

    @Test
    void validate_disallowedExtension_throws() {
        var ex = assertThrows(InvalidUploadException.class, () ->
                VideoUploadValidator.validate("movie.exe", "video/mp4", 1024));
        assertTrue(ex.getMessage().contains("'exe'"));
    }

    @Test
    void validate_noExtension_throws() {
        assertThrows(InvalidUploadException.class, () ->
                VideoUploadValidator.validate("movie", "video/mp4", 1024));
    }

    @Test
    void validate_nullFilename_throws() {
        assertThrows(InvalidUploadException.class, () ->
                VideoUploadValidator.validate(null, "video/mp4", 1024));
    }

    @Test
    void validate_allWhitelistedExtensions() {
        for (var ext : new String[]{"mp4", "mov", "m4v", "mkv", "webm", "avi", "mpeg", "mpg", "wmv"}) {
            assertDoesNotThrow(() ->
                    VideoUploadValidator.validate("file." + ext, "video/anything", 100));
        }
    }

    @Test
    void validate_videoMimeWithSubtype_passes() {
        assertDoesNotThrow(() ->
                VideoUploadValidator.validate("clip.webm", "video/webm", 100));
        assertDoesNotThrow(() ->
                VideoUploadValidator.validate("movie.mkv", "video/x-matroska", 100));
    }
}
