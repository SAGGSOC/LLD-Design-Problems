import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Logging Framework — LLD Interview (Single File)
 *
 * Design Patterns:
 *   - Strategy: Formatter (PlainText, JSON)
 *   - Strategy: Sink (Console, File)
 *   - Composite: Logger writes to multiple Destinations
 *   - Template: Destination combines filter + format + write
 *
 * Concurrency:
 *   - ReentrantLock per Destination (prevents interleaved writes to same sink)
 *   - Logger itself is stateless (just dispatches to destinations)
 *   - LogRecord is immutable (thread-safe by construction)
 *
 * Architecture:
 *   Logger → Destination(s) → [level filter] → Formatter → Sink
 */
public class LoggingFramework {

    // ═══════════════════════════════════════════════
    // Log Level
    // ═══════════════════════════════════════════════

    enum LogLevel {
        DEBUG(10), INFO(20), WARN(30), ERROR(40), FATAL(50);

        private final int severity;

        LogLevel(int severity) { this.severity = severity; }

        public boolean isAtLeast(LogLevel minimum) {
            return severity >= minimum.severity;
        }
    }

    // ═══════════════════════════════════════════════
    // Log Record (Immutable — thread-safe by design)
    // ═══════════════════════════════════════════════

    static class LogRecord {
        private final Instant timestamp;
        private final LogLevel level;
        private final String message;
        private final String threadName;

        public LogRecord(Instant timestamp, LogLevel level, String message, String threadName) {
            this.timestamp = timestamp;
            this.level = level;
            this.message = message;
            this.threadName = threadName;
        }

        public Instant getTimestamp() { return timestamp; }
        public LogLevel getLevel() { return level; }
        public String getMessage() { return message; }
        public String getThreadName() { return threadName; }
    }

    // ═══════════════════════════════════════════════
    // Strategy: Formatter
    // ═══════════════════════════════════════════════

    interface Formatter {
        String format(LogRecord record);
    }

    static class PlainTextFormatter implements Formatter {
        @Override
        public String format(LogRecord record) {
            return record.getTimestamp() + " [" + record.getLevel() + "] ["
                + record.getThreadName() + "] " + record.getMessage();
        }
    }

    static class JsonFormatter implements Formatter {
        @Override
        public String format(LogRecord record) {
            return "{"
                + "\"timestamp\":\"" + escape(record.getTimestamp().toString()) + "\","
                + "\"level\":\"" + record.getLevel() + "\","
                + "\"thread\":\"" + escape(record.getThreadName()) + "\","
                + "\"message\":\"" + escape(record.getMessage()) + "\""
                + "}";
        }

        private String escape(String value) {
            if (value == null) return "";
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"': result.append("\\\""); break;
                    case '\\': result.append("\\\\"); break;
                    case '\n': result.append("\\n"); break;
                    case '\r': result.append("\\r"); break;
                    case '\t': result.append("\\t"); break;
                    default: result.append(c);
                }
            }
            return result.toString();
        }
    }

    // ═══════════════════════════════════════════════
    // Strategy: Sink
    // ═══════════════════════════════════════════════

    interface Sink {
        void write(String formatted) throws Exception;
    }

    static class ConsoleSink implements Sink {
        @Override
        public void write(String formatted) {
            System.out.println(formatted);
        }
    }

    static class FileSink implements Sink, AutoCloseable {
        private final BufferedWriter writer;

        public FileSink(String filePath) throws IOException {
            this.writer = Files.newBufferedWriter(
                Path.of(filePath), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        @Override
        public void write(String formatted) throws IOException {
            writer.write(formatted);
            writer.newLine();
            writer.flush();
        }

        @Override
        public void close() throws IOException {
            writer.close();
        }
    }

    // ═══════════════════════════════════════════════
    // Destination (combines filter + formatter + sink)
    // Thread-safe: ReentrantLock per destination
    // ═══════════════════════════════════════════════

    static class Destination {
        private final Formatter formatter;
        private final LogLevel minLevel;
        private final Sink sink;
        private final ReentrantLock lock;

        public Destination(Formatter formatter, LogLevel minLevel, Sink sink) {
            this.formatter = formatter;
            this.minLevel = minLevel;
            this.sink = sink;
            this.lock = new ReentrantLock();
        }

        /**
         * Write a log record if it passes the level filter.
         * Lock ensures no interleaved output from concurrent threads.
         */
        public void write(LogRecord record) {
            if (!record.getLevel().isAtLeast(minLevel)) return;

            // Format OUTSIDE lock (stateless, no shared state)
            String formatted = formatter.format(record);

            // Write INSIDE lock (sink is shared resource)
            lock.lock();
            try {
                sink.write(formatted);
            } catch (Exception e) {
                System.err.println("logger: sink write failed: " + e.getMessage());
            } finally {
                lock.unlock();
            }
        }
    }

    // ═══════════════════════════════════════════════
    // Logger (stateless dispatcher — thread-safe by design)
    // ═══════════════════════════════════════════════

    static class Logger {
        private final List<Destination> destinations;

        public Logger(List<Destination> destinations) {
            this.destinations = List.copyOf(destinations); // immutable copy
        }

        public void log(LogLevel level, String message) {
            LogRecord record = new LogRecord(
                Instant.now(), level, message, Thread.currentThread().getName());

            for (Destination destination : destinations) {
                destination.write(record);
            }
        }

        public void debug(String msg) { log(LogLevel.DEBUG, msg); }
        public void info(String msg)  { log(LogLevel.INFO, msg); }
        public void warn(String msg)  { log(LogLevel.WARN, msg); }
        public void error(String msg) { log(LogLevel.ERROR, msg); }
        public void fatal(String msg) { log(LogLevel.FATAL, msg); }
    }

    // ═══════════════════════════════════════════════
    // Demo
    // ═══════════════════════════════════════════════

    public static void main(String[] args) throws Exception {
        // Console destination: plain text, INFO and above
        Destination consoleDest = new Destination(
            new PlainTextFormatter(), LogLevel.INFO, new ConsoleSink());

        // Console destination: JSON, ERROR and above
        Destination jsonErrorDest = new Destination(
            new JsonFormatter(), LogLevel.ERROR, new ConsoleSink());

        // Create logger with multiple destinations
        Logger logger = new Logger(List.of(consoleDest, jsonErrorDest));

        System.out.println("═══ Logging Framework ═══\n");

        logger.debug("This won't show (below INFO)");
        logger.info("Application started");
        logger.warn("Disk usage at 85%");
        logger.error("Failed to connect to database");
        logger.fatal("System out of memory");

        // Concurrent logging from multiple threads
        System.out.println("\n--- Concurrent Logging (5 threads) ---\n");
        Thread[] threads = new Thread[5];
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                logger.info("Thread " + idx + " started processing");
                logger.warn("Thread " + idx + " encountered retry");
                logger.error("Thread " + idx + " failed");
            }, "worker-" + i);
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        System.out.println("\n--- Done ---");
    }
}
