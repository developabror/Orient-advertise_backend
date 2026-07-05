package uz.orientadvertise.services.api.controller;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import uz.orientadvertise.services.service.FileStorageService;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileStorageService fileStorageService;

    public FileController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<UploadResponse> upload(@RequestParam("file") MultipartFile file) throws Exception {
        var objectName = UUID.randomUUID() + "_" + file.getOriginalFilename();
        fileStorageService.upload(objectName, file.getInputStream(), file.getSize(), file.getContentType());
        return ResponseEntity.ok(new UploadResponse(objectName, file.getSize()));
    }

    // Raw-key reads are ADMIN-only: the key is an opaque MinIO object name, so an OPERATOR who
    // learned another project's key could otherwise fetch its bytes directly, bypassing
    // operator_content_access. Operators stream their own ∪ granted content via
    // GET /api/content/{id}/stream-url (which enforces ownership/grant). POST upload stays
    // OPERATOR-allowed below — it mints a fresh random key and can't read another project's bytes.
    @GetMapping("/{objectName}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<InputStreamResource> download(@PathVariable String objectName) {
        String key = sanitizeObjectName(objectName);
        InputStream stream = fileStorageService.download(key);
        // Encode the filename (RFC 6266) instead of interpolating it raw into the header —
        // a crafted key can't inject CR/LF or extra header directives.
        var disposition = ContentDisposition.attachment().filename(key, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(stream));
    }

    @GetMapping("/{objectName}/presigned-url")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PresignedUrlResponse> presignedUrl(@PathVariable String objectName) {
        String key = sanitizeObjectName(objectName);
        var url = fileStorageService.generatePresignedUrl(key);
        return ResponseEntity.ok(new PresignedUrlResponse(key, url));
    }

    /**
     * Reject object keys that try to escape the bucket namespace — path separators,
     * parent-dir traversal, or control characters. Returns the key unchanged when clean;
     * throws {@link IllegalArgumentException} (→ 400) otherwise.
     */
    private static String sanitizeObjectName(String objectName) {
        if (objectName == null || objectName.isBlank()
                || objectName.contains("/") || objectName.contains("\\")
                || objectName.contains("..") || objectName.chars().anyMatch(c -> c < 0x20)) {
            throw new IllegalArgumentException("Invalid object name");
        }
        return objectName;
    }

    @DeleteMapping("/{objectName}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable String objectName) {
        fileStorageService.delete(objectName);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<StorageStatusResponse> status() {
        boolean available = fileStorageService.isStorageAvailable();
        var status = available ? "UP" : "DEGRADED";
        int httpStatus = available ? 200 : 503;
        return ResponseEntity.status(httpStatus).body(new StorageStatusResponse(status));
    }

    public record UploadResponse(String objectName, long size) {}
    public record PresignedUrlResponse(String objectName, String url) {}
    public record StorageStatusResponse(String status) {}
}
