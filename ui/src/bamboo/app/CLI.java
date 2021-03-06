package bamboo.app;

import java.io.IOException;

public class CLI {
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
        System.out.println("  import-pandas-all  <series-id>   - Import all pandas instances");
        System.exit(1);
    }

    public static void main(String[] args) throws IOException {
        Bamboo bamboo = new Bamboo();
        switch (args[0]) {
            case "import-pandas-all":
                checkPandasIntegration(bamboo);
                bamboo.pandas.importAllInstances(Long.parseLong(args[1]));
                break;
            case "import-pandas":
                checkPandasIntegration(bamboo);
                bamboo.pandas.importAllInstances(Long.parseLong(args[1]), args[2]);
                break;
            case "import-pandas-instance":
                checkPandasIntegration(bamboo);
                bamboo.pandas.importInstanceIfNotExists(Long.parseLong(args[2]), Long.parseLong(args[1]));
                break;
            case "import":
                bamboo.crawls.importHeritrixCrawl(args[1], Long.parseLong(args[2]));
                break;
            /* FIXME: restore these
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
            */
            default:
                usage();
        }
    }

    private static void checkPandasIntegration(Bamboo bamboo) {
        if (bamboo.pandas == null) {
            System.err.println("PANDAS integration not available, ensure PANDAS_DB_URL is set");
            System.exit(1);
        }
    }
}
