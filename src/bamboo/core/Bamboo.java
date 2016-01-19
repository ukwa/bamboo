package bamboo.core;

import bamboo.io.HeritrixJob;
import bamboo.seedlist.Seedlists;
import bamboo.task.*;
import bamboo.web.Main;
import doss.BlobStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Bamboo implements AutoCloseable {
    public final Config config;
    private final DbPool dbPool;
    public final PandasDbPool pandasDbPool;
    public final Taskmaster taskmaster;
    public final BlobStore blobStore;

    public final Crawls crawls;
    public final Serieses serieses;
    public final Warcs warcs;
    public final Collections collections;
    public final Seedlists seedlists;

    public Bamboo(Config config, DbPool dbPool) {
        this.config = config;
        this.dbPool = dbPool;
        this.pandasDbPool = null;
        this.taskmaster = new Taskmaster(config, dbPool);
        //blobStore = LocalBlobStore.open(config.getDossHome());
        blobStore = null; // coming soon

        crawls = new Crawls(dbPool);
        serieses = new Serieses(dbPool);
        warcs = new Warcs(dbPool);
        collections = new Collections(dbPool);
        seedlists = new Seedlists(dbPool);
    }

    public Bamboo() {
        config = new Config();
        dbPool = new DbPool(config);
        dbPool.migrate();
        if (config.getPandasDbUrl() != null) {
            pandasDbPool = new PandasDbPool(config);
        } else {
            pandasDbPool = null;
        }
        this.taskmaster = new Taskmaster(config, dbPool);
        //blobStore = LocalBlobStore.open(config.getDossHome());
        blobStore = null; // coming soon

        crawls = new Crawls(dbPool);
        serieses = new Serieses(dbPool);
        warcs = new Warcs(dbPool);
        collections = new Collections(dbPool);
        seedlists = new Seedlists(dbPool);
    }

    @Override
    public void close() {
        dbPool.close();
        if (pandasDbPool != null) {
            pandasDbPool.close();
        }
    }

    public long importHeritrixCrawl(String jobName, Long crawlSeriesId) {
        HeritrixJob job = HeritrixJob.byName(config.getHeritrixJobs(), jobName);
        long crawlId;
        try (Db db = dbPool.take()) {
            crawlId = db.createCrawl(jobName, crawlSeriesId, Db.IMPORTING);
        }
        taskmaster.startImporting();
        return crawlId;
    }

    public void insertWarc(long crawlId, String path) throws IOException {
        try (Db db = dbPool.take()) {
            Path p = Paths.get(path);
            long size = Files.size(p);
            String digest = Scrub.calculateDigest("SHA-256", p);
            long warcId = db.insertWarc(crawlId, Warc.IMPORTED, path, p.getFileName().toString(), size, digest);
            System.out.println("Registered WARC " + warcId);
        }
    }

    public void runCdxIndexer() throws Exception {
        new CdxIndexer(dbPool).run();
    }

    public void runSolrIndexer() throws Exception {
        new SolrIndexer(dbPool).run();
    }

    public void refreshWarcStats() throws IOException {
        try (Db db = dbPool.take()) {
            db.refreshWarcStatsOnCrawls();
            db.refreshWarcStatsOnCrawlSeries();
        }
    }

    public void refreshWarcStatsFs() throws IOException {
        try (Db db = dbPool.take()) {
            for (Warc warc : db.listWarcs()) {
                long size = Files.size(warc.getPath());
                System.out.println(warc.getSize() + " -> " + size + " " + warc.getId() + " " + warc.getPath());
                db.updateWarcSizeWithoutRollup(warc.getId(), size);
            }
            db.refreshWarcStatsOnCrawls();
            db.refreshWarcStatsOnCrawlSeries();
        }
    }

    public static void main(String args[]) throws Exception {
        if (args.length == 0)
            usage();
        if (args[0].equals("server")) {
            Main.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        Bamboo bamboo = new Bamboo();
        switch (args[0]) {
            case "import":
                bamboo.importHeritrixCrawl(args[1], Long.parseLong(args[2]));
                break;
            case "insert-warc":
                for (int i = 2; i < args.length; i++) {
                    bamboo.insertWarc(Long.parseLong(args[1]), args[i]);
                }
                break;
            case "cdx-indexer":
                bamboo.runCdxIndexer();
                break;
            case "solr-indexer":
                bamboo.runSolrIndexer();
                break;
            case "recalc-crawl-times":
                bamboo.recalcCrawlTimes();
                break;
            case "refresh-warc-stats":
                bamboo.refreshWarcStats();
                break;
            case "refresh-warc-stats-fs":
                bamboo.refreshWarcStatsFs();
                break;
            case "scrub":
                Scrub.scrub(bamboo);
                break;
            case "watch-importer":
                new WatchImporter(bamboo.dbPool, bamboo.config.getWatches()).run();
                break;
            case "import-pandas-instance":
                //new PandasImport(bamboo).importInstance(Long.parseLong(args[1]), Long.parseLong(args[2]));
                break;
            default:
                usage();
        }
    }

    /**
     * Update crawls with an appriximation of their start and end times based on the timestamp extracted from (W)ARC
     * filenames.  Bit of a migration hack to fill in the table without a full reindex.
     */
    public void recalcCrawlTimes() {
        Pattern p = Pattern.compile(".*-((?:20|19)[0-9]{12,15})-[0-9]{5}-.*");
        try (Db db = dbPool.take()) {
            for (Warc warc : db.listWarcs()) {
                Matcher m = p.matcher(warc.getFilename());
                if (m.matches()) {
                    Date date = WarcUtils.parseArcDate(m.group(1).substring(0, 14));
                    db.conditionallyUpdateCrawlStartTime(warc.getCrawlId(), date);
                    db.conditionallyUpdateCrawlEndTime(warc.getCrawlId(), date);
                }
            }
        }
    }

    public static void usage() {
        System.out.println("Usage: bamboo <subcommand>");
        System.out.println("Bamboo admin tools");
        System.out.println("\nSub-commands:");
        System.out.println("  cdx-indexer                      - Run the CDX indexer");
        System.out.println("  import <jobName> <crawlSeriesId> - Import a crawl from Heritrix");
        System.out.println("  insert-warc <crawl-id> <paths>   - Register WARCs with a crawl");
        System.out.println("  recalc-crawl-times               - Fill approx crawl times based on warc filenames (migration hack)");
        System.out.println("  refresh-warc-stats               - Refresh warc stats tables");
        System.out.println("  refresh-warc-stats-fs            - Refresh warc stats tables based on disk");
        System.out.println("  server                           - Run web server");
        System.out.println("  watch-importer <crawl-id> <path> - Monitor path for new warcs, incrementally index them and then import them to crawl-id");
        System.out.println("  import-pandas-instance  <series-id> <instance-id>");
        System.exit(1);
    }

    public void startWorkerThreads() {
        startWatchImporter();
    }

    void startWatchImporter() {
        List<Config.Watch> watches = config.getWatches();
        if (!watches.isEmpty()) {
            Thread thread = new Thread(()-> {
                try {
                    new WatchImporter(dbPool, watches).run();
                } catch (IOException | InterruptedException e) {
                    e.printStackTrace();
                    throw new RuntimeException(e);
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
    }
}
