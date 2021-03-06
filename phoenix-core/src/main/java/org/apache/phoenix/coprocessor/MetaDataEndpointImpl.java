/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.coprocessor;

import static com.google.common.collect.Lists.newArrayList;
import static org.apache.hadoop.hbase.filter.CompareFilter.CompareOp.EQUAL;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.ARRAY_SIZE_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.COLUMN_COUNT_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.COLUMN_NAME_INDEX;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.COLUMN_SIZE_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.DATA_TABLE_NAME_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.DATA_TYPE_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.DECIMAL_DIGITS_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.DEFAULT_COLUMN_FAMILY_NAME_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.DISABLE_WAL_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.FAMILY_NAME_INDEX;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.IMMUTABLE_ROWS_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.INDEX_STATE_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.LINK_TYPE_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.MULTI_TENANT_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.NULLABLE_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.ORDINAL_POSITION_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.PK_NAME_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.SALT_BUCKETS_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.SCHEMA_NAME_INDEX;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.SORT_ORDER_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.TABLE_FAMILY_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.TABLE_NAME_INDEX;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.TABLE_SEQ_NUM_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.TABLE_TYPE_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.TENANT_ID_INDEX;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.VIEW_CONSTANT_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.VIEW_INDEX_ID_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.VIEW_STATEMENT_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.VIEW_TYPE_BYTES;
import static org.apache.phoenix.schema.PTableType.INDEX;
import static org.apache.phoenix.util.SchemaUtil.getVarCharLength;
import static org.apache.phoenix.util.SchemaUtil.getVarChars;

import java.io.IOException;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.Coprocessor;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.KeyValue.Type;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.coprocessor.CoprocessorException;
import org.apache.hadoop.hbase.coprocessor.CoprocessorService;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterBase;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.FirstKeyOnlyFilter;
import org.apache.hadoop.hbase.filter.SingleColumnValueFilter;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.HRegion.RowLock;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.phoenix.cache.GlobalCache;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.AddColumnRequest;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.ClearCacheRequest;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.ClearCacheResponse;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.CreateTableRequest;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.DropColumnRequest;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.DropTableRequest;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.GetTableRequest;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.GetVersionRequest;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.GetVersionResponse;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.MetaDataResponse;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.UpdateIndexStateRequest;
import org.apache.phoenix.hbase.index.util.GenericKeyValueBuilder;
import org.apache.phoenix.hbase.index.util.ImmutableBytesPtr;
import org.apache.phoenix.hbase.index.util.IndexManagementUtil;
import org.apache.phoenix.jdbc.PhoenixDatabaseMetaData;
import org.apache.phoenix.protobuf.ProtobufUtil;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.schema.AmbiguousColumnException;
import org.apache.phoenix.schema.ColumnFamilyNotFoundException;
import org.apache.phoenix.schema.ColumnNotFoundException;
import org.apache.phoenix.schema.PColumn;
import org.apache.phoenix.schema.PColumnFamily;
import org.apache.phoenix.schema.PColumnImpl;
import org.apache.phoenix.schema.PDataType;
import org.apache.phoenix.schema.PIndexState;
import org.apache.phoenix.schema.PName;
import org.apache.phoenix.schema.PNameFactory;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.PTable.LinkType;
import org.apache.phoenix.schema.PTable.ViewType;
import org.apache.phoenix.schema.PTableImpl;
import org.apache.phoenix.schema.PTableType;
import org.apache.phoenix.schema.SortOrder;
import org.apache.phoenix.schema.TableNotFoundException;
import org.apache.phoenix.util.ByteUtil;
import org.apache.phoenix.util.IndexUtil;
import org.apache.phoenix.util.KeyValueUtil;
import org.apache.phoenix.util.MetaDataUtil;
import org.apache.phoenix.util.SchemaUtil;
import org.apache.phoenix.util.ServerUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.Cache;
import com.google.common.collect.Lists;
import com.google.protobuf.RpcCallback;
import com.google.protobuf.RpcController;
import com.google.protobuf.Service;

/**
 * 
 * Endpoint co-processor through which all Phoenix metadata mutations flow.
 * We only allow mutations to the latest version of a Phoenix table (i.e. the
 * timeStamp must be increasing).
 * For adding/dropping columns use a sequence number on the table to ensure that
 * the client has the latest version.
 * The timeStamp on the table correlates with the timeStamp on the data row.
 * TODO: we should enforce that a metadata mutation uses a timeStamp bigger than
 * any in use on the data table, b/c otherwise we can end up with data rows that
 * are not valid against a schema row.
 *
 * 
 * @since 0.1
 */
public class MetaDataEndpointImpl extends MetaDataProtocol implements CoprocessorService, Coprocessor {
    private static final Logger logger = LoggerFactory.getLogger(MetaDataEndpointImpl.class);

    // KeyValues for Table
    private static final KeyValue TABLE_TYPE_KV = KeyValue.createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, TABLE_TYPE_BYTES);
    private static final KeyValue TABLE_SEQ_NUM_KV = KeyValue.createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, TABLE_SEQ_NUM_BYTES);
    private static final KeyValue COLUMN_COUNT_KV = KeyValue.createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, COLUMN_COUNT_BYTES);
    private static final KeyValue SALT_BUCKETS_KV = KeyValue.createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, SALT_BUCKETS_BYTES);
    private static final KeyValue PK_NAME_KV = KeyValue.createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, PK_NAME_BYTES);
    private static final KeyValue DATA_TABLE_NAME_KV = KeyValue.createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, DATA_TABLE_NAME_BYTES);
    private static final KeyValue INDEX_STATE_KV = KeyValue.createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, INDEX_STATE_BYTES);
    private static final KeyValue IMMUTABLE_ROWS_KV = KeyValue.createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, IMMUTABLE_ROWS_BYTES);
    private static final KeyValue VIEW_EXPRESSION_KV = KeyValue.createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, VIEW_STATEMENT_BYTES);
    private static final KeyValue DEFAULT_COLUMN_FAMILY_KV = KeyValue.createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, DEFAULT_COLUMN_FAMILY_NAME_BYTES);
    private static final KeyValue DISABLE_WAL_KV = KeyValue.createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, DISABLE_WAL_BYTES);
    private static final KeyValue MULTI_TENANT_KV = KeyValue.createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, MULTI_TENANT_BYTES);
    private static final KeyValue VIEW_TYPE_KV = KeyValue.createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, VIEW_TYPE_BYTES);
    private static final KeyValue VIEW_INDEX_ID_KV = KeyValue.createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, VIEW_INDEX_ID_BYTES);
    private static final List<KeyValue> TABLE_KV_COLUMNS = Arrays.<KeyValue>asList(
            TABLE_TYPE_KV,
            TABLE_SEQ_NUM_KV,
            COLUMN_COUNT_KV,
            SALT_BUCKETS_KV,
            PK_NAME_KV,
            DATA_TABLE_NAME_KV,
            INDEX_STATE_KV,
            IMMUTABLE_ROWS_KV,
            VIEW_EXPRESSION_KV,
            DEFAULT_COLUMN_FAMILY_KV,
            DISABLE_WAL_KV,
            MULTI_TENANT_KV,
            VIEW_TYPE_KV,
            VIEW_INDEX_ID_KV
            );
    static {
        Collections.sort(TABLE_KV_COLUMNS, KeyValue.COMPARATOR);
    }
    private static final int TABLE_TYPE_INDEX = TABLE_KV_COLUMNS.indexOf(TABLE_TYPE_KV);
    private static final int TABLE_SEQ_NUM_INDEX = TABLE_KV_COLUMNS.indexOf(TABLE_SEQ_NUM_KV);
    private static final int COLUMN_COUNT_INDEX = TABLE_KV_COLUMNS.indexOf(COLUMN_COUNT_KV);
    private static final int SALT_BUCKETS_INDEX = TABLE_KV_COLUMNS.indexOf(SALT_BUCKETS_KV);
    private static final int PK_NAME_INDEX = TABLE_KV_COLUMNS.indexOf(PK_NAME_KV);
    private static final int DATA_TABLE_NAME_INDEX = TABLE_KV_COLUMNS.indexOf(DATA_TABLE_NAME_KV);
    private static final int INDEX_STATE_INDEX = TABLE_KV_COLUMNS.indexOf(INDEX_STATE_KV);
    private static final int IMMUTABLE_ROWS_INDEX = TABLE_KV_COLUMNS.indexOf(IMMUTABLE_ROWS_KV);
    private static final int VIEW_STATEMENT_INDEX = TABLE_KV_COLUMNS.indexOf(VIEW_EXPRESSION_KV);
    private static final int DEFAULT_COLUMN_FAMILY_INDEX = TABLE_KV_COLUMNS.indexOf(DEFAULT_COLUMN_FAMILY_KV);
    private static final int DISABLE_WAL_INDEX = TABLE_KV_COLUMNS.indexOf(DISABLE_WAL_KV);
    private static final int MULTI_TENANT_INDEX = TABLE_KV_COLUMNS.indexOf(MULTI_TENANT_KV);
    private static final int VIEW_TYPE_INDEX = TABLE_KV_COLUMNS.indexOf(VIEW_TYPE_KV);
    private static final int VIEW_INDEX_ID_INDEX = TABLE_KV_COLUMNS.indexOf(VIEW_INDEX_ID_KV);
    
    // KeyValues for Column
    private static final KeyValue DECIMAL_DIGITS_KV = KeyValue.createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, DECIMAL_DIGITS_BYTES);
    private static final KeyValue COLUMN_SIZE_KV = KeyValue.createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, COLUMN_SIZE_BYTES);
    private static final KeyValue NULLABLE_KV = KeyValue.createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, NULLABLE_BYTES);
    private static final KeyValue DATA_TYPE_KV = KeyValue.createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, DATA_TYPE_BYTES);
    private static final KeyValue ORDINAL_POSITION_KV = KeyValue.createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, ORDINAL_POSITION_BYTES);
    private static final KeyValue SORT_ORDER_KV = KeyValue.createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, SORT_ORDER_BYTES);
    private static final KeyValue ARRAY_SIZE_KV = KeyValue.createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, ARRAY_SIZE_BYTES);
    private static final KeyValue VIEW_CONSTANT_KV = KeyValue.createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, VIEW_CONSTANT_BYTES);
    private static final List<KeyValue> COLUMN_KV_COLUMNS = Arrays.<KeyValue>asList(
            DECIMAL_DIGITS_KV,
            COLUMN_SIZE_KV,
            NULLABLE_KV,
            DATA_TYPE_KV,
            ORDINAL_POSITION_KV,
            SORT_ORDER_KV,
            DATA_TABLE_NAME_KV, // included in both column and table row for metadata APIs
            ARRAY_SIZE_KV,
            VIEW_CONSTANT_KV
            );
    static {
        Collections.sort(COLUMN_KV_COLUMNS, KeyValue.COMPARATOR);
    }
    private static final int DECIMAL_DIGITS_INDEX = COLUMN_KV_COLUMNS.indexOf(DECIMAL_DIGITS_KV);
    private static final int COLUMN_SIZE_INDEX = COLUMN_KV_COLUMNS.indexOf(COLUMN_SIZE_KV);
    private static final int NULLABLE_INDEX = COLUMN_KV_COLUMNS.indexOf(NULLABLE_KV);
    private static final int DATA_TYPE_INDEX = COLUMN_KV_COLUMNS.indexOf(DATA_TYPE_KV);
    private static final int ORDINAL_POSITION_INDEX = COLUMN_KV_COLUMNS.indexOf(ORDINAL_POSITION_KV);
    private static final int SORT_ORDER_INDEX = COLUMN_KV_COLUMNS.indexOf(SORT_ORDER_KV);
    private static final int ARRAY_SIZE_INDEX = COLUMN_KV_COLUMNS.indexOf(ARRAY_SIZE_KV);
    private static final int VIEW_CONSTANT_INDEX = COLUMN_KV_COLUMNS.indexOf(VIEW_CONSTANT_KV);
    
    private static final int LINK_TYPE_INDEX = 0;

    private static PName newPName(byte[] keyBuffer, int keyOffset, int keyLength) {
        if (keyLength <= 0) {
            return null;
        }
        int length = getVarCharLength(keyBuffer, keyOffset, keyLength);
        return PNameFactory.newName(keyBuffer, keyOffset, length);
    }

    private static Scan newTableRowsScan(byte[] key, long startTimeStamp, long stopTimeStamp)
            throws IOException {
        Scan scan = new Scan();
        scan.setTimeRange(startTimeStamp, stopTimeStamp);
        scan.setStartRow(key);
        byte[] stopKey = ByteUtil.concat(key, QueryConstants.SEPARATOR_BYTE_ARRAY);
        ByteUtil.nextKey(stopKey, stopKey.length);
        scan.setStopRow(stopKey);
        return scan;
    }

    private RegionCoprocessorEnvironment env;

    private static MetaDataMutationResult checkTableKeyInRegion(byte[] key, HRegion region) {
        byte[] startKey = region.getStartKey();
        byte[] endKey = region.getEndKey();
        if (Bytes.compareTo(startKey, key) <= 0
                && (Bytes.compareTo(HConstants.LAST_ROW, endKey) == 0 || Bytes.compareTo(key,
                    endKey) < 0)) {
            return null; // normal case;
        }
        return new MetaDataMutationResult(MutationCode.TABLE_NOT_IN_REGION,
                EnvironmentEdgeManager.currentTimeMillis(), null);
    }

    /**
     * Stores a reference to the coprocessor environment provided by the
     * {@link org.apache.hadoop.hbase.regionserver.RegionCoprocessorHost} from the region where this
     * coprocessor is loaded. Since this is a coprocessor endpoint, it always expects to be loaded
     * on a table region, so always expects this to be an instance of
     * {@link RegionCoprocessorEnvironment}.
     * @param env the environment provided by the coprocessor host
     * @throws IOException if the provided environment is not an instance of
     *             {@code RegionCoprocessorEnvironment}
     */
    @Override
    public void start(CoprocessorEnvironment env) throws IOException {
        if (env instanceof RegionCoprocessorEnvironment) {
            this.env = (RegionCoprocessorEnvironment) env;
        } else {
            throw new CoprocessorException("Must be loaded on a table region!");
        }
    }

    @Override
    public void stop(CoprocessorEnvironment env) throws IOException {
        // nothing to do
    }

    @Override
    public Service getService() {
        return this;
    }

    @Override
    public void getTable(RpcController controller, GetTableRequest request,
            RpcCallback<MetaDataResponse> done) {
        MetaDataResponse.Builder builder = MetaDataResponse.newBuilder();
        byte[] tenantId = request.getTenantId().toByteArray();
        byte[] schemaName = request.getSchemaName().toByteArray();
        byte[] tableName = request.getTableName().toByteArray();
        byte[] key = SchemaUtil.getTableKey(tenantId, schemaName, tableName);
        long tableTimeStamp = request.getTableTimestamp();

        try {
            // TODO: check that key is within region.getStartKey() and region.getEndKey()
            // and return special code to force client to lookup region from meta.
            HRegion region = env.getRegion();
            MetaDataMutationResult result = checkTableKeyInRegion(key, region);
            if (result != null) {
                done.run(MetaDataMutationResult.toProto(result));
                return;
            }

            long currentTime = EnvironmentEdgeManager.currentTimeMillis();
            PTable table = doGetTable(key, request.getClientTimestamp());
            if (table == null) {
                builder.setReturnCode(MetaDataProtos.MutationCode.TABLE_NOT_FOUND);
                builder.setMutationTime(currentTime);
                done.run(builder.build());
                return;
            }
            builder.setReturnCode(MetaDataProtos.MutationCode.TABLE_ALREADY_EXISTS);
            builder.setMutationTime(currentTime);
            if (table.getTimeStamp() != tableTimeStamp) {
                builder.setTable(PTableImpl.toProto(table));
            }
            done.run(builder.build());
            return;
        } catch (Throwable t) {
        	logger.error("getTable failed", t);
            ProtobufUtil.setControllerException(controller,
                ServerUtil.createIOException(SchemaUtil.getTableName(schemaName, tableName), t));
        }
    }

    private PTable doGetTable(byte[] key, long clientTimeStamp) throws IOException, SQLException {
        ImmutableBytesPtr cacheKey = new ImmutableBytesPtr(key);
        Cache<ImmutableBytesPtr, PTable> metaDataCache =
                GlobalCache.getInstance(this.env).getMetaDataCache();
        PTable table = metaDataCache.getIfPresent(cacheKey);
        // We only cache the latest, so we'll end up building the table with every call if the
        // client connection has specified an SCN.
        // TODO: If we indicate to the client that we're returning an older version, but there's a
        // newer version available, the client
        // can safely not call this, since we only allow modifications to the latest.
        if (table != null && table.getTimeStamp() < clientTimeStamp) {
            // Table on client is up-to-date with table on server, so just return
            if (isTableDeleted(table)) {
                return null;
            }
            return table;
        }
        // Ask Lars about the expense of this call - if we don't take the lock, we still won't get
        // partial results
        // get the co-processor environment
        // TODO: check that key is within region.getStartKey() and region.getEndKey()
        // and return special code to force client to lookup region from meta.
        HRegion region = env.getRegion();
        /*
         * Lock directly on key, though it may be an index table. This will just prevent a table
         * from getting rebuilt too often.
         */
        RowLock rowLock = region.getRowLock(key);
        if (rowLock == null) {
            throw new IOException("Failed to acquire lock on " + Bytes.toStringBinary(key));
        }
        try {
            // Try cache again in case we were waiting on a lock
            table = metaDataCache.getIfPresent(cacheKey);
            // We only cache the latest, so we'll end up building the table with every call if the
            // client connection has specified an SCN.
            // TODO: If we indicate to the client that we're returning an older version, but there's
            // a newer version available, the client
            // can safely not call this, since we only allow modifications to the latest.
            if (table != null && table.getTimeStamp() < clientTimeStamp) {
                // Table on client is up-to-date with table on server, so just return
                if (isTableDeleted(table)) {
                    return null;
                }
                return table;
            }
            // Query for the latest table first, since it's not cached
            table = buildTable(key, cacheKey, region, HConstants.LATEST_TIMESTAMP);
            if (table != null && table.getTimeStamp() < clientTimeStamp) {
                return table;
            }
            // Otherwise, query for an older version of the table - it won't be cached
            return buildTable(key, cacheKey, region, clientTimeStamp);
        } finally {
            rowLock.release();
        }
    }

    private PTable buildTable(byte[] key, ImmutableBytesPtr cacheKey, HRegion region,
            long clientTimeStamp) throws IOException, SQLException {
        Scan scan = newTableRowsScan(key, MIN_TABLE_TIMESTAMP, clientTimeStamp);
        RegionScanner scanner = region.getScanner(scan);

        Cache<ImmutableBytesPtr,PTable> metaDataCache = GlobalCache.getInstance(this.env).getMetaDataCache();
        try {
            PTable oldTable = metaDataCache.getIfPresent(cacheKey);
            long tableTimeStamp = oldTable == null ? MIN_TABLE_TIMESTAMP-1 : oldTable.getTimeStamp();
            PTable newTable;
            newTable = getTable(scanner, clientTimeStamp, tableTimeStamp);
            if (newTable == null) {
                return null;
            }
            if (oldTable == null || tableTimeStamp < newTable.getTimeStamp()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Caching table "
                            + Bytes.toStringBinary(cacheKey.get(), cacheKey.getOffset(),
                                cacheKey.getLength()) + " at seqNum "
                            + newTable.getSequenceNumber() + " with newer timestamp "
                            + newTable.getTimeStamp() + " versus " + tableTimeStamp);
                }
                metaDataCache.put(cacheKey, newTable);
            }
            return newTable;
        } finally {
            scanner.close();
        }
    }

    private void addIndexToTable(PName tenantId, PName schemaName, PName indexName, PName tableName, long clientTimeStamp, List<PTable> indexes) throws IOException, SQLException {
        byte[] key = SchemaUtil.getTableKey(tenantId == null ? ByteUtil.EMPTY_BYTE_ARRAY : tenantId.getBytes(), schemaName.getBytes(), indexName.getBytes());
        PTable indexTable = doGetTable(key, clientTimeStamp);
        if (indexTable == null) {
            ServerUtil.throwIOException("Index not found", new TableNotFoundException(schemaName.getString(), indexName.getString()));
            return;
        }
        indexes.add(indexTable);
    }

    private void addColumnToTable(List<Cell> results, PName colName, PName famName,
        Cell[] colKeyValues, List<PColumn> columns) {
        int i = 0;
        int j = 0;
        while (i < results.size() && j < COLUMN_KV_COLUMNS.size()) {
            Cell kv = results.get(i);
            Cell searchKv = COLUMN_KV_COLUMNS.get(j);
            int cmp =
                    Bytes.compareTo(kv.getQualifierArray(), kv.getQualifierOffset(),
                        kv.getQualifierLength(), searchKv.getQualifierArray(),
                        searchKv.getQualifierOffset(), searchKv.getQualifierLength());
            if (cmp == 0) {
                colKeyValues[j++] = kv;
                i++;
            } else if (cmp > 0) {
                colKeyValues[j++] = null;
            } else {
                i++; // shouldn't happen - means unexpected KV in system table column row
            }
        }

        if (colKeyValues[DATA_TYPE_INDEX] == null || colKeyValues[NULLABLE_INDEX] == null
                || colKeyValues[ORDINAL_POSITION_INDEX] == null) {
            throw new IllegalStateException("Didn't find all required key values in '"
                    + colName.getString() + "' column metadata row");
        }

        Cell columnSizeKv = colKeyValues[COLUMN_SIZE_INDEX];
        Integer maxLength =
                columnSizeKv == null ? null : PDataType.INTEGER.getCodec().decodeInt(
                    columnSizeKv.getValueArray(), columnSizeKv.getValueOffset(), SortOrder.getDefault());
        Cell decimalDigitKv = colKeyValues[DECIMAL_DIGITS_INDEX];
        Integer scale =
                decimalDigitKv == null ? null : PDataType.INTEGER.getCodec().decodeInt(
                    decimalDigitKv.getValueArray(), decimalDigitKv.getValueOffset(), SortOrder.getDefault());
        Cell ordinalPositionKv = colKeyValues[ORDINAL_POSITION_INDEX];
        int position =
                PDataType.INTEGER.getCodec().decodeInt(ordinalPositionKv.getValueArray(),
                    ordinalPositionKv.getValueOffset(), SortOrder.getDefault());
        Cell nullableKv = colKeyValues[NULLABLE_INDEX];
        boolean isNullable =
                PDataType.INTEGER.getCodec().decodeInt(nullableKv.getValueArray(),
                    nullableKv.getValueOffset(), SortOrder.getDefault()) != ResultSetMetaData.columnNoNulls;
        Cell dataTypeKv = colKeyValues[DATA_TYPE_INDEX];
        PDataType dataType =
                PDataType.fromTypeId(PDataType.INTEGER.getCodec().decodeInt(
                  dataTypeKv.getValueArray(), dataTypeKv.getValueOffset(), SortOrder.getDefault()));
        if (maxLength == null && dataType == PDataType.BINARY) dataType = PDataType.VARBINARY; // For
                                                                                               // backward
                                                                                               // compatibility.
        Cell sortOrderKv = colKeyValues[SORT_ORDER_INDEX];
        SortOrder sortOrder =
        		sortOrderKv == null ? SortOrder.getDefault() : SortOrder.fromSystemValue(PDataType.INTEGER
                        .getCodec().decodeInt(sortOrderKv.getValueArray(),
                        		sortOrderKv.getValueOffset(), SortOrder.getDefault()));
        
        Cell arraySizeKv = colKeyValues[ARRAY_SIZE_INDEX];
        Integer arraySize = arraySizeKv == null ? null : 
          PDataType.INTEGER.getCodec().decodeInt(arraySizeKv.getValueArray(), arraySizeKv.getValueOffset(), SortOrder.getDefault());
 
        Cell viewConstantKv = colKeyValues[VIEW_CONSTANT_INDEX];
        byte[] viewConstant = viewConstantKv == null ? null : viewConstantKv.getValue();
        PColumn column = new PColumnImpl(colName, famName, dataType, maxLength, scale, isNullable,
                        position - 1, sortOrder, arraySize, viewConstant);
        columns.add(column);
    }
    
    private PTable getTable(RegionScanner scanner, long clientTimeStamp, long tableTimeStamp)
        throws IOException, SQLException {
        List<Cell> results = Lists.newArrayList();
        scanner.next(results);
        if (results.isEmpty()) {
            return null;
        }
        Cell[] tableKeyValues = new Cell[TABLE_KV_COLUMNS.size()];
        Cell[] colKeyValues = new Cell[COLUMN_KV_COLUMNS.size()];
    
        // Create PTable based on KeyValues from scan
        Cell keyValue = results.get(0);
        byte[] keyBuffer = keyValue.getRowArray();
        int keyLength = keyValue.getRowLength();
        int keyOffset = keyValue.getRowOffset();
        PName tenantId = newPName(keyBuffer, keyOffset, keyLength);
        int tenantIdLength = (tenantId == null) ? 0 : tenantId.getBytes().length;
        if (tenantIdLength == 0) {
            tenantId = null;
        }
        PName schemaName = newPName(keyBuffer, keyOffset+tenantIdLength+1, keyLength);
        int schemaNameLength = schemaName.getBytes().length;
        int tableNameLength = keyLength - schemaNameLength - 1 - tenantIdLength - 1;
        byte[] tableNameBytes = new byte[tableNameLength];
        System.arraycopy(keyBuffer, keyOffset + schemaNameLength + 1 + tenantIdLength + 1,
            tableNameBytes, 0, tableNameLength);
        PName tableName = PNameFactory.newName(tableNameBytes);
    
        int offset = tenantIdLength + schemaNameLength + tableNameLength + 3;
        // This will prevent the client from continually looking for the current
        // table when we know that there will never be one since we disallow updates
        // unless the table is the latest
        // If we already have a table newer than the one we just found and
        // the client timestamp is less that the existing table time stamp,
        // bump up the timeStamp to right before the client time stamp, since
        // we know it can't possibly change.
        long timeStamp = keyValue.getTimestamp();
        // long timeStamp = tableTimeStamp > keyValue.getTimestamp() &&
        // clientTimeStamp < tableTimeStamp
        // ? clientTimeStamp-1
        // : keyValue.getTimestamp();
    
        int i = 0;
        int j = 0;
        while (i < results.size() && j < TABLE_KV_COLUMNS.size()) {
            Cell kv = results.get(i);
            Cell searchKv = TABLE_KV_COLUMNS.get(j);
            int cmp =
                    Bytes.compareTo(kv.getQualifierArray(), kv.getQualifierOffset(),
                        kv.getQualifierLength(), searchKv.getQualifierArray(),
                        searchKv.getQualifierOffset(), searchKv.getQualifierLength());
            if (cmp == 0) {
                timeStamp = Math.max(timeStamp, kv.getTimestamp()); // Find max timestamp of table
                                                                    // header row
                tableKeyValues[j++] = kv;
                i++;
            } else if (cmp > 0) {
                tableKeyValues[j++] = null;
            } else {
                i++; // shouldn't happen - means unexpected KV in system table header row
            }
        }
        // TABLE_TYPE, TABLE_SEQ_NUM and COLUMN_COUNT are required.
        if (tableKeyValues[TABLE_TYPE_INDEX] == null || tableKeyValues[TABLE_SEQ_NUM_INDEX] == null
                || tableKeyValues[COLUMN_COUNT_INDEX] == null) {
            throw new IllegalStateException(
                    "Didn't find expected key values for table row in metadata row");
        }

        Cell tableTypeKv = tableKeyValues[TABLE_TYPE_INDEX];
        PTableType tableType =
                PTableType
                        .fromSerializedValue(tableTypeKv.getValueArray()[tableTypeKv.getValueOffset()]);
        Cell tableSeqNumKv = tableKeyValues[TABLE_SEQ_NUM_INDEX];
        long tableSeqNum =
                PDataType.LONG.getCodec().decodeLong(tableSeqNumKv.getValueArray(),
                    tableSeqNumKv.getValueOffset(), SortOrder.getDefault());
        Cell columnCountKv = tableKeyValues[COLUMN_COUNT_INDEX];
        int columnCount =
                PDataType.INTEGER.getCodec().decodeInt(columnCountKv.getValueArray(),
                    columnCountKv.getValueOffset(), SortOrder.getDefault());
        Cell pkNameKv = tableKeyValues[PK_NAME_INDEX];
        PName pkName =
                pkNameKv != null ? newPName(pkNameKv.getValueArray(), pkNameKv.getValueOffset(),
                    pkNameKv.getValueLength()) : null;
        Cell saltBucketNumKv = tableKeyValues[SALT_BUCKETS_INDEX];
        Integer saltBucketNum =
                saltBucketNumKv != null ? (Integer) PDataType.INTEGER.getCodec().decodeInt(
                    saltBucketNumKv.getValueArray(), saltBucketNumKv.getValueOffset(), SortOrder.getDefault()) : null;
        Cell dataTableNameKv = tableKeyValues[DATA_TABLE_NAME_INDEX];
        PName dataTableName =
                dataTableNameKv != null ? newPName(dataTableNameKv.getValueArray(),
                    dataTableNameKv.getValueOffset(), dataTableNameKv.getValueLength()) : null;
        Cell indexStateKv = tableKeyValues[INDEX_STATE_INDEX];
        PIndexState indexState =
                indexStateKv == null ? null : PIndexState.fromSerializedValue(indexStateKv
                        .getValueArray()[indexStateKv.getValueOffset()]);
        Cell immutableRowsKv = tableKeyValues[IMMUTABLE_ROWS_INDEX];
        boolean isImmutableRows =
                immutableRowsKv == null ? false : (Boolean) PDataType.BOOLEAN.toObject(
                    immutableRowsKv.getValueArray(), immutableRowsKv.getValueOffset(),
                    immutableRowsKv.getValueLength());
        Cell defaultFamilyNameKv = tableKeyValues[DEFAULT_COLUMN_FAMILY_INDEX];
        PName defaultFamilyName = defaultFamilyNameKv != null ? newPName(defaultFamilyNameKv.getValueArray(), defaultFamilyNameKv.getValueOffset(), defaultFamilyNameKv.getValueLength()) : null;
        Cell viewStatementKv = tableKeyValues[VIEW_STATEMENT_INDEX];
        String viewStatement = viewStatementKv != null ? (String)PDataType.VARCHAR.toObject(viewStatementKv.getValueArray(), viewStatementKv.getValueOffset(), viewStatementKv.getValueLength()) : null;
        Cell disableWALKv = tableKeyValues[DISABLE_WAL_INDEX];
        boolean disableWAL = disableWALKv == null ? PTable.DEFAULT_DISABLE_WAL : Boolean.TRUE.equals(PDataType.BOOLEAN.toObject(disableWALKv.getValueArray(), disableWALKv.getValueOffset(), disableWALKv.getValueLength()));
        Cell multiTenantKv = tableKeyValues[MULTI_TENANT_INDEX];
        boolean multiTenant = multiTenantKv == null ? false : Boolean.TRUE.equals(PDataType.BOOLEAN.toObject(multiTenantKv.getValueArray(), multiTenantKv.getValueOffset(), multiTenantKv.getValueLength()));
        Cell viewTypeKv = tableKeyValues[VIEW_TYPE_INDEX];
        ViewType viewType = viewTypeKv == null ? null : ViewType.fromSerializedValue(viewTypeKv.getValueArray()[viewTypeKv.getValueOffset()]);
        Cell viewIndexIdKv = tableKeyValues[VIEW_INDEX_ID_INDEX];
        Short viewIndexId = viewIndexIdKv == null ? null : (Short)MetaDataUtil.getViewIndexIdDataType().getCodec().decodeShort(viewIndexIdKv.getValueArray(), viewIndexIdKv.getValueOffset(), SortOrder.getDefault());
        
        List<PColumn> columns = Lists.newArrayListWithExpectedSize(columnCount);
        List<PTable> indexes = new ArrayList<PTable>();
        List<PName> physicalTables = new ArrayList<PName>();
        while (true) {
          results.clear();
          scanner.next(results);
          if (results.isEmpty()) {
              break;
          }
          Cell colKv = results.get(LINK_TYPE_INDEX);
          int colKeyLength = colKv.getRowLength();
          PName colName = newPName(colKv.getRowArray(), colKv.getRowOffset() + offset, colKeyLength-offset);
          int colKeyOffset = offset + colName.getBytes().length + 1;
          PName famName = newPName(colKv.getRowArray(), colKv.getRowOffset() + colKeyOffset, colKeyLength-colKeyOffset);
          if (colName.getString().isEmpty() && famName != null) {
              LinkType linkType = LinkType.fromSerializedValue(colKv.getValueArray()[colKv.getValueOffset()]);
              if (linkType == LinkType.INDEX_TABLE) {
                  addIndexToTable(tenantId, schemaName, famName, tableName, clientTimeStamp, indexes);
              } else if (linkType == LinkType.PHYSICAL_TABLE) {
                  physicalTables.add(famName);
              } else {
                  logger.warn("Unknown link type: " + colKv.getValueArray()[colKv.getValueOffset()] + " for " + SchemaUtil.getTableName(schemaName.getString(), tableName.getString()));
              }
          } else {
              addColumnToTable(results, colName, famName, colKeyValues, columns);
          }
        }

        return PTableImpl.makePTable(tenantId, schemaName, tableName, tableType, indexState, timeStamp, 
            tableSeqNum, pkName, saltBucketNum, columns, tableType == INDEX ? dataTableName : null, 
            indexes, isImmutableRows, physicalTables, defaultFamilyName, viewStatement, disableWAL, 
            multiTenant, viewType, viewIndexId);
    }

    private PTable buildDeletedTable(byte[] key, ImmutableBytesPtr cacheKey, HRegion region,
        long clientTimeStamp) throws IOException {
        if (clientTimeStamp == HConstants.LATEST_TIMESTAMP) {
            return null;
        }
    
        Scan scan = newTableRowsScan(key, clientTimeStamp, HConstants.LATEST_TIMESTAMP);
        scan.setFilter(new FirstKeyOnlyFilter());
        scan.setRaw(true);
        RegionScanner scanner = region.getScanner(scan);
        List<Cell> results = Lists.<Cell> newArrayList();
        scanner.next(results);
        // HBase ignores the time range on a raw scan (HBASE-7362)
        if (!results.isEmpty() && results.get(0).getTimestamp() > clientTimeStamp) {
            Cell kv = results.get(0);
            if (kv.getTypeByte() == Type.Delete.getCode()) {
                Cache<ImmutableBytesPtr, PTable> metaDataCache =
                        GlobalCache.getInstance(this.env).getMetaDataCache();
                PTable table = newDeletedTableMarker(kv.getTimestamp());
                metaDataCache.put(cacheKey, table);
                return table;
            }
        }
        return null;
    }

    private static PTable newDeletedTableMarker(long timestamp) {
        return new PTableImpl(timestamp);
    }

    private static boolean isTableDeleted(PTable table) {
        return table.getName() == null;
    }

    private PTable loadTable(RegionCoprocessorEnvironment env, byte[] key,
        ImmutableBytesPtr cacheKey, long clientTimeStamp, long asOfTimeStamp)
        throws IOException, SQLException {
        HRegion region = env.getRegion();
        Cache<ImmutableBytesPtr,PTable> metaDataCache = GlobalCache.getInstance(this.env).getMetaDataCache();
        PTable table = metaDataCache.getIfPresent(cacheKey);
        // We always cache the latest version - fault in if not in cache
        if (table != null || (table = buildTable(key, cacheKey, region, asOfTimeStamp)) != null) {
            return table;
        }
        // if not found then check if newer table already exists and add delete marker for timestamp
        // found
        if (table == null
                && (table = buildDeletedTable(key, cacheKey, region, clientTimeStamp)) != null) {
            return table;
        }
        return null;
    }
    
    
    @Override
    public void createTable(RpcController controller, CreateTableRequest request,
            RpcCallback<MetaDataResponse> done) {
        MetaDataResponse.Builder builder = MetaDataResponse.newBuilder();
        byte[][] rowKeyMetaData = new byte[3][];
        byte[] schemaName = null;
        byte[] tableName = null;

        try {
            List<Mutation> tableMetadata = ProtobufUtil.getMutations(request);
            MetaDataUtil.getTenantIdAndSchemaAndTableName(tableMetadata, rowKeyMetaData);
            byte[] tenantIdBytes = rowKeyMetaData[PhoenixDatabaseMetaData.TENANT_ID_INDEX];
            schemaName = rowKeyMetaData[PhoenixDatabaseMetaData.SCHEMA_NAME_INDEX];
            tableName = rowKeyMetaData[PhoenixDatabaseMetaData.TABLE_NAME_INDEX];
            byte[] parentTableName = MetaDataUtil.getParentTableName(tableMetadata);
            byte[] lockTableName = parentTableName == null ? tableName : parentTableName;
            byte[] lockKey = SchemaUtil.getTableKey(tenantIdBytes, schemaName, lockTableName);
            byte[] key =
                    parentTableName == null ? lockKey : SchemaUtil.getTableKey(tenantIdBytes,
                        schemaName, tableName);
            byte[] parentKey = parentTableName == null ? null : lockKey;

            HRegion region = env.getRegion();
            MetaDataMutationResult result = checkTableKeyInRegion(lockKey, region);
            if (result != null) {
                done.run(MetaDataMutationResult.toProto(result));
                return;
            }
            List<RowLock> locks = Lists.newArrayList();
            long clientTimeStamp = MetaDataUtil.getClientTimeStamp(tableMetadata);
            try {
                acquireLock(region, lockKey, locks);
                if (key != lockKey) {
                    acquireLock(region, key, locks);
                }
                // Load parent table first
                PTable parentTable = null;
                ImmutableBytesPtr parentCacheKey = null;
                if (parentKey != null) {
                    parentCacheKey = new ImmutableBytesPtr(parentKey);
                    parentTable =
                            loadTable(env, parentKey, parentCacheKey, clientTimeStamp,
                                clientTimeStamp);
                    if (parentTable == null || isTableDeleted(parentTable)) {
                        builder.setReturnCode(MetaDataProtos.MutationCode.PARENT_TABLE_NOT_FOUND);
                        builder.setMutationTime(EnvironmentEdgeManager.currentTimeMillis());
                        builder.setTable(PTableImpl.toProto(parentTable));
                        done.run(builder.build());
                        return;
                    }
                    // If parent table isn't at the expected sequence number, then return
                    if (parentTable.getSequenceNumber() != MetaDataUtil
                            .getParentSequenceNumber(tableMetadata)) {
                        builder.setReturnCode(MetaDataProtos.MutationCode.CONCURRENT_TABLE_MUTATION);
                        builder.setMutationTime(EnvironmentEdgeManager.currentTimeMillis());
                        builder.setTable(PTableImpl.toProto(parentTable));
                        done.run(builder.build());
                        return;
                    }
                }
                // Load child table next
                ImmutableBytesPtr cacheKey = new ImmutableBytesPtr(key);
                // Get as of latest timestamp so we can detect if we have a newer table that already
                // exists
                // without making an additional query
                PTable table =
                        loadTable(env, key, cacheKey, clientTimeStamp, HConstants.LATEST_TIMESTAMP);
                if (table != null) {
                    if (table.getTimeStamp() < clientTimeStamp) {
                        // If the table is older than the client time stamp and it's deleted,
                        // continue
                        if (!isTableDeleted(table)) {
                            builder.setReturnCode(MetaDataProtos.MutationCode.TABLE_ALREADY_EXISTS);
                            builder.setMutationTime(EnvironmentEdgeManager.currentTimeMillis());
                            builder.setTable(PTableImpl.toProto(table));
                            done.run(builder.build());
                            return;
                        }
                    } else {
                        builder.setReturnCode(MetaDataProtos.MutationCode.NEWER_TABLE_FOUND);
                        builder.setMutationTime(EnvironmentEdgeManager.currentTimeMillis());
                        builder.setTable(PTableImpl.toProto(table));
                        done.run(builder.build());
                        return;
                    }
                }
                // TODO: Switch this to HRegion#batchMutate when we want to support indexes on the
                // system
                // table. Basically, we get all the locks that we don't already hold for all the
                // tableMetadata rows. This ensures we don't have deadlock situations (ensuring
                // primary and
                // then index table locks are held, in that order). For now, we just don't support
                // indexing
                // on the system table. This is an issue because of the way we manage batch mutation
                // in the
                // Indexer.
                region.mutateRowsWithLocks(tableMetadata, Collections.<byte[]> emptySet());

                // Invalidate the cache - the next getTable call will add it
                // TODO: consider loading the table that was just created here, patching up the parent table, and updating the cache
                Cache<ImmutableBytesPtr,PTable> metaDataCache = GlobalCache.getInstance(this.env).getMetaDataCache();
                if (parentCacheKey != null) {
                    metaDataCache.invalidate(parentCacheKey);
                }
                metaDataCache.invalidate(cacheKey);
                // Get timeStamp from mutations - the above method sets it if it's unset
                long currentTimeStamp = MetaDataUtil.getClientTimeStamp(tableMetadata);
                builder.setReturnCode(MetaDataProtos.MutationCode.TABLE_NOT_FOUND);
                builder.setMutationTime(currentTimeStamp);
                done.run(builder.build());
                return;
            } finally {
                region.releaseRowLocks(locks);
            }
        } catch (Throwable t) {
          logger.error("createTable failed", t);
            ProtobufUtil.setControllerException(controller,
                ServerUtil.createIOException(SchemaUtil.getTableName(schemaName, tableName), t));
        }
    }


    private static void acquireLock(HRegion region, byte[] key, List<RowLock> locks)
        throws IOException {
        RowLock rowLock = region.getRowLock(key);
        if (rowLock == null) {
            throw new IOException("Failed to acquire lock on " + Bytes.toStringBinary(key));
        }
        locks.add(rowLock);
    }
    
    private static final byte[] PHYSICAL_TABLE_BYTES = new byte[] {PTable.LinkType.PHYSICAL_TABLE.getSerializedValue()};
    /**
     * @param tableName parent table's name
     * @return true if there exist a table that use this table as their base table.
     * TODO: should we pass a timestamp here?
     */
    private boolean hasViews(HRegion region, byte[] tenantId, PTable table) throws IOException {
        byte[] schemaName = table.getSchemaName().getBytes();
        byte[] tableName = table.getTableName().getBytes();
        Scan scan = new Scan();
        // If the table is multi-tenant, we need to check across all tenant_ids,
        // so we can't constrain the row key. Otherwise, any views would have
        // the same tenantId.
        if (!table.isMultiTenant()) {
            byte[] startRow = ByteUtil.concat(tenantId, QueryConstants.SEPARATOR_BYTE_ARRAY);
            byte[] stopRow = ByteUtil.nextKey(startRow);
            scan.setStartRow(startRow);
            scan.setStopRow(stopRow);
        }
        SingleColumnValueFilter linkFilter = new SingleColumnValueFilter(TABLE_FAMILY_BYTES, LINK_TYPE_BYTES, EQUAL, PHYSICAL_TABLE_BYTES);
        linkFilter.setFilterIfMissing(true);
        byte[] suffix = ByteUtil.concat(QueryConstants.SEPARATOR_BYTE_ARRAY, SchemaUtil.getTableNameAsBytes(schemaName, tableName));
        SuffixFilter rowFilter = new SuffixFilter(suffix);
        Filter filter = new FilterList(linkFilter, rowFilter);
        scan.setFilter(filter);
        scan.addColumn(TABLE_FAMILY_BYTES, LINK_TYPE_BYTES);
        RegionScanner scanner = region.getScanner(scan);
        try {
            List<Cell> results = newArrayList();
            scanner.next(results);
            return results.size() > 0;
        }
        finally {
            scanner.close();
        }
    }
    
    @Override
    public void dropTable(RpcController controller, DropTableRequest request,
            RpcCallback<MetaDataResponse> done) {
        MetaDataResponse.Builder builder = MetaDataResponse.newBuilder();
        byte[][] rowKeyMetaData = new byte[3][];
        String tableType = request.getTableType();
        byte[] schemaName = null;
        byte[] tableName = null;

        try {
            List<Mutation> tableMetadata = ProtobufUtil.getMutations(request);
            MetaDataUtil.getTenantIdAndSchemaAndTableName(tableMetadata, rowKeyMetaData);
            byte[] tenantIdBytes = rowKeyMetaData[PhoenixDatabaseMetaData.TENANT_ID_INDEX];
            schemaName = rowKeyMetaData[PhoenixDatabaseMetaData.SCHEMA_NAME_INDEX];
            tableName = rowKeyMetaData[PhoenixDatabaseMetaData.TABLE_NAME_INDEX];
            // Disallow deletion of a system table
            if (tableType.equals(PTableType.SYSTEM.getSerializedValue())) {
                builder.setReturnCode(MetaDataProtos.MutationCode.UNALLOWED_TABLE_MUTATION);
                builder.setMutationTime(EnvironmentEdgeManager.currentTimeMillis());
                done.run(builder.build());
                return;
            }
            List<byte[]> tableNamesToDelete = Lists.newArrayList();
            byte[] parentTableName = MetaDataUtil.getParentTableName(tableMetadata);
            byte[] lockTableName = parentTableName == null ? tableName : parentTableName;
            byte[] lockKey = SchemaUtil.getTableKey(tenantIdBytes, schemaName, lockTableName);
            byte[] key =
                    parentTableName == null ? lockKey : SchemaUtil.getTableKey(tenantIdBytes,
                        schemaName, tableName);

            HRegion region = env.getRegion();
            MetaDataMutationResult result = checkTableKeyInRegion(key, region);
            if (result != null) {
                done.run(MetaDataMutationResult.toProto(result));
                return;
            }
            List<RowLock> locks = Lists.newArrayList();
            try {
                acquireLock(region, lockKey, locks);
                if (key != lockKey) {
                    acquireLock(region, key, locks);
                }
                List<ImmutableBytesPtr> invalidateList = new ArrayList<ImmutableBytesPtr>();
                result =
                        doDropTable(key, tenantIdBytes, schemaName, tableName,
                            PTableType.fromSerializedValue(tableType), tableMetadata,
                            invalidateList, locks, tableNamesToDelete);
                if (result.getMutationCode() != MutationCode.TABLE_ALREADY_EXISTS
                        || result.getTable() == null) {
                    done.run(MetaDataMutationResult.toProto(result));
                    return;
                }
                Cache<ImmutableBytesPtr,PTable> metaDataCache = GlobalCache.getInstance(this.env).getMetaDataCache();
                // Commit the list of deletion.
                region.mutateRowsWithLocks(tableMetadata, Collections.<byte[]> emptySet());
                long currentTime = MetaDataUtil.getClientTimeStamp(tableMetadata);
                for (ImmutableBytesPtr ckey : invalidateList) {
                    metaDataCache.put(ckey, newDeletedTableMarker(currentTime));
                }
                if (parentTableName != null) {
                    ImmutableBytesPtr parentCacheKey = new ImmutableBytesPtr(lockKey);
                    metaDataCache.invalidate(parentCacheKey);
                }
                done.run(MetaDataMutationResult.toProto(result));
                return;
            } finally {
                region.releaseRowLocks(locks);
            }
        } catch (Throwable t) {
          logger.error("dropTable failed", t);
            ProtobufUtil.setControllerException(controller,
                ServerUtil.createIOException(SchemaUtil.getTableName(schemaName, tableName), t));
        }
    }

    private MetaDataMutationResult doDropTable(byte[] key, byte[] tenantId, byte[] schemaName,
        byte[] tableName, PTableType tableType, List<Mutation> rowsToDelete,
        List<ImmutableBytesPtr> invalidateList, List<RowLock> locks,
        List<byte[]> tableNamesToDelete) throws IOException, SQLException {
        long clientTimeStamp = MetaDataUtil.getClientTimeStamp(rowsToDelete);
    
        HRegion region = env.getRegion();
        ImmutableBytesPtr cacheKey = new ImmutableBytesPtr(key);
        
        Cache<ImmutableBytesPtr,PTable> metaDataCache = GlobalCache.getInstance(this.env).getMetaDataCache();
        PTable table = metaDataCache.getIfPresent(cacheKey);
        
        // We always cache the latest version - fault in if not in cache
        if (table != null
                || (table = buildTable(key, cacheKey, region, HConstants.LATEST_TIMESTAMP)) != null) {
            if (table.getTimeStamp() < clientTimeStamp) {
                // If the table is older than the client time stamp and its deleted, continue
                if (isTableDeleted(table)) {
                    return new MetaDataMutationResult(MutationCode.TABLE_ALREADY_EXISTS,
                            EnvironmentEdgeManager.currentTimeMillis(), null);
                }
                if (tableType != table.getType()) {
                    // We said to drop a table, but found a view or visa versa
                    return new MetaDataMutationResult(MutationCode.TABLE_NOT_FOUND,
                            EnvironmentEdgeManager.currentTimeMillis(), null);
                }
            } else {
                return new MetaDataMutationResult(MutationCode.NEWER_TABLE_FOUND,
                        EnvironmentEdgeManager.currentTimeMillis(), table);
            }
        }
        if (table == null && buildDeletedTable(key, cacheKey, region, clientTimeStamp) != null) {
            return new MetaDataMutationResult(MutationCode.NEWER_TABLE_FOUND,
                    EnvironmentEdgeManager.currentTimeMillis(), null);
        }
        // Get mutations for main table.
        Scan scan = newTableRowsScan(key, MIN_TABLE_TIMESTAMP, clientTimeStamp);
        RegionScanner scanner = region.getScanner(scan);
        List<Cell> results = Lists.newArrayList();
        scanner.next(results);
        if (results.isEmpty()) {
            return new MetaDataMutationResult(MutationCode.TABLE_NOT_FOUND,
                    EnvironmentEdgeManager.currentTimeMillis(), null);
        }

        Cell typeKeyValue =
                KeyValueUtil.getColumnLatest(GenericKeyValueBuilder.INSTANCE, results, PhoenixDatabaseMetaData.TABLE_FAMILY_BYTES,
                    PhoenixDatabaseMetaData.TABLE_TYPE_BYTES);
        assert (typeKeyValue != null && typeKeyValue.getValueLength() == 1);
        if (tableType != PTableType.fromSerializedValue(typeKeyValue.getValueArray()[typeKeyValue
                .getValueOffset()])) {
            // We said to drop a table, but found a view or visa versa
            return new MetaDataMutationResult(MutationCode.TABLE_NOT_FOUND,
                    EnvironmentEdgeManager.currentTimeMillis(), null);
        }
        // Don't allow a table with views to be deleted
        // TODO: support CASCADE with DROP
        if (tableType == PTableType.TABLE && hasViews(region, tenantId, table)) {
            return new MetaDataMutationResult(MutationCode.UNALLOWED_TABLE_MUTATION, 
              EnvironmentEdgeManager.currentTimeMillis(), null);
        }
        if (table.getType() != PTableType.VIEW) { // Add to list of HTables to delete, unless it's a view
            byte[] fullName = table.getName().getBytes();
            tableNamesToDelete.add(fullName);
        }
        List<byte[]> indexNames = Lists.newArrayList();
        invalidateList.add(cacheKey);
        byte[][] rowKeyMetaData = new byte[5][];
        do {
            Cell kv = results.get(LINK_TYPE_INDEX);
            int nColumns = getVarChars(kv.getRowArray(), kv.getRowOffset(), kv.getRowLength(), 0, rowKeyMetaData);
            if (nColumns == 5
                    && rowKeyMetaData[PhoenixDatabaseMetaData.COLUMN_NAME_INDEX].length == 0
                    && rowKeyMetaData[PhoenixDatabaseMetaData.INDEX_NAME_INDEX].length > 0
                    && Bytes.compareTo(kv.getQualifierArray(), kv.getQualifierOffset(), kv.getQualifierLength(), LINK_TYPE_BYTES, 0, LINK_TYPE_BYTES.length) == 0
                    && LinkType.fromSerializedValue(kv.getValueArray()[kv.getValueOffset()]) == LinkType.INDEX_TABLE) {
                indexNames.add(rowKeyMetaData[PhoenixDatabaseMetaData.INDEX_NAME_INDEX]);
            }
            // FIXME: Remove when unintentionally deprecated method is fixed (HBASE-7870).
            // FIXME: the version of the Delete constructor without the lock args was introduced
            // in 0.94.4, thus if we try to use it here we can no longer use the 0.94.2 version
            // of the client.
            Delete delete = new Delete(kv.getRowArray(), kv.getRowOffset(), kv.getRowLength(), clientTimeStamp);
            rowsToDelete.add(delete);
            results.clear();
            scanner.next(results);
        } while (!results.isEmpty());
    
        // Recursively delete indexes
        for (byte[] indexName : indexNames) {
            byte[] indexKey = SchemaUtil.getTableKey(tenantId, schemaName, indexName);
            // FIXME: Remove when unintentionally deprecated method is fixed (HBASE-7870).
            // FIXME: the version of the Delete constructor without the lock args was introduced
            // in 0.94.4, thus if we try to use it here we can no longer use the 0.94.2 version
            // of the client.
            Delete delete = new Delete(indexKey, clientTimeStamp);
            rowsToDelete.add(delete);
            acquireLock(region, indexKey, locks);
            MetaDataMutationResult result =
                    doDropTable(indexKey, tenantId, schemaName, indexName, PTableType.INDEX,
                        rowsToDelete, invalidateList, locks, tableNamesToDelete);
            if (result.getMutationCode() != MutationCode.TABLE_ALREADY_EXISTS
                    || result.getTable() == null) {
                return result;
            }
        }
    
        return new MetaDataMutationResult(MutationCode.TABLE_ALREADY_EXISTS,
                EnvironmentEdgeManager.currentTimeMillis(), table, tableNamesToDelete);
    }

    private static interface ColumnMutator {
      MetaDataMutationResult updateMutation(PTable table, byte[][] rowKeyMetaData,
          List<Mutation> tableMetadata, HRegion region,
          List<ImmutableBytesPtr> invalidateList, List<RowLock> locks) throws IOException,
          SQLException;
    }

    private MetaDataMutationResult
    mutateColumn(List<Mutation> tableMetadata, ColumnMutator mutator) throws IOException {
        byte[][] rowKeyMetaData = new byte[5][];
        MetaDataUtil.getTenantIdAndSchemaAndTableName(tableMetadata, rowKeyMetaData);
        byte[] tenantId = rowKeyMetaData[PhoenixDatabaseMetaData.TENANT_ID_INDEX];
        byte[] schemaName = rowKeyMetaData[PhoenixDatabaseMetaData.SCHEMA_NAME_INDEX];
        byte[] tableName = rowKeyMetaData[PhoenixDatabaseMetaData.TABLE_NAME_INDEX];
        try {
            byte[] key = SchemaUtil.getTableKey(tenantId, schemaName, tableName);
            HRegion region = env.getRegion();
            MetaDataMutationResult result = checkTableKeyInRegion(key, region);
            if (result != null) {
                return result;
            }
            List<RowLock> locks = Lists.newArrayList();
            try {
                acquireLock(region, key, locks);
                ImmutableBytesPtr cacheKey = new ImmutableBytesPtr(key);
                List<ImmutableBytesPtr> invalidateList = new ArrayList<ImmutableBytesPtr>();
                invalidateList.add(cacheKey);
                Cache<ImmutableBytesPtr,PTable> metaDataCache = GlobalCache.getInstance(this.env).getMetaDataCache();
                PTable table = metaDataCache.getIfPresent(cacheKey);
                if (logger.isDebugEnabled()) {
                    if (table == null) {
                        logger.debug("Table " + Bytes.toStringBinary(key)
                                + " not found in cache. Will build through scan");
                    } else {
                        logger.debug("Table " + Bytes.toStringBinary(key)
                                + " found in cache with timestamp " + table.getTimeStamp()
                                + " seqNum " + table.getSequenceNumber());
                    }
                }
                // Get client timeStamp from mutations
                long clientTimeStamp = MetaDataUtil.getClientTimeStamp(tableMetadata);
                if (table == null
                        && (table = buildTable(key, cacheKey, region, HConstants.LATEST_TIMESTAMP)) == null) {
                    // if not found then call newerTableExists and add delete marker for timestamp
                    // found
                    if (buildDeletedTable(key, cacheKey, region, clientTimeStamp) != null) {
                        return new MetaDataMutationResult(MutationCode.NEWER_TABLE_FOUND,
                                EnvironmentEdgeManager.currentTimeMillis(), null);
                    }
                    return new MetaDataMutationResult(MutationCode.TABLE_NOT_FOUND,
                            EnvironmentEdgeManager.currentTimeMillis(), null);
                }
                if (table.getTimeStamp() >= clientTimeStamp) {
                    return new MetaDataMutationResult(MutationCode.NEWER_TABLE_FOUND,
                            EnvironmentEdgeManager.currentTimeMillis(), table);
                } else if (isTableDeleted(table)) {
                    return new MetaDataMutationResult(MutationCode.TABLE_NOT_FOUND,
                            EnvironmentEdgeManager.currentTimeMillis(), null);
                }
        
                long expectedSeqNum = MetaDataUtil.getSequenceNumber(tableMetadata) - 1; // lookup
                                                                                         // TABLE_SEQ_NUM
                                                                                         // in
                                                                                         // tableMetaData
                if (logger.isDebugEnabled()) {
                    logger.debug("For table " + Bytes.toStringBinary(key) + " expecting seqNum "
                            + expectedSeqNum + " and found seqNum " + table.getSequenceNumber()
                            + " with " + table.getColumns().size() + " columns: "
                            + table.getColumns());
                }
                if (expectedSeqNum != table.getSequenceNumber()) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("For table " + Bytes.toStringBinary(key)
                                + " returning CONCURRENT_TABLE_MUTATION due to unexpected seqNum");
                    }
                    return new MetaDataMutationResult(MutationCode.CONCURRENT_TABLE_MUTATION,
                            EnvironmentEdgeManager.currentTimeMillis(), table);
                }
        
                PTableType type = table.getType();
                if (type == PTableType.INDEX) {
                    // Disallow mutation of an index table
                    return new MetaDataMutationResult(MutationCode.UNALLOWED_TABLE_MUTATION,
                            EnvironmentEdgeManager.currentTimeMillis(), null);
                } else {
                    // server-side, except for indexing, we always expect the keyvalues to be standard KeyValues
                    PTableType expectedType = MetaDataUtil.getTableType(tableMetadata, GenericKeyValueBuilder.INSTANCE, new ImmutableBytesPtr());
                    // We said to drop a table, but found a view or visa versa
                    if (type != expectedType) {
                        return new MetaDataMutationResult(MutationCode.TABLE_NOT_FOUND, EnvironmentEdgeManager.currentTimeMillis(), null);
                    }
                    if (hasViews(region, tenantId, table)) {
                        // Disallow any column mutations for parents of tenant tables
                        return new MetaDataMutationResult(MutationCode.UNALLOWED_TABLE_MUTATION, EnvironmentEdgeManager.currentTimeMillis(), null);
                    }
                }
                result = mutator.updateMutation(table, rowKeyMetaData, tableMetadata, region,
                            invalidateList, locks);
                if (result != null) {
                    return result;
                }
        
                region.mutateRowsWithLocks(tableMetadata, Collections.<byte[]> emptySet());
                // Invalidate from cache
                for (ImmutableBytesPtr invalidateKey : invalidateList) {
                    metaDataCache.invalidate(invalidateKey);
                }
                // Get client timeStamp from mutations, since it may get updated by the
                // mutateRowsWithLocks call
                long currentTime = MetaDataUtil.getClientTimeStamp(tableMetadata);
                return new MetaDataMutationResult(MutationCode.TABLE_ALREADY_EXISTS, currentTime,
                        null);
            } finally {
                region.releaseRowLocks(locks);
            }
        } catch (Throwable t) {
            ServerUtil.throwIOException(SchemaUtil.getTableName(schemaName, tableName), t);
            return null; // impossible
        }
    }


    @Override
    public void addColumn(RpcController controller, AddColumnRequest request,
            RpcCallback<MetaDataResponse> done) {
        try {
            List<Mutation> tableMetaData = ProtobufUtil.getMutations(request);
            MetaDataMutationResult result = mutateColumn(tableMetaData, new ColumnMutator() {
                @Override
                public MetaDataMutationResult updateMutation(PTable table, byte[][] rowKeyMetaData,
                        List<Mutation> tableMetaData, HRegion region,
                        List<ImmutableBytesPtr> invalidateList, List<RowLock> locks) {
                    byte[] tenantId = rowKeyMetaData[TENANT_ID_INDEX];
                    byte[] schemaName = rowKeyMetaData[SCHEMA_NAME_INDEX];
                    byte[] tableName = rowKeyMetaData[TABLE_NAME_INDEX];
                    for (Mutation m : tableMetaData) {
                        byte[] key = m.getRow();
                        boolean addingPKColumn = false;
                        int pkCount = getVarChars(key, rowKeyMetaData);
                        if (pkCount > COLUMN_NAME_INDEX
                                && Bytes.compareTo(schemaName, rowKeyMetaData[SCHEMA_NAME_INDEX]) == 0
                                && Bytes.compareTo(tableName, rowKeyMetaData[TABLE_NAME_INDEX]) == 0) {
                            try {
                                if (pkCount > FAMILY_NAME_INDEX
                                        && rowKeyMetaData[PhoenixDatabaseMetaData.FAMILY_NAME_INDEX].length > 0) {
                                    PColumnFamily family =
                                            table.getColumnFamily(rowKeyMetaData[PhoenixDatabaseMetaData.FAMILY_NAME_INDEX]);
                                    family.getColumn(rowKeyMetaData[PhoenixDatabaseMetaData.COLUMN_NAME_INDEX]);
                                } else if (pkCount > COLUMN_NAME_INDEX
                                        && rowKeyMetaData[PhoenixDatabaseMetaData.COLUMN_NAME_INDEX].length > 0) {
                                    addingPKColumn = true;
                                    table.getPKColumn(new String(
                                            rowKeyMetaData[PhoenixDatabaseMetaData.COLUMN_NAME_INDEX]));
                                } else {
                                    continue;
                                }

                                return new MetaDataMutationResult(
                                        MutationCode.COLUMN_ALREADY_EXISTS, EnvironmentEdgeManager
                                                .currentTimeMillis(), table);
                            } catch (ColumnFamilyNotFoundException e) {
                                continue;
                            } catch (ColumnNotFoundException e) {
                                if (addingPKColumn) {
                                    // Add all indexes to invalidate list, as they will all be
                                    // adding
                                    // the same PK column
                                    // No need to lock them, as we have the parent table lock at
                                    // this
                                    // point
                                    for (PTable index : table.getIndexes()) {
                                        invalidateList.add(new ImmutableBytesPtr(SchemaUtil
                                                .getTableKey(tenantId, index.getSchemaName()
                                                        .getBytes(), index.getTableName()
                                                        .getBytes())));
                                    }
                                }
                                continue;
                            }
                        }
                    }
                    return null;
                }
            });
            if (result != null) {
                done.run(MetaDataMutationResult.toProto(result));
            }
        } catch (IOException ioe) {
            ProtobufUtil.setControllerException(controller, ioe);
        }
    }
    
    @Override
    public void dropColumn(RpcController controller, DropColumnRequest request,
            RpcCallback<MetaDataResponse> done) {
        List<Mutation> tableMetaData = null;

        try {
            tableMetaData = ProtobufUtil.getMutations(request);
            final long clientTimeStamp = MetaDataUtil.getClientTimeStamp(tableMetaData);
            final List<byte[]> tableNamesToDelete = Lists.newArrayList();
            MetaDataMutationResult result = mutateColumn(tableMetaData, new ColumnMutator() {
                @Override
                public MetaDataMutationResult updateMutation(PTable table, byte[][] rowKeyMetaData,
                        List<Mutation> tableMetaData, HRegion region,
                        List<ImmutableBytesPtr> invalidateList, List<RowLock> locks)
                        throws IOException, SQLException {
                    byte[] tenantId = rowKeyMetaData[TENANT_ID_INDEX];
                    byte[] schemaName = rowKeyMetaData[SCHEMA_NAME_INDEX];
                    byte[] tableName = rowKeyMetaData[TABLE_NAME_INDEX];
                    boolean deletePKColumn = false;
                    List<Mutation> additionalTableMetaData = Lists.newArrayList();
                    for (Mutation m : tableMetaData) {
                        if (m instanceof Delete) {
                            byte[] key = m.getRow();
                            int pkCount = getVarChars(key, rowKeyMetaData);
                            if (pkCount > COLUMN_NAME_INDEX
                                    && Bytes.compareTo(schemaName,
                                        rowKeyMetaData[SCHEMA_NAME_INDEX]) == 0
                                    && Bytes.compareTo(tableName, rowKeyMetaData[TABLE_NAME_INDEX]) == 0) {
                                PColumn columnToDelete = null;
                                try {
                                    if (pkCount > FAMILY_NAME_INDEX
                                            && rowKeyMetaData[PhoenixDatabaseMetaData.FAMILY_NAME_INDEX].length > 0) {
                                        PColumnFamily family =
                                                table.getColumnFamily(rowKeyMetaData[PhoenixDatabaseMetaData.FAMILY_NAME_INDEX]);
                                        columnToDelete =
                                                family.getColumn(rowKeyMetaData[PhoenixDatabaseMetaData.COLUMN_NAME_INDEX]);
                                    } else if (pkCount > COLUMN_NAME_INDEX
                                            && rowKeyMetaData[PhoenixDatabaseMetaData.COLUMN_NAME_INDEX].length > 0) {
                                        deletePKColumn = true;
                                        columnToDelete = table.getPKColumn(new String(
                                          rowKeyMetaData[PhoenixDatabaseMetaData.COLUMN_NAME_INDEX]));
                                    } else {
                                        continue;
                                    }
                                    if (columnToDelete.getViewConstant() != null) { // Disallow deletion of column referenced in WHERE clause of view
                                        return new MetaDataMutationResult(MutationCode.UNALLOWED_TABLE_MUTATION, EnvironmentEdgeManager.currentTimeMillis(), table, columnToDelete);
                                    }
                                    // Look for columnToDelete in any indexes. If found as PK
                                    // column, get lock and drop the index. If found as covered
                                    // column, delete from index (do this client side?).
                                    // In either case, invalidate index if the column is in it
                                    for (PTable index : table.getIndexes()) {
                                        try {
                                            String indexColumnName = IndexUtil.getIndexColumnName(columnToDelete);
                                            PColumn indexColumn = index.getColumn(indexColumnName);
                                            byte[] indexKey =
                                                    SchemaUtil.getTableKey(tenantId, index
                                                            .getSchemaName().getBytes(), index.getTableName().getBytes());
                                            // If index contains the column in it's PK, then drop it
                                            if (SchemaUtil.isPKColumn(indexColumn)) {
                                                // Since we're dropping the index, lock it to ensure
                                                // that a change in index state doesn't
                                                // occur while we're dropping it.
                                                acquireLock(region, indexKey, locks);
                                                // Drop the index table. The doDropTable will expand
                                                // this to all of the table rows and invalidate the
                                                // index table
                                                additionalTableMetaData.add(new Delete(indexKey, clientTimeStamp));
                                                byte[] linkKey = MetaDataUtil.getParentLinkKey(tenantId,
                                                            schemaName, tableName, index.getTableName().getBytes());
                                                // Drop the link between the data table and the
                                                // index table
                                                additionalTableMetaData.add(new Delete(linkKey, clientTimeStamp));
                                                doDropTable(indexKey, tenantId, index.getSchemaName().getBytes(), index.getTableName().getBytes(),
                                                    index.getType(), additionalTableMetaData, invalidateList, locks, tableNamesToDelete);
                                                // TODO: return in result?
                                            } else {
                                                invalidateList.add(new ImmutableBytesPtr(indexKey));
                                            }
                                        } catch (ColumnNotFoundException e) {
                                        } catch (AmbiguousColumnException e) {
                                        }
                                    }
                                } catch (ColumnFamilyNotFoundException e) {
                                    return new MetaDataMutationResult(
                                            MutationCode.COLUMN_NOT_FOUND, EnvironmentEdgeManager
                                                    .currentTimeMillis(), table, columnToDelete);
                                } catch (ColumnNotFoundException e) {
                                    return new MetaDataMutationResult(
                                            MutationCode.COLUMN_NOT_FOUND, EnvironmentEdgeManager
                                                    .currentTimeMillis(), table, columnToDelete);
                                }
                            }
                        }
                    }
                    if (deletePKColumn) {
                        if (table.getPKColumns().size() == 1) {
                            return new MetaDataMutationResult(MutationCode.NO_PK_COLUMNS,
                                    EnvironmentEdgeManager.currentTimeMillis(), null);
                        }
                    }
                    tableMetaData.addAll(additionalTableMetaData);
                    return null;
                }
            });
            if (result != null) {
                done.run(MetaDataMutationResult.toProto(result));
            }
        } catch (IOException ioe) {
            ProtobufUtil.setControllerException(controller, ioe);
        }
    }

    @Override
    public void clearCache(RpcController controller, ClearCacheRequest request,
            RpcCallback<ClearCacheResponse> done) {
        GlobalCache cache = GlobalCache.getInstance(this.env);
        Cache<ImmutableBytesPtr, PTable> metaDataCache =
                GlobalCache.getInstance(this.env).getMetaDataCache();
        metaDataCache.invalidateAll();
        cache.clearTenantCache();
    }

    @Override
    public void getVersion(RpcController controller, GetVersionRequest request,
            RpcCallback<GetVersionResponse> done) {

        GetVersionResponse.Builder builder = GetVersionResponse.newBuilder();
        // The first 3 bytes of the long is used to encoding the HBase version as major.minor.patch.
        // The next 4 bytes of the value is used to encode the Phoenix version as major.minor.patch.
        long version = MetaDataUtil.encodeHBaseAndPhoenixVersions(this.env.getHBaseVersion());

        // The last byte is used to communicate whether or not mutable secondary indexing
        // was configured properly.
        version =
                MetaDataUtil.encodeMutableIndexConfiguredProperly(version,
                    IndexManagementUtil.isWALEditCodecSet(this.env.getConfiguration()));

        builder.setVersion(version);
        done.run(builder.build());
    }

    @Override
    public void updateIndexState(RpcController controller, UpdateIndexStateRequest request,
            RpcCallback<MetaDataResponse> done) {
        MetaDataResponse.Builder builder = MetaDataResponse.newBuilder();
        byte[] schemaName = null;
        byte[] tableName = null;
        try {
            byte[][] rowKeyMetaData = new byte[3][];
            List<Mutation> tableMetadata = ProtobufUtil.getMutations(request);
            MetaDataUtil.getTenantIdAndSchemaAndTableName(tableMetadata, rowKeyMetaData);
            byte[] tenantId = rowKeyMetaData[PhoenixDatabaseMetaData.TENANT_ID_INDEX];
            schemaName = rowKeyMetaData[PhoenixDatabaseMetaData.SCHEMA_NAME_INDEX];
            tableName = rowKeyMetaData[PhoenixDatabaseMetaData.TABLE_NAME_INDEX];
            byte[] key = SchemaUtil.getTableKey(tenantId, schemaName, tableName);
            HRegion region = env.getRegion();
            MetaDataMutationResult result = checkTableKeyInRegion(key, region);
            if (result != null) {
                done.run(MetaDataMutationResult.toProto(result));
                return;
            }
            long timeStamp = MetaDataUtil.getClientTimeStamp(tableMetadata);
            ImmutableBytesPtr cacheKey = new ImmutableBytesPtr(key);
            List<Cell> newKVs = tableMetadata.get(0).getFamilyCellMap().get(TABLE_FAMILY_BYTES);
            Cell newKV = newKVs.get(0);
            PIndexState newState =
                    PIndexState.fromSerializedValue(newKV.getValueArray()[newKV.getValueOffset()]);
            RowLock rowLock = region.getRowLock(key);
            if (rowLock == null) {
                throw new IOException("Failed to acquire lock on " + Bytes.toStringBinary(key));
            }
            try {
                Get get = new Get(key);
                get.setTimeRange(PTable.INITIAL_SEQ_NUM, timeStamp);
                get.addColumn(TABLE_FAMILY_BYTES, INDEX_STATE_BYTES);
                Result currentResult = region.get(get);
                if (currentResult.rawCells().length == 0) {
                    builder.setReturnCode(MetaDataProtos.MutationCode.TABLE_NOT_FOUND);
                    builder.setMutationTime(EnvironmentEdgeManager.currentTimeMillis());
                    done.run(builder.build());
                    return;
                }
                Cell currentStateKV = currentResult.rawCells()[0];
                PIndexState currentState =
                        PIndexState.fromSerializedValue(currentStateKV.getValueArray()[currentStateKV
                                .getValueOffset()]);
                // Detect invalid transitions
                if (currentState == PIndexState.BUILDING) {
                    if (newState == PIndexState.USABLE) {
                        builder.setReturnCode(MetaDataProtos.MutationCode.UNALLOWED_TABLE_MUTATION);
                        builder.setMutationTime(EnvironmentEdgeManager.currentTimeMillis());
                        done.run(builder.build());
                        return;
                    }
                } else if (currentState == PIndexState.DISABLE) {
                    if (newState != PIndexState.BUILDING && newState != PIndexState.DISABLE) {
                        builder.setReturnCode(MetaDataProtos.MutationCode.UNALLOWED_TABLE_MUTATION);
                        builder.setMutationTime(EnvironmentEdgeManager.currentTimeMillis());
                        done.run(builder.build());
                        return;
                    }
                    // Done building, but was disable before that, so that in disabled state
                    if (newState == PIndexState.ACTIVE) {
                        newState = PIndexState.DISABLE;
                    }
                }

                if (currentState == PIndexState.BUILDING && newState != PIndexState.ACTIVE) {
                    timeStamp = currentStateKV.getTimestamp();
                }
                if ((currentState == PIndexState.UNUSABLE && newState == PIndexState.ACTIVE)
                        || (currentState == PIndexState.ACTIVE && newState == PIndexState.UNUSABLE)) {
                    newState = PIndexState.INACTIVE;
                    newKVs.set(0, KeyValueUtil.newKeyValue(key, TABLE_FAMILY_BYTES,
                        INDEX_STATE_BYTES, timeStamp, Bytes.toBytes(newState.getSerializedValue())));
                } else if (currentState == PIndexState.INACTIVE && newState == PIndexState.USABLE) {
                    newState = PIndexState.ACTIVE;
                    newKVs.set(0, KeyValueUtil.newKeyValue(key, TABLE_FAMILY_BYTES,
                        INDEX_STATE_BYTES, timeStamp, Bytes.toBytes(newState.getSerializedValue())));
                }
                if (currentState != newState) {
                    region.mutateRowsWithLocks(tableMetadata, Collections.<byte[]> emptySet());
                    // Invalidate from cache
                    Cache<ImmutableBytesPtr,PTable> metaDataCache = GlobalCache.getInstance(this.env).getMetaDataCache();
                    metaDataCache.invalidate(cacheKey);
                }
                // Get client timeStamp from mutations, since it may get updated by the
                // mutateRowsWithLocks call
                long currentTime = MetaDataUtil.getClientTimeStamp(tableMetadata);
                builder.setReturnCode(MetaDataProtos.MutationCode.TABLE_ALREADY_EXISTS);
                builder.setMutationTime(currentTime);
                done.run(builder.build());
                return;
            } finally {
                rowLock.release();
            }
        } catch (Throwable t) {
          logger.error("updateIndexState failed", t);
            ProtobufUtil.setControllerException(controller,
                ServerUtil.createIOException(SchemaUtil.getTableName(schemaName, tableName), t));
        }
    }
    
    /**
     * 
     * Matches rows that end with a given byte array suffix
     *
     * 
     * @since 3.0
     */
    private static class SuffixFilter extends FilterBase {
        protected byte[] suffix = null;

        public SuffixFilter(final byte[] suffix) {
            this.suffix = suffix;
        }
        
        @Override
        public ReturnCode filterKeyValue(Cell ignored) throws IOException {
          return ReturnCode.INCLUDE;
        }
        
        @Override
        public boolean filterRowKey(byte[] buffer, int offset, int length) {
            if (buffer == null || this.suffix == null) return true;
            if (length < suffix.length) return true;
            // if they are equal, return false => pass row
            // else return true, filter row
            // if we are passed the suffix, set flag
            int cmp = Bytes.compareTo(buffer, offset + (length - this.suffix.length),
                    this.suffix.length, this.suffix, 0, this.suffix.length);
            return cmp != 0;
        }
    }
}
