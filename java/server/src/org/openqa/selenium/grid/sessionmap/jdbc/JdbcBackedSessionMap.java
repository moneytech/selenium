// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.openqa.selenium.grid.sessionmap.jdbc;

import org.openqa.selenium.Capabilities;
import org.openqa.selenium.ImmutableCapabilities;
import org.openqa.selenium.NoSuchSessionException;
import org.openqa.selenium.events.EventBus;
import org.openqa.selenium.grid.config.Config;
import org.openqa.selenium.grid.config.ConfigException;
import org.openqa.selenium.grid.data.Session;
import org.openqa.selenium.grid.log.LoggingOptions;
import org.openqa.selenium.grid.server.EventBusOptions;
import org.openqa.selenium.grid.sessionmap.SessionMap;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.tracing.EventAttribute;
import org.openqa.selenium.remote.tracing.EventAttributeValue;
import org.openqa.selenium.remote.tracing.Span;
import org.openqa.selenium.remote.tracing.Status;
import org.openqa.selenium.remote.tracing.Tracer;

import java.io.Closeable;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import static org.openqa.selenium.grid.data.SessionClosedEvent.SESSION_CLOSED;

public class JdbcBackedSessionMap extends SessionMap implements Closeable {

  private static final Json JSON = new Json();
  private static final Logger LOG = Logger.getLogger(JdbcBackedSessionMap.class.getName());
  private static final String TABLE_NAME = "sessions_map";
  private static final String SESSION_ID_COL = "session_ids";
  private static final String SESSION_CAPS_COL = "session_caps";
  private static final String SESSION_URI_COL = "session_uri";
  private static final String DATABASE_STATEMENT = "db.statement";
  private static final String DATABASE_OPERATION = "db.operation";
  private final EventBus bus;
  private final Connection connection;

  public JdbcBackedSessionMap(Tracer tracer, Connection jdbcConnection, EventBus bus)  {
    super(tracer);

    Require.nonNull("JDBC Connection Object", jdbcConnection);
    this.bus = Require.nonNull("Event bus", bus);

    this.connection = jdbcConnection;
    this.bus.addListener(SESSION_CLOSED, event -> {
      SessionId id = event.getData(SessionId.class);
      remove(id);
    });
  }

  public static SessionMap create(Config config) {
    Tracer tracer = new LoggingOptions(config).getTracer();
    EventBus bus = new EventBusOptions(config).getEventBus();

    JdbcSessionMapOptions sessionMapOptions = new JdbcSessionMapOptions(config);

    Connection connection;

    try {
      connection = sessionMapOptions.getJdbcConnection();
    } catch (SQLException e) {
      throw new ConfigException(e);
    }

    return new JdbcBackedSessionMap(tracer, connection, bus);
  }

  @Override
  public boolean isReady() {
    try {
      return !connection.isClosed();
    } catch (SQLException throwables) {
      return false;
    }
  }

  @Override
  public boolean add(Session session) {
    Require.nonNull("Session to add", session);

    try (Span span = tracer.getCurrentContext().createSpan(
        "INSERT into  sessions_map (session_ids, session_uri, session_caps) values (?, ?, ?) ")) {
      try (PreparedStatement statement = insertSessionStatement(session)) {
        setCommonSpanAttributes(span);
        span.setAttribute(DATABASE_STATEMENT, statement.toString());
        span.setAttribute(DATABASE_OPERATION, "insert");

        return statement.executeUpdate() >= 1;
      } catch (SQLException e) {
        span.setAttribute("error", true);
        span.setStatus(Status.CANCELLED);
        Map<String, EventAttributeValue> attributeValueMap = new HashMap<>();
        attributeValueMap.put("Error Message", EventAttribute.setValue(e.getMessage()));
        attributeValueMap
            .put("Session id", EventAttribute.setValue(session.getId().toString()));
        span.addEvent("Unable to add session information to the database.", attributeValueMap);

        throw new JdbcException(e);
      }
    }
  }

  @Override
  public Session get(SessionId id) throws NoSuchSessionException {
    Require.nonNull("Session ID", id);

    URI uri = null;
    Capabilities caps = null;
    String rawUri = null;
    Map<String, EventAttributeValue> attributeValueMap = new HashMap<>();
    attributeValueMap
        .put("Session id", EventAttribute.setValue(id.toString()));

    try (Span span = tracer.getCurrentContext().createSpan(
        "SELECT * from  sessions_map where session_ids = ?")) {
      try (PreparedStatement statement = readSessionStatement(id)) {
        setCommonSpanAttributes(span);
        span.setAttribute(DATABASE_STATEMENT, statement.toString());
        span.setAttribute(DATABASE_OPERATION, "select");

        ResultSet sessions = statement.executeQuery();
        if (!sessions.next()) {
          span.setAttribute("error", true);
          span.setStatus(Status.NOT_FOUND);
          span.addEvent("Session id does not exist in the database.", attributeValueMap);

          throw new NoSuchSessionException("Unable to find...");
        }

        rawUri = sessions.getString(SESSION_URI_COL);
        String rawCapabilities = sessions.getString(SESSION_CAPS_COL);

        caps = rawCapabilities == null ?
               new ImmutableCapabilities() :
               JSON.toType(rawCapabilities, Capabilities.class);
        try {
          uri = new URI(rawUri);
        } catch (URISyntaxException e) {
          span.setAttribute("error", true);
          span.setStatus(Status.INTERNAL);
          attributeValueMap.put("Error Message", EventAttribute.setValue(e.getMessage()));
          span.addEvent("Unable to convert session id to uri.", attributeValueMap);

          throw new NoSuchSessionException(
              String.format("Unable to convert session id (%s) to uri: %s", id, rawUri), e);
        }

        return new Session(id, uri, caps);
      } catch (SQLException e) {
        span.setAttribute("error", true);
        span.setStatus(Status.CANCELLED);
        attributeValueMap.put("Error Message", EventAttribute.setValue(e.getMessage()));
        span.addEvent("Unable to get session information from the database.", attributeValueMap);

        throw new JdbcException(e);
      }
    }
  }

  @Override
  public void remove(SessionId id) {
    Require.nonNull("Session ID", id);
    try (Span span = tracer.getCurrentContext().createSpan(
        "DELETE from  sessions_map where session_ids = ?")) {

      try (PreparedStatement statement = getDeleteSqlForSession(id)) {
        setCommonSpanAttributes(span);
        span.setAttribute(DATABASE_STATEMENT, statement.toString());
        span.setAttribute(DATABASE_OPERATION, "delete");
        
        statement.executeUpdate();
      } catch (SQLException e) {
        span.setAttribute("error", true);
        span.setStatus(Status.CANCELLED);
        Map<String, EventAttributeValue> attributeValueMap = new HashMap<>();
        attributeValueMap.put("Error Message", EventAttribute.setValue(e.getMessage()));
        attributeValueMap
            .put("Session id", EventAttribute.setValue(id.toString()));
        span.addEvent("Unable to delete session information from the database.", attributeValueMap);

        throw new JdbcException(e.getMessage());
      }
    }
  }

  @Override
  public void close() {
    try {
      connection.close();
    } catch (SQLException e) {
      LOG.warning("SQL exception while closing JDBC Connection:" + e.getMessage());
    }
  }

  private PreparedStatement insertSessionStatement(Session session) throws SQLException {
    PreparedStatement insertStatement = connection.prepareStatement(
      String.format("insert into %1$s (%2$s, %3$s, %4$s) values (?, ?, ?)",
        TABLE_NAME,
        SESSION_ID_COL,
        SESSION_URI_COL,
        SESSION_CAPS_COL));

    insertStatement.setString(1, session.getId().toString());
    insertStatement.setString(2, session.getUri().toString());
    insertStatement.setString(3, JSON.toJson(session.getCapabilities()));

    return insertStatement;
  }

  private PreparedStatement readSessionStatement(SessionId sessionId) throws SQLException {
    PreparedStatement getSessionsStatement = connection.prepareStatement(
      String.format("select * from %1$s where %2$s = ?",
        TABLE_NAME,
        SESSION_ID_COL));

    getSessionsStatement.setMaxRows(1);
    getSessionsStatement.setString(1, sessionId.toString());

    return getSessionsStatement;
  }

  private PreparedStatement getDeleteSqlForSession(SessionId sessionId) throws SQLException{
    PreparedStatement deleteSessionStatement = connection.prepareStatement(
      String.format("delete from %1$s where %2$s = ?",
        TABLE_NAME,
        SESSION_ID_COL));

    deleteSessionStatement.setString(1, sessionId.toString());

    return deleteSessionStatement;
  }

  private void setCommonSpanAttributes(Span span) {
    span.setAttribute("span.kind", Span.Kind.CLIENT.toString());
    if (JdbcSessionMapOptions.jdbcUser != null) {
      span.setAttribute("db.user", JdbcSessionMapOptions.jdbcUser);
    }
    if (JdbcSessionMapOptions.jdbcUrl != null) {
      span.setAttribute("db.connection_string", JdbcSessionMapOptions.jdbcUrl);
    }
  }
}
