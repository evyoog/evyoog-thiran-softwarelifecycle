package com.vyoog.report;

import com.vyoog.platform.audit.AuditService;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * VYB-0956 (F24, D43): the trend report as a file. The same read as the screen ({@link TrendService}), written to xlsx or PDF;
 * each export is audited because the file leaves the system, with the filters it was made with.
 */
@Service
public class TrendReportService {

    public enum Format {
        XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        PDF("pdf", "application/pdf");

        public final String extension;
        public final String contentType;

        Format(String extension, String contentType) {
            this.extension = extension;
            this.contentType = contentType;
        }

        public static Format of(String value) {
            for (Format f : values()) if (f.extension.equalsIgnoreCase(value == null ? "" : value.strip())) return f;
            throw new IllegalArgumentException("The format is xlsx or pdf");
        }
    }

    public record Export(String filename, String contentType, byte[] bytes) {}

    private final TrendService trends;
    private final AuditService audit;

    public TrendReportService(TrendService trends, AuditService audit) {
        this.trends = trends;
        this.audit = audit;
    }

    public Export export(Format format, UUID productId, UUID applicationId, int days, UUID releaseId, UUID actorId, String actorName) {
        var t = trends.trends(productId, applicationId, days, releaseId);
        byte[] bytes = format == Format.XLSX ? TrendReportWriter.xlsx(t, actorName) : TrendReportWriter.pdf(t, actorName);
        Map<String, Object> after = new HashMap<>();
        after.put("format", format.extension);
        after.put("days", days);
        if (productId != null) after.put("product", productId.toString());
        if (applicationId != null) after.put("application", applicationId.toString());
        if (releaseId != null) after.put("release", releaseId.toString());
        audit.record(actorId, "report.trends_exported", "TREND_REPORT", null, null, after);
        String filename = "vyoog-trends-" + LocalDate.now(ZoneOffset.UTC) + "." + format.extension;
        return new Export(filename, format.contentType, bytes);
    }
}
