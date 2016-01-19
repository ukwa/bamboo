package bamboo.task;

import bamboo.core.*;
import bamboo.io.HeritrixJob;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ImportJob {
	final Config config;
	final DbPool dbPool;
	final long crawlId;

	private HeritrixJob heritrixJob;
	private List<Path> launches;

	public ImportJob(Config config, DbPool dbPool, long crawlId) {
		this.config = config;
		this.dbPool = dbPool;
		this.crawlId = crawlId;
	}
	
	public void run()  {
		Crawl crawl;
		Series series;

		try (Db db = dbPool.take()) {
			crawl = db.findCrawl(crawlId);
			if (crawl == null)
				throw new RuntimeException("Crawl " + crawlId + " not found");
			if (crawl.getState() != Db.IMPORTING) {
				return; // sanity check
			}
			if (crawl.getCrawlSeriesId() == null)
				throw new RuntimeException("TODO: implement imports without a series");

			series = db.findCrawlSeriesById(crawl.getCrawlSeriesId());
			if (series == null)
				throw new RuntimeException("Couldn't find crawl series " + crawl.getCrawlSeriesId());
		}

		try {
			heritrixJob = HeritrixJob.byName(config.getHeritrixJobs(), crawl.getName());
			heritrixJob.checkSuitableForArchiving();

			Path dest;

			if (crawl.getPath() != null) {
				dest = crawl.getPath();
			} else {
				dest = allocateCrawlPath(crawl, series);
			}

			Path warcsDir = dest.resolve("warcs");

			if (!Files.exists(warcsDir)) {
				Files.createDirectory(warcsDir);
			}

			copyWarcs(heritrixJob.warcs().collect(Collectors.toList()), warcsDir);
			constructCrawlBundle(heritrixJob.dir(), dest);

			try (Db db = dbPool.take()) {
				db.updateCrawlState(crawl.getId(), Db.ARCHIVED);
			}
		} catch (IOException e) {
			try (Db db = dbPool.take()) {
				db.updateCrawlState(crawl.getId(), Db.IMPORT_FAILED);
			}
			throw new UncheckedIOException(e);
		}
	}

	private Path allocateCrawlPath(Crawl crawl, Series series) throws IOException {
		if (crawl.getPath() != null)
			return crawl.getPath();
		Path path;
		for (int i = 1;; i++) {
			path = series.getPath().resolve(String.format("%03d", i));
			try {
				Files.createDirectory(path);
				break;
			} catch (FileAlreadyExistsException e) {
				// try again
			}
		}
		try (Db db = dbPool.take()) {
			if (db.updateCrawlPath(crawl.getId(), path.toString()) == 0) {
				throw new RuntimeException("No such crawl: " + crawl.getId());
			}
		}
		return path;
	}

	private void copyWarcs(List<Path> warcs, Path destRoot) throws IOException {
		int i = 0;
		for (Path src : warcs) {
			Path destDir = destRoot.resolve(String.format("%03d", i++ / 1000));
			Path dest = destDir.resolve(src.getFileName());
			long size = Files.size(src);
			if (Files.exists(dest) && Files.size(dest) == size) {
				continue;
			}
			if (!Files.exists(destDir)) {
				Files.createDirectory(destDir);
			}
			String digest = Scrub.calculateDigest("SHA-256", src);
			Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
			try (Db db = dbPool.take()) {
				db.insertWarc(crawlId, Warc.IMPORTED, dest.toString(), dest.getFileName().toString(), size, digest);
			}
		}
	}

	private static boolean shouldIncludeInCrawlBundle(Path path) {
		return !path.getParent().getFileName().toString().equals("warcs") &&
				Files.isRegularFile(path);
	}

	private void constructCrawlBundle(Path src, Path dest) throws IOException {
		Path zipFile = dest.resolve("crawl-bundle.zip");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipFile))) {
			Files.walk(src)
					.filter(ImportJob::shouldIncludeInCrawlBundle)
					.forEachOrdered((path) -> {
						ZipEntry entry = new ZipEntry(src.relativize(path).toString());
						try {
							PosixFileAttributes attr = Files.readAttributes(path, PosixFileAttributes.class);
							entry.setCreationTime(attr.creationTime());
							entry.setLastModifiedTime(attr.lastModifiedTime());
							entry.setLastAccessTime(attr.lastAccessTime());
							entry.setSize(attr.size());
							zip.putNextEntry(entry);
							Files.copy(path, zip);
						} catch (IOException e) {
							throw new UncheckedIOException(e);
						}
					});
		}
	}
}
