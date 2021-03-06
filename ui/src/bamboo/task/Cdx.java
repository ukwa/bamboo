package bamboo.task;

import bamboo.util.Urls;
import com.codepoetics.protonpack.StreamUtils;
import org.apache.commons.lang.StringUtils;
import org.archive.io.ArchiveReader;
import org.archive.io.ArchiveRecord;
import org.archive.io.ArchiveRecordHeader;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class Cdx {

    public static Stream<CdxRecord> records(ArchiveReader warcReader, String filename) {
        Stream<CdxRecord> stream = Stream.generate(new CdxRecordProducer(warcReader, filename)::next);
        return StreamUtils.takeWhile(stream, (record) -> record != null);
    }

    public static void writeCdx(Path warc, String filename, Writer out) throws IOException {
        records(WarcUtils.open(warc), filename).forEach(record -> {
            try {
                out.write(record.toCdxLine() + "\n");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    static class CdxRecordProducer {
        final static Pattern PANDORA_URL_MAP = Pattern.compile("^http://pandora.nla.gov.au/pan/([0-9]+/[0-9-]+)/url.map$");

        private final ArchiveReader warc;
        private final Iterator<ArchiveRecord> iterator;
        private final String filename;
        private Iterator<Alias> urlMapIterator = null;

        CdxRecordProducer(ArchiveReader warc, String filename) {
            this.warc = warc;
            iterator = warc.iterator();
            this.filename = filename;
        }

        public CdxRecord next() {
            try {
                if (urlMapIterator != null && urlMapIterator.hasNext()) {
                    return urlMapIterator.next();
                }
                while (iterator.hasNext()) {
                    ArchiveRecord record = iterator.next();

                    String url = record.getHeader().getUrl();
                    if (url != null) {
                        Matcher m = PANDORA_URL_MAP.matcher(url);
                        if (m.matches()) {
                            urlMapIterator = parseUrlMap(record, m.group(1)).iterator();
                            return next();
                        }
                    }

                    Capture capture = Capture.parseWarcRecord(filename, record);
                    if (capture != null) {
                        return capture;
                    }
                }
                return null;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public interface CdxRecord {
        String toCdxLine();
    }

    static List<Alias> parseUrlMap(InputStream in, String piAndDate) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
        List<Alias> aliases = primaryAliases(reader, piAndDate);
        aliases.addAll(secondaryAliases(aliases, piAndDate));
        return aliases;
    }

    /**
     * Sometimes HTTrack's url-rewriting is only partially successful, often due to JavaScript constructing
     * URLs.  So we build a second set of aliases that include original URL but relative to the PANDORA instance.
     *
     * We don't include a secondary alias if there's already a primary alias covering it.
     */
    static List<Alias> secondaryAliases(List<Alias> primaryAliases, String piAndDate) {
        Set<String> primaryUrls = new HashSet<>();
        for (Alias alias : primaryAliases) {
            primaryUrls.add(alias.alias);
        }

        List<Alias> out = new ArrayList<>();
        for (Alias alias : primaryAliases) {
            String secondaryUrl = "http://pandora.nla.gov.au/pan/" + piAndDate + "/" + Urls.removeScheme(alias.target);
            if (!primaryUrls.contains(secondaryUrl) && isUrlSane(secondaryUrl)) {
                out.add(new Alias(secondaryUrl, alias.target));
            }
        }
        return out;
    }

    public static boolean isUrlSane(String url) {
        try {
            URI targetUri = new URI(url);
            String host = targetUri.getHost();
            if (host == null || !host.contains(".")) {
                return false;
            }
        } catch (URISyntaxException e) {
            return false;
        }
        return true;
    }

    static List<Alias> primaryAliases(BufferedReader reader, String piAndDate) throws IOException {
        List<Alias> aliases = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            Alias alias = parseUrlMapLine(line, piAndDate);
            if (alias.isSane()) {
                aliases.add(alias);
            }
        }
        return aliases;
    }

    /**
     * Parses a line from a PANDORA url.map file and returns a list of corresponding aliases.
     */
    static Alias parseUrlMapLine(String line, String piAndDate) {
        String[] fields = line.trim().split("\\^\\^");
        String targetUrl = Urls.addImplicitScheme(fields[0]);
        String instanceBaseUrl = "http://pandora.nla.gov.au/pan/" + piAndDate + "/";
        String aliasUrl = instanceBaseUrl + cleanHttrackPath(fields[1], piAndDate);
        return new Alias(aliasUrl, targetUrl);
    }

    /**
     * Strips the pi and instance date from a PANDORA path if present.
     */
    static String cleanHttrackPath(String path, String piAndDate) {
        path = StringUtils.removeStart(path, "/");
        String piAndLegacyDate = StringUtils.removeEnd(piAndDate, "-0000");
        if (path.startsWith(piAndDate + "/")) {
            return path.substring(piAndDate.length() + 1); // 1234/20010101-1234/(example.org/index.html)
        } else if (path.startsWith(piAndLegacyDate + "/")){
            return path.substring(piAndLegacyDate.length() + 1); // 1234/20010101/(example.org/index.html)
        } else {
            return path; // (example.org/index.html)
        }
    }

    public static class Alias implements CdxRecord {
        public String alias;
        public String target;

        public Alias(String alias, String target) {
            this.alias = alias;
            this.target = target;
        }

        @Override
        public String toCdxLine() {
            return "@alias " + alias + " " + target;
        }

        public boolean isSane() {
            return isUrlSane(alias) && isUrlSane(target);
        }
    }

    public static class Capture implements CdxRecord {
        public String contentType;
        public int status;
        public String location;
        public String date;
        public String url;
        public long contentLength;
        public long offset;
        public String filename;
        public String digest;

        public String toCdxLine() {
            return String.join(" ", "-", date, url, optional(contentType),
                    status == -1 ? "-" : Integer.toString(status), optional(digest),
                    optional(location), "-", Long.toString(contentLength),
                    Long.toString(offset), filename);
        }

        private static String optional(String s) {
            if (s == null) {
                return "-";
            }
            return s.replace(" ", "%20").replace("\n", "%0A").replace("\r", "%0D");
        }

        static Capture parseWarcRecord(String filename, ArchiveRecord record) throws IOException {
            ArchiveRecordHeader header = record.getHeader();

            Capture capture = new Capture();

            if (WarcUtils.isResponseRecord(header)) {
                HttpHeader http = HttpHeader.parse(record, capture.url);
                if (http == null) {
                    return null;
                }
                capture.contentType = HttpHeader.cleanContentType(http.contentType);
                capture.status = http.status;
                capture.location = http.location;
            } else if (WarcUtils.isResourceRecord(header)) {
                capture.contentType = header.getMimetype();
                capture.status = 200;
                capture.location = null;
            } else {
                return null;
            }

            capture.url = WarcUtils.getCleanUrl(header);
            capture.date = WarcUtils.getArcDate(header);
            capture.contentLength = header.getContentLength();
            capture.offset = header.getOffset();
            capture.filename = filename;
            capture.digest = WarcUtils.getOrCalcDigest(record);
            return capture;
        }
    }
}
