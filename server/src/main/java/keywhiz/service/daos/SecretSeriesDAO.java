/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package keywhiz.service.daos;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.ws.rs.BadRequestException;
import keywhiz.api.model.Group;
import keywhiz.api.model.SecretSeries;
import keywhiz.jooq.tables.records.SecretsRecord;
import keywhiz.service.config.Readonly;
import org.jooq.Configuration;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.SelectQuery;
import org.jooq.impl.DSL;

import static com.google.common.base.Preconditions.checkNotNull;
import static keywhiz.jooq.tables.Accessgrants.ACCESSGRANTS;
import static keywhiz.jooq.tables.Groups.GROUPS;
import static keywhiz.jooq.tables.Secrets.SECRETS;
import static keywhiz.jooq.tables.SecretsContent.SECRETS_CONTENT;
import static org.jooq.impl.DSL.decode;
import static org.jooq.impl.DSL.least;
import static org.jooq.impl.DSL.val;

/**
 * Interacts with 'secrets' table and actions on {@link SecretSeries} entities.
 */
public class SecretSeriesDAO {
  private final DSLContext dslContext;
  private final ObjectMapper mapper;
  private final SecretSeriesMapper secretSeriesMapper;

  private SecretSeriesDAO(DSLContext dslContext, ObjectMapper mapper,
      SecretSeriesMapper secretSeriesMapper) {
    this.dslContext = dslContext;
    this.mapper = mapper;
    this.secretSeriesMapper = secretSeriesMapper;
  }

  long createSecretSeries(String name, String creator, String description, @Nullable String type,
      @Nullable Map<String, String> generationOptions) {
    SecretsRecord r = dslContext.newRecord(SECRETS);

    long now = OffsetDateTime.now().toEpochSecond();

    r.setName(name);
    r.setDescription(description);
    r.setCreatedby(creator);
    r.setCreatedat(now);
    r.setUpdatedby(creator);
    r.setUpdatedat(now);
    r.setType(type);
    if (generationOptions != null) {
      try {
        r.setOptions(mapper.writeValueAsString(generationOptions));
      } catch (JsonProcessingException e) {
        // Serialization of a Map<String, String> can never fail.
        throw Throwables.propagate(e);
      }
    } else {
      r.setOptions("{}");
    }
    r.store();

    return r.getId();
  }

  void updateSecretSeries(long secretId, String name, String creator, String description,
      @Nullable String type,
      @Nullable Map<String, String> generationOptions) {
    long now = OffsetDateTime.now().toEpochSecond();
    if (generationOptions == null) {
      generationOptions = ImmutableMap.of();
    }

    try {
      dslContext.update(SECRETS)
          .set(SECRETS.NAME, name)
          .set(SECRETS.DESCRIPTION, description)
          .set(SECRETS.UPDATEDBY, creator)
          .set(SECRETS.UPDATEDAT, now)
          .set(SECRETS.TYPE, type)
          .set(SECRETS.OPTIONS, mapper.writeValueAsString(generationOptions))
          .where(SECRETS.ID.eq(secretId))
          .execute();
    } catch (JsonProcessingException e) {
      // Serialization of a Map<String, String> can never fail.
      throw Throwables.propagate(e);
    }
  }

  public int setExpiration(long secretContentId, Instant expiration) {
    Field<Long> minExpiration = decode()
        .when(SECRETS_CONTENT.EXPIRY.eq(0L), val(expiration.getEpochSecond()))
        .otherwise(least(SECRETS_CONTENT.EXPIRY, val(expiration.getEpochSecond())));

    return dslContext.update(SECRETS_CONTENT)
        .set(SECRETS_CONTENT.EXPIRY, minExpiration)
        .where(SECRETS_CONTENT.ID.eq(secretContentId))
        .execute();
  }

  public int setHmac(long secretContentId, String hmac) {
    return dslContext.update(SECRETS_CONTENT)
        .set(SECRETS_CONTENT.CONTENT_HMAC, hmac)
        .where(SECRETS_CONTENT.ID.eq(secretContentId))
        .execute();
  }

  public int setCurrentVersion(long secretId, long secretContentId) {
    long checkId;
    long now = OffsetDateTime.now().toEpochSecond();
    Record1<Long> r = dslContext.select(SECRETS_CONTENT.SECRETID)
        .from(SECRETS_CONTENT)
        .where(SECRETS_CONTENT.ID.eq(secretContentId))
        .fetchOne();
    if (r == null) {
      throw new BadRequestException(
          String.format("The requested version %d is not a known version of this secret",
              secretContentId));
    }

    checkId = r.value1();
    if (checkId != secretId) {
      throw new IllegalStateException("inconsistent secrets_content");
    }

    return dslContext.update(SECRETS)
        .set(SECRETS.CURRENT, secretContentId)
        .set(SECRETS.UPDATEDAT, now)
        .where(SECRETS.ID.eq(secretId))
        .execute();
  }

  public Optional<SecretSeries> getSecretSeriesById(long id) {
    SecretsRecord r = dslContext.fetchOne(SECRETS, SECRETS.ID.eq(id));
    return Optional.ofNullable(r).map(secretSeriesMapper::map);
  }

  public Optional<SecretSeries> getSecretSeriesByName(String name) {
    SecretsRecord r = dslContext.fetchOne(SECRETS, SECRETS.NAME.eq(name));
    return Optional.ofNullable(r).map(secretSeriesMapper::map);
  }

  public ImmutableList<SecretSeries> getSecretSeries(@Nullable Long expireMaxTime, Group group) {
    SelectQuery<Record> select = dslContext
        .select().from(SECRETS).join(SECRETS_CONTENT).on(SECRETS.CURRENT.equal(SECRETS_CONTENT.ID))
        .where(SECRETS.CURRENT.isNotNull()).getQuery();

    if (expireMaxTime != null && expireMaxTime > 0) {
      select.addOrderBy(SECRETS_CONTENT.EXPIRY.asc().nullsLast());
      long now = System.currentTimeMillis() / 1000L;
      select.addConditions(SECRETS_CONTENT.EXPIRY.greaterThan(now));
      select.addConditions(SECRETS_CONTENT.EXPIRY.lessOrEqual(expireMaxTime));
    }

    if (group != null) {
      select.addJoin(ACCESSGRANTS, SECRETS.ID.eq(ACCESSGRANTS.SECRETID));
      select.addJoin(GROUPS, GROUPS.ID.eq(ACCESSGRANTS.GROUPID));
      select.addConditions(GROUPS.NAME.eq(group.getName()));
    }
    List<SecretSeries> r = select.fetchInto(SECRETS).map(secretSeriesMapper);
    return ImmutableList.copyOf(r);
  }

  public ImmutableList<SecretSeries> getSecretSeriesBatched(int idx, int num, boolean newestFirst) {
    SelectQuery<Record> select = dslContext
        .select()
        .from(SECRETS)
        .join(SECRETS_CONTENT)
        .on(SECRETS.CURRENT.equal(SECRETS_CONTENT.ID))
        .where(SECRETS.CURRENT.isNotNull())
        .getQuery();
    if (newestFirst) {
      select.addOrderBy(SECRETS.CREATEDAT.desc());
    } else {
      select.addOrderBy(SECRETS.CREATEDAT.asc());
    }
    select.addLimit(idx, num);

    List<SecretSeries> r = select.fetchInto(SECRETS).map(secretSeriesMapper);
    return ImmutableList.copyOf(r);
  }

  public void deleteSecretSeriesByName(String name) {
    long now = OffsetDateTime.now().toEpochSecond();
    dslContext.transaction(configuration -> {
      SecretsRecord r = DSL.using(configuration).fetchOne(SECRETS, SECRETS.NAME.eq(name));
      if (r != null) {
        DSL.using(configuration)
            .update(SECRETS)
            .set(SECRETS.CURRENT, (Long) null)
            .set(SECRETS.UPDATEDAT, now)
            .where(SECRETS.ID.eq(r.getId()))
            .execute();

        DSL.using(configuration)
            .delete(ACCESSGRANTS)
            .where(ACCESSGRANTS.SECRETID.eq(r.getId()))
            .execute();
      }
    });
  }

  public void deleteSecretSeriesById(long id) {
    long now = OffsetDateTime.now().toEpochSecond();
    dslContext.transaction(configuration -> {
      DSL.using(configuration)
          .update(SECRETS)
          .set(SECRETS.CURRENT, (Long) null)
          .set(SECRETS.UPDATEDAT, now)
          .where(SECRETS.ID.eq(id))
          .execute();

      DSL.using(configuration)
          .delete(ACCESSGRANTS)
          .where(ACCESSGRANTS.SECRETID.eq(id))
          .execute();
    });
  }

  public static class SecretSeriesDAOFactory implements DAOFactory<SecretSeriesDAO> {
    private final DSLContext jooq;
    private final DSLContext readonlyJooq;
    private final ObjectMapper objectMapper;
    private final SecretSeriesMapper secretSeriesMapper;

    @Inject public SecretSeriesDAOFactory(DSLContext jooq, @Readonly DSLContext readonlyJooq,
        ObjectMapper objectMapper, SecretSeriesMapper secretSeriesMapper) {
      this.jooq = jooq;
      this.readonlyJooq = readonlyJooq;
      this.objectMapper = objectMapper;
      this.secretSeriesMapper = secretSeriesMapper;
    }

    @Override public SecretSeriesDAO readwrite() {
      return new SecretSeriesDAO(jooq, objectMapper, secretSeriesMapper);
    }

    @Override public SecretSeriesDAO readonly() {
      return new SecretSeriesDAO(readonlyJooq, objectMapper, secretSeriesMapper);
    }

    @Override public SecretSeriesDAO using(Configuration configuration) {
      DSLContext dslContext = DSL.using(checkNotNull(configuration));
      return new SecretSeriesDAO(dslContext, objectMapper, secretSeriesMapper);
    }
  }
}
