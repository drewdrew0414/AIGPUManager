package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
import com.drewdrew1.core.model.UsageReportRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.freva.asciitable.AsciiTable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/** Exposes usage and billing report commands. */
@Command(
        name = "report",
        mixinStandardHelpOptions = true,
        description = "Reporting operations",
        subcommands = {
                ReportCommand.UsageCommand.class,
                ReportCommand.BillingCommand.class,
                ReportCommand.PrometheusCommand.class
        }
)
public class ReportCommand implements Runnable {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    @ParentCommand private GpuMgrCommand parent;
    @Spec private CommandSpec spec;
    @Override public void run() { spec.commandLine().usage(System.out); }

    @Command(name = "usage", description = "Generate usage report")
    static class UsageCommand implements Callable<Integer> {
        @ParentCommand private ReportCommand reportCommand;
        @Option(names = "--format", required = true) private String format;
        @Option(names = "--by", required = true) private String by;
        @Override public Integer call() {
            CliSupport.requireOneOf(format, "format", Set.of("pdf", "csv", "json"));
            CliSupport.requireOneOf(by, "by", Set.of("user", "tenant", "model"));
            List<UsageReportRow> rows = reportCommand.parent.createContext().governanceService().usageReport(by);
            try {
                render(rows, format, "usage-report");
            } catch (Exception e) {
                throw new IllegalStateException("Failed to render usage report", e);
            }
            return 0;
        }
    }

    @Command(name = "billing", description = "Generate billing simulation report")
    static class BillingCommand implements Callable<Integer> {
        @ParentCommand private ReportCommand reportCommand;
        @Option(names = "--rate-card", required = true) private String rateCard;
        @Override public Integer call() {
            CliSupport.requireNonBlank(rateCard, "rate-card");
            List<UsageReportRow> rows = reportCommand.parent.createContext().governanceService().billingReport(Path.of(rateCard));
            printTable(rows, true);
            return 0;
        }
    }

    @Command(name = "prometheus", description = "Export current inventory and allocation metrics in Prometheus text format")
    static class PrometheusCommand implements Callable<Integer> {
        @ParentCommand private ReportCommand reportCommand;
        @Option(names = "--path") private Path path;

        @Override public Integer call() {
            String payload = reportCommand.parent.createContext().prometheusExportService().render();
            if (path == null) {
                System.out.print(payload);
            } else {
                CliSupport.writeStringAtomic(path, payload);
                System.out.println("Wrote Prometheus metrics to " + path.toAbsolutePath());
            }
            return 0;
        }
    }

    private static void render(List<UsageReportRow> rows, String format, String baseName) throws Exception {
        switch (format.toLowerCase()) {
            case "json" -> System.out.println(OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(rows));
            case "csv" -> printCsv(rows);
            case "pdf" -> writePdf(rows, baseName);
            default -> throw new IllegalArgumentException("Unsupported format: " + format);
        }
    }

    private static void printCsv(List<UsageReportRow> rows) {
        System.out.println("key,allocations,gpus,total_vram_mb,total_lease_hours,gpu_hours,estimated_cost");
        for (UsageReportRow row : rows) {
            System.out.printf(
                    "%s,%d,%d,%d,%d,%.2f,%.2f%n",
                    quote(row.key()),
                    row.allocationCount(),
                    row.gpuCount(),
                    row.totalVramMb(),
                    row.totalLeaseHours(),
                    row.gpuHours(),
                    row.estimatedCost()
            );
        }
    }

    private static void writePdf(List<UsageReportRow> rows, String baseName) throws Exception {
        Path path = Path.of(baseName + "-" + Instant.now().toEpochMilli() + ".pdf");
        List<String> lines = new ArrayList<>();
        lines.add(baseName);
        lines.add("");
        for (UsageReportRow row : rows) {
            lines.add(row.key() + " | allocations=" + row.allocationCount()
                    + " | gpus=" + row.gpuCount()
                    + " | gpuHours=" + String.format("%.2f", row.gpuHours())
                    + " | estimatedCost=" + String.format("%.2f", row.estimatedCost()));
        }
        String text = String.join("\n", lines).replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
        byte[] content = minimalPdf(text).getBytes(StandardCharsets.US_ASCII);
        CliSupport.writeBytesAtomic(path, content);
        System.out.println("Wrote PDF report to " + path.toAbsolutePath());
    }

    private static String minimalPdf(String text) {
        String stream = "BT /F1 10 Tf 50 780 Td (" + text.replace("\n", ") Tj T* (") + ") Tj ET";
        StringBuilder pdf = new StringBuilder();
        List<Integer> offsets = new ArrayList<>();
        pdf.append("%PDF-1.4\n");
        offsets.add(pdf.length());
        pdf.append("1 0 obj<< /Type /Catalog /Pages 2 0 R >>endobj\n");
        offsets.add(pdf.length());
        pdf.append("2 0 obj<< /Type /Pages /Kids [3 0 R] /Count 1 >>endobj\n");
        offsets.add(pdf.length());
        pdf.append("3 0 obj<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources<< /Font<< /F1 5 0 R >> >> >>endobj\n");
        offsets.add(pdf.length());
        pdf.append("4 0 obj<< /Length ").append(stream.length()).append(" >>stream\n").append(stream).append("\nendstream endobj\n");
        offsets.add(pdf.length());
        pdf.append("5 0 obj<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>endobj\n");
        int xref = pdf.length();
        pdf.append("xref\n0 6\n0000000000 65535 f \n");
        for (int offset : offsets) {
            pdf.append(String.format("%010d 00000 n %n", offset));
        }
        pdf.append("trailer<< /Size 6 /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF");
        return pdf.toString();
    }

    private static void printTable(List<UsageReportRow> rows, boolean includeCost) {
        List<String[]> tableRows = new ArrayList<>();
        for (UsageReportRow row : rows) {
            tableRows.add(new String[]{
                    row.key(),
                    Integer.toString(row.allocationCount()),
                    Integer.toString(row.gpuCount()),
                    Long.toString(row.totalVramMb()),
                    Long.toString(row.totalLeaseHours()),
                    String.format("%.2f", row.gpuHours()),
                    includeCost ? String.format("%.2f", row.estimatedCost()) : "-"
            });
        }
        System.out.println(AsciiTable.getTable(
                new String[]{"Key", "Allocations", "GPUs", "TotalVRAM", "LeaseHours", "GPUHours", "EstimatedCost"},
                tableRows.toArray(String[][]::new)
        ));
    }

    private static String quote(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
