package bamboo.crawl;

import org.skife.jdbi.v2.StatementContext;
import org.skife.jdbi.v2.sqlobject.*;
import org.skife.jdbi.v2.sqlobject.customizers.RegisterMapper;
import org.skife.jdbi.v2.tweak.ResultSetMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@RegisterMapper({CollectionsDAO.CollectionMapper.class, CollectionsDAO.CollectionWithFiltersMapper.class, CollectionsDAO.CollectionWarcMapper.class})
public interface CollectionsDAO {

    class CollectionMapper implements ResultSetMapper<Collection> {
        @Override
        public Collection map(int index, ResultSet rs, StatementContext ctx) throws SQLException {
            return new Collection(rs);
        }
    }

    class CollectionWithFiltersMapper implements ResultSetMapper<CollectionWithFilters> {
        @Override
        public CollectionWithFilters map(int index, ResultSet rs, StatementContext ctx) throws SQLException {
            return new CollectionWithFilters(rs);
        }
    }

    @SqlUpdate("SELECT COUNT(*) FROM collection")
    long countCollections();

    @SqlQuery("SELECT * FROM collection ORDER BY name")
    List<Collection> listCollections();

    @SqlQuery("SELECT * FROM collection ORDER BY name LIMIT :limit OFFSET :offset")
    List<Collection> paginateCollections(@Bind("limit") long limit, @Bind("offset") long offset);

    @SqlUpdate("UPDATE collection SET records = records + :records, record_bytes = record_bytes + :bytes WHERE id = :id")
    int incrementRecordStatsForCollection(@Bind("id") long collectionId, @Bind("records") long records, @Bind("bytes") long bytes);

    @SqlQuery("SELECT collection.*, collection_series.url_filters FROM collection_series LEFT JOIN collection ON collection.id = collection_id WHERE crawl_series_id = :it")
    List<CollectionWithFilters> listCollectionsForCrawlSeries(@Bind long crawlSeriesId);

    @SqlQuery("SELECT * FROM collection WHERE id = :id")
    Collection findCollection(@Bind("id") long id);

    @SqlUpdate("INSERT INTO collection(name, description, cdx_url, solr_url) VALUES (:name, :description, :cdxUrl, :solrUrl)")
    @GetGeneratedKeys
    long createCollection(@BindBean Collection collection);

    @SqlUpdate("UPDATE collection SET name = :coll.name, description = :coll.description, cdx_url = :coll.cdxUrl, solr_url = :coll.solrUrl WHERE id = :id")
    int updateCollection(@Bind("id") long collectionId, @BindBean("coll") Collection coll);

    class CollectionWarc {
        public final long collectionId;
        public final long warcId;
        public final long records;
        public final long recordBytes;

        public CollectionWarc(ResultSet rs) throws SQLException {
            collectionId = rs.getLong("collection_id");
            warcId = rs.getLong("warc_id");
            records = rs.getLong("records");
            recordBytes = rs.getLong("record_bytes");
        }
    }

    class CollectionWarcMapper implements ResultSetMapper<CollectionWarc> {
        @Override
        public CollectionWarc map(int index, ResultSet r, StatementContext ctx) throws SQLException {
            return new CollectionWarc(r);
        }
    }

    @SqlQuery("SELECT * FROM collection_warc WHERE collection_id = :collectionId AND warc_id = :warcId")
    CollectionWarc findCollectionWarc(@Bind("collectionId") long collectionId, @Bind("warcId") long warcId);

    @SqlUpdate("DELETE FROM collection_warc WHERE collection_id = :collectionId AND warc_id = :warcId")
    int deleteCollectionWarc(@Bind("collectionId") long collectionId, @Bind("warcId") long warcId);

    @SqlUpdate("INSERT INTO collection_warc (collection_id, warc_id, records, record_bytes) VALUES (:collectionId, :warcId, :records, :recordBytes)")
    void insertCollectionWarc(@Bind("collectionId") long collectionId, @Bind("warcId") long warcId, @Bind("records") long records, @Bind("recordBytes") long recordBytes);

}
