/**
 * Copyright 2016 National Library of Australia
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package bamboo.trove.demand;

import javax.annotation.PostConstruct;

import bamboo.trove.common.BaseWarcDomainManager;
import bamboo.trove.common.IndexerDocument;
import bamboo.trove.common.WarcProgressManager;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OnDemandWarcManager extends BaseWarcDomainManager {
  private static final Logger log = LoggerFactory.getLogger(OnDemandWarcManager.class);

  private boolean running = false;
  private long warcsProcessed = 0;
  private long lastWarcId = 0;

  @PostConstruct
  public void init() throws InterruptedException {
    BaseWarcDomainManager.waitUntilStarted();
		log.info("***** OnDemandWarcManager *****");
		log.info("Run at start       : {}", runAtStart);
  }

  // The UI will call here when it wants to start indexing a warc
	public String index(long warcId) throws Exception {
    return index(warcId, -1);
  }
  // Same as above, but flagging a particular offset as being of interest
	public String index(long warcId, long warcOffset) throws Exception {
    if (!running) {
      return "<error>Offline</error>";
    }

    log.info("Indexing on demand. Warc #{}", warcId);
    WarcProgressManager batch = getAndEnqueueWarc(warcId, warcOffset, 0);
    IndexerDocument responseDocument = batch.getTrackedDocument();
    log.info("Warc #{} has {} documents. Loading has completed.", warcId, batch.size());

    while (!batch.isFilterComplete()) {
      Thread.sleep(100);
      checkErrors(batch);
    }
    log.info("Warc #{} has finished filtering...", warcId);

    while (!batch.isTransformComplete()) {
      Thread.sleep(100);
      checkErrors(batch);
    }
    log.info("Warc #{} has finished transform...", warcId);

    while (!batch.isIndexComplete()) {
      Thread.sleep(100);
      checkErrors(batch);
    }
    log.info("Warc #{} has finished indexing...", warcId);

    warcsProcessed++;
    lastWarcId = warcId;

    return ClientUtils.toXML(responseDocument.getSolrDocument());
  }

  private void checkErrors(WarcProgressManager warc) throws Exception {
    if (warc.hasErrors()) {
      log.error("Warc #{} failed to index.", warc.getWarcId());
      throw new Exception("Indexing failed");
    }
  }

  public void run() {
    // Doesn't really do anything
    start();
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public boolean isStopping() {
    return false;
  }

  @Override
  public void start() {
    if (!running)  {
      log.info("Starting...");
      running = true;
    }
  }

  @Override
  public void stop() {
    if (running)  {
      running = false;
    }
  }

  @Override
  public String getName() {
    return "Web Archives On-Demand Indexing";
  }

  @Override
  public long getUpdateCount() {
    return warcsProcessed;
  }

  @Override
  public String getLastIdProcessed() {
    return "warc#" + lastWarcId;
  }
}