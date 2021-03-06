// Copyright 2012 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloudera.impala.catalog;

import java.util.concurrent.ConcurrentMap;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.log4j.Logger;

import com.cloudera.impala.catalog.Catalog.MetadataLoadState;
import com.cloudera.impala.catalog.Catalog.TableNotFoundException;
import com.cloudera.impala.common.ImpalaException;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ComputationException;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.MapMaker;
import com.google.common.collect.Maps;

/**
 * Internal representation of db-related metadata. Owned by Catalog instance.
 * Not thread safe.
 *
 * The static initialisation method loadDb is the only way to construct a Db
 * object.
 *
 * Tables are stored in a map from the table name to the table object. They may
 * be loaded 'eagerly' at construction or 'lazily' on first reference. 
 * Tables are accessed via getTable which may trigger a metadata read in two cases:
 *  * if the table has never been loaded
 *  * if the table loading failed on the previous attempt
 */
public class Db {
  private static final Logger LOG = Logger.getLogger(Db.class);

  private final String name;

  private final Catalog parentCatalog;
  private final HiveMetaStoreClient client;

  // map from table name to Table
  private final LazyTableMap tables;

  // If true, table map values are populated lazily on read.
  final boolean lazy;

  /**
   * Thrown when a table cannot be loaded due to an error. 
   */
  public static class TableLoadingException extends ImpalaException {
    public TableLoadingException(String s, Throwable cause) {
      super(s, cause);
    }

    public TableLoadingException(String s) {
      super(s);
    }
  };

  /**
   * Loads all tables in the the table map, ignoring any tables that don't load
   * correctly.
   */
  private void forceLoadAllTables() {
    for (String tableName: getAllTableNames()) {
      try {
        tables.get(tableName);
      } catch (Exception ex) {
        LOG.warn("Ignoring table: " + tableName + " due to error when loading", ex);
      }
    }
  }

  /**
   * Lazily loads table metadata on read (through 'get') and tracks the valid/known
   * table names.
   *
   * If a table has not yet been loaded successfully, get() will attempt to load it.
   * It is only possible to load metadata for tables in the known table name map.
   *
   * Getting all the table metadata is a heavy-weight operation, but Impala still needs
   * to know what tables exist in each database (one use case is for SHOW commands). To
   * support this, there is a parallel mapping of the known table names to their metadata
   * load state. When Impala starts up (and on refresh commands) the table name map is
   * populated with all tables names available.
   *
   * Before loading any metadata, the table name map is checked to ensure the given
   * table is "known". If it is not, no metadata is loaded and an exception
   * is thrown.
   */
  private class LazyTableMap {
    // Map of table name to Table metadata
    private final ConcurrentMap<String, Table> tableMetadataMap = new MapMaker()
        .makeComputingMap(
        new Function<String, Table>() {
          public Table apply(String tableName) {
            return loadTable(tableName);
          }
        });

    // Map of table names to the metadata load state. It is only possible to load metadata
    // for tables that exist in this map.
    private final ConcurrentMap<String, MetadataLoadState> tableNameMap = new MapMaker()
        .makeMap();

    /**
     * Initializes the class with a list of valid table names and marks each table's
     * metadata as uninitialized.
     */
    public LazyTableMap(List<String> tableNames) {
      for (String tableName: tableNames) {
        tableNameMap.put(tableName.toLowerCase(), MetadataLoadState.UNINITIALIZED);
      }
    }

    /**
     * Returns all known table names.
     */
    public Set<String> getAllTableNames() {
      return tableNameMap.keySet();
    }

    /**
     * Returns the Table object corresponding to the supplied table name. The table
     * name must exist in the table name map for the metadata load to succeed.
     * The exact behavior is:
     * - If the table already exists in the metadata map, its value will be returned.
     * - If the table is not present in the metadata map and the table exists in the
     *   known table map, its metadata is loaded.
     * - If the table is not present the table name map, null is returned.
     *
     * throws a TableLoadingException if there are errors loading the table metadata
     * unless the error is a TableNotFound error in which case null is returned.
     */
    public Table get(String tableName) throws TableLoadingException {
      try {
        // There is no need to check the tableNameMap here because it is done within
        // the loadTable(...) function.
        return tableMetadataMap.get(tableName.toLowerCase());
      } catch (ComputationException e) {
        // Search for the cause of this exception and throw the correct inner exception
        // type. In the case of a TableNotFoundException, return null.
        Throwable cause = e.getCause();
        while(cause != null) {
          if (cause instanceof TableLoadingException) {
            throw (TableLoadingException) cause;
          } else if (cause instanceof TableNotFoundException) {
            return null;
          }
          cause = cause.getCause();
        }
        throw e;
      }
    }

    private Table loadTable(String tableName) {
      try {
        MetadataLoadState metadataState = tableNameMap.get(tableName);

        // This table doesn't exist in the table name cache. Throw an exception.
        if (metadataState == null) {
          throw new TableNotFoundException("Table not found: " + tableName);
        }

        // We should never have a case where we make it here and the metadata is marked
        // as already loaded.
        Preconditions.checkState(metadataState != MetadataLoadState.LOADED);

        // Try to load the table Metadata
        Table table =
            Table.load(parentCatalog.getNextTableId(), client, Db.this, tableName);
        tableNameMap.put(tableName, MetadataLoadState.LOADED);
        return table;
      } catch (TableLoadingException e) {
        // Convert to an unchecked exception
        throw new ComputationException(e);
      } catch (TableNotFoundException e) {
        // Convert to an unchecked exception
        throw new ComputationException(e);
      }
    }
  }

  private Db(String name, Catalog catalog, HiveMetaStoreClient hiveClient,
      boolean lazy) throws MetaException {
    this.name = name;
    this.lazy = lazy;
    this.parentCatalog = catalog;
    this.client = hiveClient;
    this.tables = new LazyTableMap(client.getAllTables(name));
  }

  /**
   * Load the metadata of a Hive database into our own in-memory metadata
   * representation.  Ignore tables with columns of unsupported types (all
   * complex types). Throws an exception if there is an error communicating with
   * the metastore.
   *
   * @param client
   *          HiveMetaStoreClient to communicate with Metastore
   * @param dbName
   * @param lazy
   *          if true, tables themselves are loaded lazily on read, otherwise
   *          they are read eagerly in this method. The set of table names is
   *          always loaded. If false - meaning all tables are read - malformed
   *          tables that do not load are logged and ignored with no exception
   *          thrown.
   * @return non-null Db instance (possibly containing no tables)
   */
  public static Db loadDb(Catalog catalog, HiveMetaStoreClient client, String dbName,
      boolean lazy) {
    try {
      return new Db(dbName, catalog, client, lazy);
    } catch (MetaException e) {
      // turn into unchecked exception
      throw new UnsupportedOperationException(e);
    }
  }

  public String getName() {
    return name;
  }

  public List<String> getAllTableNames() {
    return Lists.newArrayList(tables.getAllTableNames());
  }

  /**
   * Case-insensitive lookup. Returns null if a table does not exist, throws an
   * exception if the table metadata could not be loaded.
   */
  public Table getTable(String tbl) throws TableLoadingException {
      return tables.get(tbl);
  }
}
