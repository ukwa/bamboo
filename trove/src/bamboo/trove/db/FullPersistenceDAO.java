package bamboo.trove.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

import org.apache.commons.math3.util.Pair;
import org.skife.jdbi.v2.StatementContext;
import org.skife.jdbi.v2.sqlobject.Bind;
import org.skife.jdbi.v2.sqlobject.SqlQuery;
import org.skife.jdbi.v2.sqlobject.SqlUpdate;
import org.skife.jdbi.v2.sqlobject.customizers.RegisterMapper;
import org.skife.jdbi.v2.tweak.ResultSetMapper;

@RegisterMapper({FullPersistenceDAO.ErrorMapper.class, FullPersistenceDAO.OldErrorMapper.class})
public interface FullPersistenceDAO {
  public static final String ID_TABLE = "index_persistance_web_archives";
  public static final String ID_COLUMN = "last_warc_id";
  public static final String ERROR_TABLE = "index_persistance_web_archives_errors";
  public static final String ERROR_ID_COLUMN = "warc_id";
  public static final String ERROR_RETRY_COLUMN = "retries";
  public static final String ERROR_TIME_COLUMN = "last_error";

  class ErrorMapper implements ResultSetMapper<Pair<Timestamp, Integer>> {
    @Override
    public Pair<Timestamp, Integer> map(int i, ResultSet resultSet, StatementContext statementContext) throws SQLException {
      return new Pair<>(resultSet.getTimestamp(1), resultSet.getInt(2));
    }
  }

  class OldErrorMapper implements ResultSetMapper<OldError> {
    @Override
    public OldError map(int i, ResultSet resultSet, StatementContext statementContext) throws SQLException {
      return new OldError(resultSet.getLong(1), new Pair<>(resultSet.getTimestamp(2), resultSet.getInt(3)));
    }
  }

  @SqlUpdate("UPDATE " + ID_TABLE + " SET " + ID_COLUMN + " = :lastId")
  public void updateLastId(@Bind("lastId") long lastId);

  @SqlQuery("SELECT " + ID_COLUMN + " FROM " + ID_TABLE)
  public long getLastId();

  @SqlUpdate("INSERT INTO " + ERROR_TABLE + " (" + ERROR_ID_COLUMN + ") VALUES (:warcId)" 
          + " ON DUPLICATE KEY UPDATE " + ERROR_RETRY_COLUMN + " = " + ERROR_RETRY_COLUMN + " + 1")
  public void trackError(@Bind("warcId") long warcId);

  @SqlUpdate("DELETE FROM " + ERROR_TABLE + " WHERE " + ERROR_ID_COLUMN + " = :warcId")
  public void removeError(@Bind("warcId") long warcId);

  @SqlQuery("SELECT " + ERROR_TIME_COLUMN + ", " + ERROR_RETRY_COLUMN + " FROM " + ERROR_TABLE
          + " WHERE " +  ERROR_ID_COLUMN + " = :warcId")
  public Pair<Timestamp, Integer> checkError(@Bind("warcId") long warcId);

  @SqlQuery("SELECT " + ERROR_ID_COLUMN + ", " + ERROR_TIME_COLUMN + ", " + ERROR_RETRY_COLUMN
          + " FROM " + ERROR_TABLE + " ORDER BY " +  ERROR_ID_COLUMN + " desc")
  public List<OldError> oldErrors();

  public class OldError {
    public final Long warcId;
    public final Pair<Timestamp, Integer> error;
    public OldError(Long warcId, Pair<Timestamp, Integer> error) {
      this.warcId = warcId;
      this.error = error;
    }
  }
}
