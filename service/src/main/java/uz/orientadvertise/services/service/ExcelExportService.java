package uz.orientadvertise.services.service;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.domain.export.ExportType;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.Event;
import uz.orientadvertise.services.domain.model.DeviceStatusView;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.DeviceStatusViewRepository;
import uz.orientadvertise.services.domain.repository.EventRepository;
import uz.orientadvertise.services.domain.repository.PlaybackLogRepository;

/**
 * Streaming Excel export over Apache POI's {@link SXSSFWorkbook}.
 *
 * <p>SXSSF keeps only {@value #ROW_WINDOW} rows in memory at any time — older rows are
 * flushed to a temp file and serialized into the final .xlsx on close. This is what makes
 * 10,000+ row exports possible without loading the full result into the heap.
 *
 * <p>The DB side is paged ({@value #BATCH_SIZE} rows per fetch) so JPA never builds a
 * massive in-memory list either. Each batch is written to the workbook and immediately
 * eligible for the SXSSF window flush.
 *
 * <p>Edge cases:
 * <ul>
 *   <li><b>Per-request deadline</b> — the caller passes {@code deadlineMillis} (typically
 *       60s after request start). Between batches we check {@code System.currentTimeMillis}
 *       and abort if exceeded. The half-written .xlsx is closed; the client sees a
 *       truncated download because the response is already streaming.</li>
 *   <li><b>{@link SXSSFWorkbook#dispose()}</b> is always called in a finally block — POI
 *       leaves temp files on disk otherwise.</li>
 *   <li><b>UNREGISTERED devices and soft-deleted rows are excluded</b> — the export
 *       reflects the live operational view.</li>
 * </ul>
 */
@Service
public class ExcelExportService {

    private static final Logger log = LoggerFactory.getLogger(ExcelExportService.class);

    public static final int ROW_WINDOW = 100;
    public static final int BATCH_SIZE = 1000;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final EventRepository eventRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceStatusViewRepository statusViewRepository;
    private final PlaybackLogRepository playbackLogRepository;

    public ExcelExportService(EventRepository eventRepository,
                                DeviceRepository deviceRepository,
                                DeviceStatusViewRepository statusViewRepository,
                                PlaybackLogRepository playbackLogRepository) {
        this.eventRepository = eventRepository;
        this.deviceRepository = deviceRepository;
        this.statusViewRepository = statusViewRepository;
        this.playbackLogRepository = playbackLogRepository;
    }

    /**
     * @param projectIds operator scope: {@code null} = unrestricted (ADMIN/VIEWER); a non-empty
     *                   set restricts every sheet to those projects; an EMPTY set means an operator
     *                   with no projects ⇒ headers only, no rows. Resolved by the controller on the
     *                   request thread (this method may run on a streaming thread with no SecurityContext).
     */
    @Transactional(readOnly = true)
    public void streamExport(ExportType type, ExportFilters filters, Collection<Long> projectIds,
                              OutputStream out, long deadlineMillis) throws IOException {
        SXSSFWorkbook workbook = new SXSSFWorkbook(ROW_WINDOW);
        try {
            workbook.setCompressTempFiles(true);
            switch (type) {
                case EVENTS -> writeEvents(workbook, filters, projectIds, deadlineMillis);
                case DEVICES -> writeDevices(workbook, filters, projectIds, deadlineMillis);
                case STATS -> writeStats(workbook, filters, projectIds, deadlineMillis);
            }
            workbook.write(out);
        } finally {
            workbook.dispose();
            workbook.close();
        }
    }

    /** True when the caller is an operator with zero projects — every sheet is headers-only. */
    private static boolean emptyScope(Collection<Long> projectIds) {
        return projectIds != null && projectIds.isEmpty();
    }

    private void writeEvents(SXSSFWorkbook wb, ExportFilters f, Collection<Long> projectIds, long deadlineMillis) {
        SXSSFSheet sheet = wb.createSheet("Events");
        CellStyle header = headerStyle(wb);
        writeHeaderRow(sheet, header, "ID", "Occurred At", "Device ID", "Device",
                "Event Type", "Priority", "Payload");

        if (emptyScope(projectIds)) {
            return;   // operator with no projects ⇒ headers only
        }
        Instant from = f.from() != null ? f.from() : Instant.EPOCH;
        Instant to = f.to() != null ? f.to() : Instant.now();
        int rowIdx = 1;
        int pageIdx = 0;
        while (true) {
            if (System.currentTimeMillis() > deadlineMillis) {
                log.warn("Events export hit deadline at row {}", rowIdx);
                return;
            }
            var page = eventRepository.findFiltered(f.deviceId(), f.facilityId(), null,
                    from, to, projectIds, PageRequest.of(pageIdx, BATCH_SIZE));
            for (Event e : page.getContent()) {
                Row row = sheet.createRow(rowIdx++);
                cell(row, 0, e.getId());
                cell(row, 1, e.getOccurredAt());
                cell(row, 2, e.getDevice() != null ? e.getDevice().getId() : null);
                cell(row, 3, e.getDevice() != null ? e.getDevice().getName() : null);
                cell(row, 4, e.getEventType());
                cell(row, 5, e.getPriority() != null ? e.getPriority().name() : null);
                cell(row, 6, truncate(e.getPayload(), 1000));
            }
            if (!page.hasNext()) break;
            pageIdx++;
        }
    }

    private void writeDevices(SXSSFWorkbook wb, ExportFilters f, Collection<Long> projectIds, long deadlineMillis) {
        SXSSFSheet sheet = wb.createSheet("Devices");
        CellStyle header = headerStyle(wb);
        writeHeaderRow(sheet, header, "ID", "Serial", "Name", "Status",
                "Region", "Facility", "Last Heartbeat", "Content Version", "Last IP");

        if (emptyScope(projectIds)) {
            return;
        }
        int rowIdx = 1;
        int pageIdx = 0;
        while (true) {
            if (System.currentTimeMillis() > deadlineMillis) {
                log.warn("Devices export hit deadline at row {}", rowIdx);
                return;
            }
            var page = projectIds == null
                    ? deviceRepository.findActivePaged(f.facilityId(), PageRequest.of(pageIdx, BATCH_SIZE))
                    : deviceRepository.findActivePagedScoped(f.facilityId(), projectIds,
                            PageRequest.of(pageIdx, BATCH_SIZE));
            // Heartbeat-derived status per page (single source of truth = device_status_view),
            // not the raw, non-authoritative status column.
            var ids = page.getContent().stream().map(Device::getId).toList();
            var computed = statusViewRepository.findAllById(ids).stream()
                    .collect(java.util.stream.Collectors.toMap(
                            DeviceStatusView::getId, DeviceStatusView::getComputedStatus));
            for (Device d : page.getContent()) {
                Row row = sheet.createRow(rowIdx++);
                cell(row, 0, d.getId());
                cell(row, 1, d.getSerialNumber());
                cell(row, 2, d.getName());
                var cs = computed.get(d.getId());
                cell(row, 3, cs != null ? cs.name() : null);
                cell(row, 4, d.getRegion() != null ? d.getRegion().getName() : null);
                cell(row, 5, d.getFacility() != null ? d.getFacility().getName() : null);
                cell(row, 6, d.getLastHeartbeatAt());
                cell(row, 7, d.getCurrentContentVersion());
                cell(row, 8, d.getLastKnownIp());
            }
            if (!page.hasNext()) break;
            pageIdx++;
        }
    }

    private void writeStats(SXSSFWorkbook wb, ExportFilters f, Collection<Long> projectIds, long deadlineMillis) {
        SXSSFSheet sheet = wb.createSheet("Content Stats");
        CellStyle header = headerStyle(wb);
        writeHeaderRow(sheet, header, "Content ID", "Content Name",
                "Total Plays", "Distinct Devices");

        if (emptyScope(projectIds)) {
            return;
        }
        Instant from = f.from() != null ? f.from() : Instant.now().minus(Duration.ofDays(30));
        Instant to = f.to() != null ? f.to() : Instant.now();
        int rowIdx = 1;
        int pageIdx = 0;
        while (true) {
            if (System.currentTimeMillis() > deadlineMillis) {
                log.warn("Stats export hit deadline at row {}", rowIdx);
                return;
            }
            var page = projectIds == null
                    ? playbackLogRepository.aggregatePerContentInRange(from, to, PageRequest.of(pageIdx, BATCH_SIZE))
                    : playbackLogRepository.aggregatePerContentInRangeScoped(from, to, projectIds,
                            PageRequest.of(pageIdx, BATCH_SIZE));
            for (Object[] r : page.getContent()) {
                Row row = sheet.createRow(rowIdx++);
                cell(row, 0, (Long) r[0]);
                cell(row, 1, (String) r[1]);
                cell(row, 2, ((Number) r[2]).longValue());
                cell(row, 3, ((Number) r[3]).longValue());
            }
            if (!page.hasNext()) break;
            pageIdx++;
        }
    }

    private static CellStyle headerStyle(SXSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private static void writeHeaderRow(SXSSFSheet sheet, CellStyle style, String... names) {
        Row row = sheet.createRow(0);
        for (int i = 0; i < names.length; i++) {
            Cell c = row.createCell(i);
            c.setCellValue(names[i]);
            c.setCellStyle(style);
        }
    }

    private static void cell(Row row, int col, Object value) {
        if (value == null) return;
        Cell c = row.createCell(col);
        if (value instanceof Number n) {
            c.setCellValue(n.doubleValue());
        } else if (value instanceof Instant i) {
            c.setCellValue(i.toString());
        } else {
            c.setCellValue(value.toString());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    public record ExportFilters(Long facilityId, Long deviceId, Instant from, Instant to) {}
}
