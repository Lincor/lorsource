package ru.org.linux.spring.dao;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.org.linux.site.*;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/**
 * Пока замена CommentDeleter в будущем должен содержать остальную часть доступа из Comments
 */

@Repository
public class CommentDao {
  private static final Log logger = LogFactory.getLog(CommentDao.class);

  private static final String replysForComment = "SELECT id FROM comments WHERE replyto=? AND NOT deleted FOR UPDATE";
  private static final String replysForCommentCount = "SELECT count(id) FROM comments WHERE replyto=? AND NOT deleted";
  private static final String deleteComment = "UPDATE comments SET deleted='t' WHERE id=?";
  private static final String insertDelinfo = "INSERT INTO del_info (msgid, delby, reason, deldate) values(?,?,?, CURRENT_TIMESTAMP)";
  private static final String updateScore = "UPDATE users SET score=score+? WHERE id=(SELECT userid FROM comments WHERE id=?)";

  private JdbcTemplate jdbcTemplate;
  private UserDao userDao;

  @Autowired
  public void setJdbcTemplate(DataSource dataSource) {
    jdbcTemplate = new JdbcTemplate(dataSource);
  }

  @Autowired
  public void setUserDao(UserDao userDao) {
    this.userDao = userDao;
  }


  /**
   * Удаляем клментарий, если на комментарий есть ответы - генерируем исключение
   * @param msgid id удаляемого сообщения
   * @param reason причина удаления
   * @param user модератор который удаляет
   * @param scoreBonus кол-во отрезаемого шкворца
   * @throws ScriptErrorException генерируем исключение если на комментарий есть ответы
   */
  @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
  public void deleteComment(int msgid, String reason, User user, int scoreBonus) throws ScriptErrorException {
    if (getReplaysCount(msgid) != 0) {
        throw new ScriptErrorException("Нельзя удалить комментарий с ответами");
    }
    doDeleteComment(msgid, reason, user, scoreBonus);
  }

  /**
   * Удаляем клментарий, если на комментарий есть ответы генерируем исключений SQL, используется в
   * массовом удалении коментариев @see UserDao
   * @param msgid id удаляемого сообщения
   * @param reason причина удаления
   * @param user модератор который удаляет
   * @param scoreBonus кол-во отрезаемого шкворца
   * @throws SQLException генерируем исключение если на комментарий есть ответы
   */
  @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
  public void deleteCommentWithSQLException(int msgid, String reason, User user, int scoreBonus) throws SQLException {
    deleteCommentWithoutTransaction(msgid, reason, user, scoreBonus);
  }

  private void deleteCommentWithoutTransaction(int msgid, String reason, User user, int scoreBonus) throws SQLException {
    if (getReplaysCount(msgid) != 0) {
        throw new SQLException("Нельзя удалить комментарий с ответами");
    }
    doDeleteComment(msgid, reason, user, scoreBonus);
  }

  private void doDeleteComment(int msgid, String reason, User user, int scoreBonus) {
    jdbcTemplate.update(deleteComment, msgid);
    jdbcTemplate.update(insertDelinfo, msgid, user.getId(), reason+" ("+scoreBonus+')');
    jdbcTemplate.update(updateScore, scoreBonus, msgid);
    logger.info("Удалено сообщение " + msgid + " пользователем " + user.getNick() + " по причине `" + reason + '\'');
  }

  public List<Integer> deleteReplys(int msgid, User user, boolean score)  {
    return deleteReplys(msgid, user, score, 0);
  }

  private List<Integer> deleteReplys(int msgid, User user, boolean score, int depth) {
    List<Integer> replys = getReplys(msgid);
    List<Integer> deleted = new LinkedList<Integer>();
    for (Integer r : replys) {
      deleted.addAll(deleteReplys(r, user, score, depth+1));
      deleted.add(r);
      switch (depth) {
        case 0:
          if (score) {
            doDeleteComment(r, "7.1 Ответ на некорректное сообщение (авто, уровень 0)", user, -2);
          } else {
            doDeleteComment(r, "7.1 Ответ на некорректное сообщение (авто)", user, 0);
          }
          break;
        case 1:
          if (score) {
            doDeleteComment(r, "7.1 Ответ на некорректное сообщение (авто, уровень 1)", user, -1);
          } else {
            doDeleteComment(r, "7.1 Ответ на некорректное сообщение (авто)", user, 0);
          }
          break;
        default:
          doDeleteComment(r, "7.1 Ответ на некорректное сообщение (авто, уровень >1)", user, 0);
          break;
      }
    }

    return deleted;
  }

  /**
   * Какие ответы на комментарий
   * @param msgid id комментария
   * @return список ответов на комментарий
   */
  public List<Integer> getReplys(int msgid){
    return jdbcTemplate.query(replysForComment, new RowMapper<Integer>() {
      @Override
      public Integer mapRow(ResultSet rs, int rowNum) throws SQLException {
        return rs.getInt(1);
      }
    },msgid);
  }

  /**
   * Сколько ответов на комментарий
   * @param msgid id комментария
   * @return число ответов на комментарий
   */
  public int getReplaysCount(int msgid) {
    return jdbcTemplate.queryForInt(replysForCommentCount, msgid);
  }

  /**
   * Блокировка и массивное удаление всех топиков и комментариев пользователя со всеми ответами на комментарии
   * @param user пользователь для экзекуции
   * @param moderator экзекутор-модератор
   * @param reason прична блокировки
   * @return список удаленных комментариев
   * @throws UserNotFoundException генерирует исключение если пользователь отсутствует
   */
  @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
  public List<Integer> deleteAllCommentsAndBlock(final User user, final User moderator, String reason) throws UserNotFoundException {
    final List<Integer> deleted = new LinkedList<Integer>();

    userDao.blockWithoutTransaction(user, moderator, reason);

    // Удаляем все топики
    jdbcTemplate.query("SELECT id FROM topics WHERE userid=? AND not deleted FOR UPDATE",
        new RowCallbackHandler(){
          @Override
          public void processRow(ResultSet rs) throws SQLException {
            int mid = rs.getInt("id");
            jdbcTemplate.update("UPDATE topics SET deleted='t',sticky='f' WHERE id=?", mid);
            jdbcTemplate.update("INSERT INTO del_info (msgid, delby, reason, deldate) values(?,?,?, CURRENT_TIMESTAMP)",
                mid, moderator.getId(), "Блокировка пользователя с удалением сообщений");
          }
        },
        user.getId());

    // Удаляем все комментарии
    jdbcTemplate.query("SELECT id FROM comments WHERE userid=? AND not deleted ORDER BY id DESC FOR update",
        new RowCallbackHandler() {
          @Override
          public void processRow(ResultSet resultSet) throws SQLException {
            int msgid = resultSet.getInt("id");
            deleted.add(msgid);
            deleted.addAll(deleteReplys(msgid, moderator, false));
            deleteCommentWithoutTransaction(msgid, "Блокировка пользователя с удалением сообщений", moderator, 0);
          }
        },
        user.getId());

    return deleted;
  }

  /**
   * Удаление топиков, сообщений по ip и за определнный период времени, те комментарии на которые существуют ответы пропускаем
   * @param ip ip для которых удаляем сообщения (не проверяется на корректность)
   * @param timedelta врменной промежуток удаления (не проверяется на корректность)
   * @param moderator экзекутор-можератор
   * @param reason причина удаления, которая будет вписана для удаляемых топиков
   * @return список id удаленных сообщений
   */
  @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
  public DeleteCommentResult deleteCommentsByIPAddress(final String ip, final Timestamp timedelta, final User moderator, final String reason) {

    final List<Integer> deletedTopicIds = new ArrayList<Integer>();
    final List<Integer> deletedCommentIds = new ArrayList<Integer>();

    final Map<Integer, String> deleteInfo = new HashMap<Integer, String>();
    // Удаляем топики
    jdbcTemplate.query("SELECT id FROM topics WHERE postip=?::inet AND not deleted AND postdate>? FOR UPDATE",
        new RowCallbackHandler() {
          @Override
          public void processRow(ResultSet resultSet) throws SQLException {
            int msgid = resultSet.getInt("id");
            deletedTopicIds.add(msgid);
            deleteInfo.put(msgid, "Топик " + msgid + " удален");
            jdbcTemplate.update("UPDATE topics SET deleted='t',sticky='f' WHERE id=?", msgid);
            jdbcTemplate.update("INSERT INTO del_info (msgid, delby, reason, deldate) values(?,?,?, CURRENT_TIMESTAMP)",
                msgid, moderator.getId(), reason);
          }
        },
        ip, timedelta);
    // Удаляем комментарии если на них нет ответа
    jdbcTemplate.query("SELECT id FROM comments WHERE postip=?::inet AND not deleted AND postdate>? ORDER BY id DESC FOR update",
        new RowCallbackHandler() {
          @Override
          public void processRow(ResultSet resultSet) throws SQLException {
            int msgid = resultSet.getInt("id");
            if (getReplaysCount(msgid) == 0) {
              deletedCommentIds.add(msgid);
              deleteInfo.put(msgid, "Комментарий " + msgid + " удален");
              deleteCommentWithoutTransaction(msgid, reason, moderator, 0);
            } else {
              deleteInfo.put(msgid, "Комментарий " + msgid + " пропущен");
            }
          }
        },
        ip, timedelta);

    return new DeleteCommentResult(deletedTopicIds, deletedCommentIds, deleteInfo);
  }

  public Comment getComment(final int msgid) throws MessageNotFoundException {
    Comment comment = jdbcTemplate.execute(new ConnectionCallback<Comment>() {
      @Override
      public Comment doInConnection(Connection con) throws SQLException, DataAccessException {
        return getCommentInternal(con, msgid);
      }
    });

    if (comment==null) {
      throw new MessageNotFoundException(msgid);
    } else {
      return comment;
    }
  }

  @Deprecated
  public static Comment getComment(Connection db, int msgid) throws SQLException, MessageNotFoundException {
    Comment comment = getCommentInternal(db, msgid);

    if (comment==null) {
      throw new MessageNotFoundException(msgid);
    }

    return comment;
  }

  private static Comment getCommentInternal(Connection db, int msgid) throws SQLException {
    Statement st = db.createStatement();
    ResultSet rs = null;

    try {
      rs = st.executeQuery("SELECT " +
              "postdate, topic, users.id as userid, comments.id as msgid, comments.title, " +
              "deleted, replyto, user_agents.name AS useragent, comments.postip " +
              "FROM comments " +
              "INNER JOIN users ON (users.id=comments.userid) " +
              "LEFT JOIN user_agents ON (user_agents.id=comments.ua_id) " +
              "WHERE comments.id=" + msgid);

      if (!rs.next()) {
        return null;
      }

      return new Comment(db, rs);
    } finally {
      JdbcUtils.closeResultSet(rs);
      JdbcUtils.closeStatement(st);
    }
  }
}
