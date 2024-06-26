/**
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.hadoop.hbase;

import static org.apache.hadoop.hbase.io.hfile.BlockType.MAGIC_LENGTH;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.commons.lang.ArrayUtils;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.hbase.util.Bytes;

/**
* HConstants holds a bunch of HBase-related constants
*/
@InterfaceAudience.Public
@InterfaceStability.Stable
public final class HConstants {
//Bytes.UTF8_ENCODING should be updated if this changed
/** When we encode strings, we always specify UTF8 encoding */
public static final String UTF8_ENCODING = "UTF-8";

//Bytes.UTF8_CHARSET should be updated if this changed
/** When we encode strings, we always specify UTF8 encoding */
public static final Charset UTF8_CHARSET = Charset.forName(UTF8_ENCODING);
/**
* Default block size for an HFile.
*/
public final static int DEFAULT_BLOCKSIZE = 64 * 1024;

/** Used as a magic return value while optimized index key feature enabled(HBASE-7845) */
public final static int INDEX_KEY_MAGIC = -2;
/*
* Name of directory that holds recovered edits written by the wal log
* splitting code, one per region
*/
public static final String RECOVERED_EDITS_DIR = "recovered.edits";
/**
* The first four bytes of Hadoop RPC connections
*/
public static final ByteBuffer RPC_HEADER = ByteBuffer.wrap("HBas".getBytes());
public static final byte RPC_CURRENT_VERSION = 0;

// HFileBlock constants.

/** The size data structures with minor version is 0 */
public static final int HFILEBLOCK_HEADER_SIZE_NO_CHECKSUM = MAGIC_LENGTH + 2 * Bytes.SIZEOF_INT
+ Bytes.SIZEOF_LONG;
/** The size of a version 2 HFile block header, minor version 1.
* There is a 1 byte checksum type, followed by a 4 byte bytesPerChecksum
* followed by another 4 byte value to store sizeofDataOnDisk.
*/
public static final int HFILEBLOCK_HEADER_SIZE = HFILEBLOCK_HEADER_SIZE_NO_CHECKSUM +
Bytes.SIZEOF_BYTE + 2 * Bytes.SIZEOF_INT;
/** Just an array of bytes of the right size. */
public static final byte[] HFILEBLOCK_DUMMY_HEADER = new byte[HFILEBLOCK_HEADER_SIZE];

//End HFileBlockConstants.

/**
* Status codes used for return values of bulk operations.
*/
public enum OperationStatusCode {
NOT_RUN,
SUCCESS,
BAD_FAMILY,
SANITY_CHECK_FAILURE,
FAILURE;
}

/** long constant for zero */
public static final Long ZERO_L = Long.valueOf(0L);
public static final String NINES = "99999999999999";
public static final String ZEROES = "00000000000000";

// For migration

/** name of version file */
public static final String VERSION_FILE_NAME = "hbase.version";

/**
* Current version of file system.
* Version 4 supports only one kind of bloom filter.
* Version 5 changes versions in catalog table regions.
* Version 6 enables blockcaching on catalog tables.
* Version 7 introduces hfile -- hbase 0.19 to 0.20..
* Version 8 introduces namespace
*/
// public static final String FILE_SYSTEM_VERSION = "6";
public static final String FILE_SYSTEM_VERSION = "8";

// Configuration parameters

//TODO: Is having HBase homed on port 60k OK?

/** Cluster is in distributed mode or not */
public static final String CLUSTER_DISTRIBUTED = "hbase.cluster.distributed";

/** Config for pluggable load balancers */
public static final String HBASE_MASTER_LOADBALANCER_CLASS = "hbase.master.loadbalancer.class";

/** Cluster is standalone or pseudo-distributed */
public static final boolean CLUSTER_IS_LOCAL = false;

/** Cluster is fully-distributed */
public static final boolean CLUSTER_IS_DISTRIBUTED = true;

/** Default value for cluster distributed mode */
public static final boolean DEFAULT_CLUSTER_DISTRIBUTED = CLUSTER_IS_LOCAL;

/** default host address */
public static final String DEFAULT_HOST = "0.0.0.0";

/** Parameter name for port master listens on. */
public static final String MASTER_PORT = "hbase.master.port";

/** default port that the master listens on */
public static final int DEFAULT_MASTER_PORT = 60000;

/** default port for master web api */
public static final int DEFAULT_MASTER_INFOPORT = 60010;

/** Configuration key for master web API port */
public static final String MASTER_INFO_PORT = "hbase.master.info.port";

/** Parameter name for the master type being backup (waits for primary to go inactive). */
public static final String MASTER_TYPE_BACKUP = "hbase.master.backup";

/** by default every master is a possible primary master unless the conf explicitly overrides it */
public static final boolean DEFAULT_MASTER_TYPE_BACKUP = false;

/** Name of ZooKeeper quorum configuration parameter. */
public static final String ZOOKEEPER_QUORUM = "hbase.zookeeper.quorum";

/** Name of ZooKeeper config file in conf/ directory. */
public static final String ZOOKEEPER_CONFIG_NAME = "zoo.cfg";

/** Common prefix of ZooKeeper configuration properties */
public static final String ZK_CFG_PROPERTY_PREFIX =
"hbase.zookeeper.property.";

public static final int ZK_CFG_PROPERTY_PREFIX_LEN =
ZK_CFG_PROPERTY_PREFIX.length();

/**
* The ZK client port key in the ZK properties map. The name reflects the
* fact that this is not an HBase configuration key.
*/
public static final String CLIENT_PORT_STR = "clientPort";

/** Parameter name for the client port that the zookeeper listens on */
public static final String ZOOKEEPER_CLIENT_PORT =
ZK_CFG_PROPERTY_PREFIX + CLIENT_PORT_STR;

/** Default client port that the zookeeper listens on */
public static final int DEFAULT_ZOOKEPER_CLIENT_PORT = 2181;

/** Parameter name for the wait time for the recoverable zookeeper */
public static final String ZOOKEEPER_RECOVERABLE_WAITTIME = "hbase.zookeeper.recoverable.waittime";

/** Default wait time for the recoverable zookeeper */
public static final long DEFAULT_ZOOKEPER_RECOVERABLE_WAITIME = 10000;

/** Parameter name for the root dir in ZK for this cluster */
public static final String ZOOKEEPER_ZNODE_PARENT = "zookeeper.znode.parent";

public static final String DEFAULT_ZOOKEEPER_ZNODE_PARENT = "/hbase";

/**
* Parameter name for the limit on concurrent client-side zookeeper
* connections
*/
public static final String ZOOKEEPER_MAX_CLIENT_CNXNS =
ZK_CFG_PROPERTY_PREFIX + "maxClientCnxns";

/** Parameter name for the ZK data directory */
public static final String ZOOKEEPER_DATA_DIR =
ZK_CFG_PROPERTY_PREFIX + "dataDir";

/** Default limit on concurrent client-side zookeeper connections */
public static final int DEFAULT_ZOOKEPER_MAX_CLIENT_CNXNS = 300;

/** Configuration key for ZooKeeper session timeout */
public static final String ZK_SESSION_TIMEOUT = "zookeeper.session.timeout";

/** Default value for ZooKeeper session timeout */
public static final int DEFAULT_ZK_SESSION_TIMEOUT = 180 * 1000;

/** Configuration key for whether to use ZK.multi */
public static final String ZOOKEEPER_USEMULTI = "hbase.zookeeper.useMulti";

/** Parameter name for port region server listens on. */
public static final String REGIONSERVER_PORT = "hbase.regionserver.port";

/** Default port region server listens on. */
public static final int DEFAULT_REGIONSERVER_PORT = 60020;

/** default port for region server web api */
public static final int DEFAULT_REGIONSERVER_INFOPORT = 60030;

/** A configuration key for regionserver info port */
public static final String REGIONSERVER_INFO_PORT =
"hbase.regionserver.info.port";

/** A flag that enables automatic selection of regionserver info port */
public static final String REGIONSERVER_INFO_PORT_AUTO =
REGIONSERVER_INFO_PORT + ".auto";

/** Parameter name for what region server implementation to use. */
public static final String REGION_SERVER_IMPL= "hbase.regionserver.impl";

/** Parameter name for what master implementation to use. */
public static final String MASTER_IMPL= "hbase.master.impl";

/** Parameter name for what hbase client implementation to use. */
public static final String HBASECLIENT_IMPL= "hbase.hbaseclient.impl";

/** Parameter name for how often threads should wake up */
public static final String THREAD_WAKE_FREQUENCY = "hbase.server.thread.wakefrequency";

/** Default value for thread wake frequency */
public static final int DEFAULT_THREAD_WAKE_FREQUENCY = 10 * 1000;

/** Parameter name for how often we should try to write a version file, before failing */
public static final String VERSION_FILE_WRITE_ATTEMPTS = "hbase.server.versionfile.writeattempts";

/** Parameter name for how often we should try to write a version file, before failing */
public static final int DEFAULT_VERSION_FILE_WRITE_ATTEMPTS = 3;

/** Parameter name for how often a region should should perform a major compaction */
public static final String MAJOR_COMPACTION_PERIOD = "hbase.hregion.majorcompaction";

/** Parameter name for the maximum batch of KVs to be used in flushes and compactions */
public static final String COMPACTION_KV_MAX = "hbase.hstore.compaction.kv.max";

/** Parameter name for HBase instance root directory */
public static final String HBASE_DIR = "hbase.rootdir";

/** Parameter name for HBase client IPC pool type */
public static final String HBASE_CLIENT_IPC_POOL_TYPE = "hbase.client.ipc.pool.type";

/** Parameter name for HBase client IPC pool size */
public static final String HBASE_CLIENT_IPC_POOL_SIZE = "hbase.client.ipc.pool.size";

/** Parameter name for HBase client operation timeout, which overrides RPC timeout */
public static final String HBASE_CLIENT_OPERATION_TIMEOUT = "hbase.client.operation.timeout";

/** Parameter name for HBase client operation timeout, which overrides RPC timeout */
public static final String HBASE_CLIENT_META_OPERATION_TIMEOUT =
"hbase.client.meta.operation.timeout";

/** Default HBase client operation timeout, which is tantamount to a blocking call */
public static final int DEFAULT_HBASE_CLIENT_OPERATION_TIMEOUT = Integer.MAX_VALUE;

/** Used to construct the name of the log directory for a region server */
public static final String HREGION_LOGDIR_NAME = "WALs";

/** Used to construct the name of the splitlog directory for a region server */
public static final String SPLIT_LOGDIR_NAME = "splitWAL";

/** Like the previous, but for old logs that are about to be deleted */
public static final String HREGION_OLDLOGDIR_NAME = "oldWALs";

public static final String CORRUPT_DIR_NAME = "corrupt";

/** Used by HBCK to sideline backup data */
public static final String HBCK_SIDELINEDIR_NAME = ".hbck";

/** Any artifacts left from migration can be moved here */
public static final String MIGRATION_NAME = ".migration";

/** Used to construct the name of the compaction directory during compaction */
public static final String HREGION_COMPACTIONDIR_NAME = "compaction.dir";

/** Conf key for the max file size after which we split the region */
public static final String HREGION_MAX_FILESIZE =
"hbase.hregion.max.filesize";

/** Default maximum file size */
public static final long DEFAULT_MAX_FILE_SIZE = 10 * 1024 * 1024 * 1024L;

/**
* The max number of threads used for opening and closing stores or store
* files in parallel
*/
public static final String HSTORE_OPEN_AND_CLOSE_THREADS_MAX =
"hbase.hstore.open.and.close.threads.max";

/**
* The default number for the max number of threads used for opening and
* closing stores or store files in parallel
*/
public static final int DEFAULT_HSTORE_OPEN_AND_CLOSE_THREADS_MAX = 1;


/** Conf key for the memstore size at which we flush the memstore */
public static final String HREGION_MEMSTORE_FLUSH_SIZE =
"hbase.hregion.memstore.flush.size";

public static final String HREGION_EDITS_REPLAY_SKIP_ERRORS =
"hbase.hregion.edits.replay.skip.errors";

public static final boolean DEFAULT_HREGION_EDITS_REPLAY_SKIP_ERRORS =
false;

/** Maximum value length, enforced on KeyValue construction */
public static final int MAXIMUM_VALUE_LENGTH = Integer.MAX_VALUE - 1;

/** name of the file for unique cluster ID */
public static final String CLUSTER_ID_FILE_NAME = "hbase.id";

/** Default value for cluster ID */
public static final String CLUSTER_ID_DEFAULT = "default-cluster";

// Always store the location of the root table's HRegion.
// This HRegion is never split.

// region name = table + startkey + regionid. This is the row key.
// each row in the root and meta tables describes exactly 1 region
// Do we ever need to know all the information that we are storing?

// Note that the name of the root table starts with "-" and the name of the
// meta table starts with "." Why? it's a trick. It turns out that when we
// store region names in memory, we use a SortedMap. Since "-" sorts before
// "." (and since no other table name can start with either of these
// characters, the root region will always be the first entry in such a Map,
// followed by all the meta regions (which will be ordered by their starting
// row key as well), followed by all user tables. So when the Master is
// choosing regions to assign, it will always choose the root region first,
// followed by the meta regions, followed by user regions. Since the root
// and meta regions always need to be on-line, this ensures that they will
// be the first to be reassigned if the server(s) they are being served by
// should go down.

public static final String BASE_NAMESPACE_DIR = "data";

/** delimiter used between portions of a region name */
public static final int META_ROW_DELIMITER = ',';

/** The catalog family as a string*/
public static final String CATALOG_FAMILY_STR = "info";

/** The catalog family */
public static final byte [] CATALOG_FAMILY = Bytes.toBytes(CATALOG_FAMILY_STR);

/** The RegionInfo qualifier as a string */
public static final String REGIONINFO_QUALIFIER_STR = "regioninfo";

/** The regioninfo column qualifier */
public static final byte [] REGIONINFO_QUALIFIER = Bytes.toBytes(REGIONINFO_QUALIFIER_STR);

/** The server column qualifier */
public static final byte [] SERVER_QUALIFIER = Bytes.toBytes("server");

/** The startcode column qualifier */
public static final byte [] STARTCODE_QUALIFIER = Bytes.toBytes("serverstartcode");

/** The open seqnum column qualifier */
public static final byte [] SEQNUM_QUALIFIER = Bytes.toBytes("seqnumDuringOpen");

/** The lower-half split region column qualifier */
public static final byte [] SPLITA_QUALIFIER = Bytes.toBytes("splitA");

/** The upper-half split region column qualifier */
public static final byte [] SPLITB_QUALIFIER = Bytes.toBytes("splitB");

/** The lower-half merge region column qualifier */
public static final byte[] MERGEA_QUALIFIER = Bytes.toBytes("mergeA");

/** The upper-half merge region column qualifier */
public static final byte[] MERGEB_QUALIFIER = Bytes.toBytes("mergeB");

/**
* The meta table version column qualifier.
* We keep current version of the meta table in this column in <code>-ROOT-</code>
* table: i.e. in the 'info:v' column.
*/
public static final byte [] META_VERSION_QUALIFIER = Bytes.toBytes("v");

/**
* The current version of the meta table.
* - pre-hbase 0.92.  There is no META_VERSION column in the root table
* in this case. The meta has HTableDescriptor serialized into the HRegionInfo;
* - version 0 is 0.92 and 0.94. Meta data has serialized HRegionInfo's using
* Writable serialization, and HRegionInfo's does not contain HTableDescriptors.
* - version 1 for 0.96+ keeps HRegionInfo data structures, but changes the
* byte[] serialization from Writables to Protobuf.
* See HRegionInfo.VERSION
*/
public static final short META_VERSION = 1;

// Other constants

/**
* An empty instance.
*/
public static final byte [] EMPTY_BYTE_ARRAY = new byte [0];

/**
* Used by scanners, etc when they want to start at the beginning of a region
*/
public static final byte [] EMPTY_START_ROW = EMPTY_BYTE_ARRAY;

/**
* Last row in a table.
*/
public static final byte [] EMPTY_END_ROW = EMPTY_START_ROW;

/**
* Used by scanners and others when they're trying to detect the end of a
* table
*/
public static final byte [] LAST_ROW = EMPTY_BYTE_ARRAY;

/**
* Max length a row can have because of the limitation in TFile.
*/
public static final int MAX_ROW_LENGTH = Short.MAX_VALUE;

/**
* Timestamp to use when we want to refer to the latest cell.
* This is the timestamp sent by clients when no timestamp is specified on
* commit.
*/
public static final long LATEST_TIMESTAMP = Long.MAX_VALUE;

/**
* Timestamp to use when we want to refer to the oldest cell.
*/
public static final long OLDEST_TIMESTAMP = Long.MIN_VALUE;

/**
* LATEST_TIMESTAMP in bytes form
*/
public static final byte [] LATEST_TIMESTAMP_BYTES = {
// big-endian
(byte) (LATEST_TIMESTAMP >>> 56),
(byte) (LATEST_TIMESTAMP >>> 48),
(byte) (LATEST_TIMESTAMP >>> 40),
(byte) (LATEST_TIMESTAMP >>> 32),
(byte) (LATEST_TIMESTAMP >>> 24),
(byte) (LATEST_TIMESTAMP >>> 16),
(byte) (LATEST_TIMESTAMP >>> 8),
(byte) LATEST_TIMESTAMP,
};

/**
* Define for 'return-all-versions'.
*/
public static final int ALL_VERSIONS = Integer.MAX_VALUE;

/**
* Unlimited time-to-live.
*/
//  public static final int FOREVER = -1;
public static final int FOREVER = Integer.MAX_VALUE;

/**
* Seconds in a week
*/
public static final int WEEK_IN_SECONDS = 7 * 24 * 3600;

//TODO: although the following are referenced widely to format strings for
//      the shell. They really aren't a part of the public API. It would be
//      nice if we could put them somewhere where they did not need to be
//      public. They could have package visibility
public static final String NAME = "NAME";
public static final String VERSIONS = "VERSIONS";
public static final String IN_MEMORY = "IN_MEMORY";
public static final String METADATA = "METADATA";
public static final String CONFIGURATION = "CONFIGURATION";

/**
* Retrying we multiply hbase.client.pause setting by what we have in this array until we
* run out of array items.  Retries beyond this use the last number in the array.  So, for
* example, if hbase.client.pause is 1 second, and maximum retries count
* hbase.client.retries.number is 10, we will retry at the following intervals:
* 1, 2, 3, 10, 100, 100, 100, 100, 100, 100.
*/
public static int RETRY_BACKOFF[] = { 1, 2, 3, 5, 10, 100 };

public static final String REGION_IMPL = "hbase.hregion.impl";

/** modifyTable op for replacing the table descriptor */
public static enum Modify {
CLOSE_REGION,
TABLE_COMPACT,
TABLE_FLUSH,
TABLE_MAJOR_COMPACT,
TABLE_SET_HTD,
TABLE_SPLIT
}

/**
* Scope tag for locally scoped data.
* This data will not be replicated.
*/
public static final int REPLICATION_SCOPE_LOCAL = 0;

/**
* Scope tag for globally scoped data.
* This data will be replicated to all peers.
*/
public static final int REPLICATION_SCOPE_GLOBAL = 1;

/**
* Default cluster ID, cannot be used to identify a cluster so a key with
* this value means it wasn't meant for replication.
*/
public static final UUID DEFAULT_CLUSTER_ID = new UUID(0L,0L);

/**
* Parameter name for maximum number of bytes returned when calling a
* scanner's next method.
*/
public static String HBASE_CLIENT_SCANNER_MAX_RESULT_SIZE_KEY = "hbase.client.scanner.max.result.size";

/**
* Maximum number of bytes returned when calling a scanner's next method.
* Note that when a single row is larger than this limit the row is still
* returned completely.
*
* The default value is unlimited.
*/
public static long DEFAULT_HBASE_CLIENT_SCANNER_MAX_RESULT_SIZE = Long.MAX_VALUE;

/**
* Parameter name for client pause value, used mostly as value to wait
* before running a retry of a failed get, region lookup, etc.
*/
public static String HBASE_CLIENT_PAUSE = "hbase.client.pause";

/**
* Default value of {@link #HBASE_CLIENT_PAUSE}.
*/
public static long DEFAULT_HBASE_CLIENT_PAUSE = 1000;

/**
* Parameter name for server pause value, used mostly as value to wait before
* running a retry of a failed operation.
*/
public static String HBASE_SERVER_PAUSE = "hbase.server.pause";

/**
* Default value of {@link #HBASE_SERVER_PAUSE}.
*/
public static int DEFAULT_HBASE_SERVER_PAUSE = 1000;

/**
* Parameter name for maximum retries, used as maximum for all retryable
* operations such as fetching of the root region from root region server,
* getting a cell's value, starting a row update, etc.
*/
public static String HBASE_CLIENT_RETRIES_NUMBER = "hbase.client.retries.number";

/**
* Default value of {@link #HBASE_CLIENT_RETRIES_NUMBER}.
*/
public static int DEFAULT_HBASE_CLIENT_RETRIES_NUMBER = 31;

/**
* Parameter name for client prefetch limit, used as the maximum number of regions
* info that will be prefetched.
*/
public static String HBASE_CLIENT_PREFETCH_LIMIT = "hbase.client.prefetch.limit";

/**
* Default value of {@link #HBASE_CLIENT_PREFETCH_LIMIT}.
*/
public static int DEFAULT_HBASE_CLIENT_PREFETCH_LIMIT = 10;

/**
* Parameter name to set the default scanner caching for all clients.
*/
public static String HBASE_CLIENT_SCANNER_CACHING = "hbase.client.scanner.caching";

/**
* Default value for {@link #HBASE_CLIENT_SCANNER_CACHING}
*/
public static int DEFAULT_HBASE_CLIENT_SCANNER_CACHING = 100;

/**
* Parameter name for number of rows that will be fetched when calling next on
* a scanner if it is not served from memory. Higher caching values will
* enable faster scanners but will eat up more memory and some calls of next
* may take longer and longer times when the cache is empty.
*/
public static String HBASE_META_SCANNER_CACHING = "hbase.meta.scanner.caching";

/**
* Default value of {@link #HBASE_META_SCANNER_CACHING}.
*/
public static int DEFAULT_HBASE_META_SCANNER_CACHING = 100;

/**
* Parameter name for unique identifier for this {@link org.apache.hadoop.conf.Configuration}
* instance. If there are two or more {@link org.apache.hadoop.conf.Configuration} instances that,
* for all intents and purposes, are the same except for their instance ids, then they will not be
* able to share the same org.apache.hadoop.hbase.client.HConnection instance. On the other hand,
* even if the instance ids are the same, it could result in non-shared
* org.apache.hadoop.hbase.client.HConnection instances if some of the other connection parameters
* differ.
*/
public static String HBASE_CLIENT_INSTANCE_ID = "hbase.client.instance.id";

/**
* The client scanner timeout period in milliseconds.
*/
public static String HBASE_CLIENT_SCANNER_TIMEOUT_PERIOD = "hbase.client.scanner.timeout.period";

/**
* Use {@link #HBASE_CLIENT_SCANNER_TIMEOUT_PERIOD} instead.
* @deprecated This config option is deprecated. Will be removed at later releases after 0.96.
*/
@Deprecated
public static String HBASE_REGIONSERVER_LEASE_PERIOD_KEY =
"hbase.regionserver.lease.period";

/**
* Default value of {@link #HBASE_CLIENT_SCANNER_TIMEOUT_PERIOD}.
*/
public static int DEFAULT_HBASE_CLIENT_SCANNER_TIMEOUT_PERIOD = 60000;

/**
* timeout for each RPC
*/
public static String HBASE_RPC_TIMEOUT_KEY = "hbase.rpc.timeout";

/**
* Default value of {@link #HBASE_RPC_TIMEOUT_KEY}
*/
public static int DEFAULT_HBASE_RPC_TIMEOUT = 60000;

/**
* timeout for short operation RPC
*/
public static String HBASE_RPC_SHORTOPERATION_TIMEOUT_KEY = "hbase.rpc.shortoperation.timeout";

/**
* Default value of {@link #HBASE_RPC_SHORTOPERATION_TIMEOUT_KEY}
*/
public static int DEFAULT_HBASE_RPC_SHORTOPERATION_TIMEOUT = 10000;

/**
* Value indicating the server name was saved with no sequence number.
*/
public static final long NO_SEQNUM = -1;


/*
* cluster replication constants.
*/
public static final String
REPLICATION_ENABLE_KEY = "hbase.replication";
public static final String
REPLICATION_SOURCE_SERVICE_CLASSNAME = "hbase.replication.source.service";
public static final String
REPLICATION_SINK_SERVICE_CLASSNAME = "hbase.replication.sink.service";
public static final String REPLICATION_SERVICE_CLASSNAME_DEFAULT =
"org.apache.hadoop.hbase.replication.regionserver.Replication";

/** HBCK special code name used as server name when manipulating ZK nodes */
public static final String HBCK_CODE_NAME = "HBCKServerName";

public static final String KEY_FOR_HOSTNAME_SEEN_BY_MASTER =
"hbase.regionserver.hostname.seen.by.master";

public static final String HBASE_MASTER_LOGCLEANER_PLUGINS =
"hbase.master.logcleaner.plugins";

public static final String HBASE_REGION_SPLIT_POLICY_KEY =
"hbase.regionserver.region.split.policy";

/**
* Configuration key for the size of the block cache
*/
public static final String HFILE_BLOCK_CACHE_SIZE_KEY =
"hfile.block.cache.size";

public static final float HFILE_BLOCK_CACHE_SIZE_DEFAULT = 0.25f;

/*
* Minimum percentage of free heap necessary for a successful cluster startup.
*/
public static final float HBASE_CLUSTER_MINIMUM_MEMORY_THRESHOLD = 0.2f;

public static final Pattern CP_HTD_ATTR_KEY_PATTERN = Pattern.compile
("^coprocessor\\$([0-9]+)$", Pattern.CASE_INSENSITIVE);
public static final Pattern CP_HTD_ATTR_VALUE_PATTERN =
Pattern.compile("(^[^\\|]*)\\|([^\\|]+)\\|[\\s]*([\\d]*)[\\s]*(\\|.*)?$");

public static final String CP_HTD_ATTR_VALUE_PARAM_KEY_PATTERN = "[^=,]+";
public static final String CP_HTD_ATTR_VALUE_PARAM_VALUE_PATTERN = "[^,]+";
public static final Pattern CP_HTD_ATTR_VALUE_PARAM_PATTERN = Pattern.compile(
"(" + CP_HTD_ATTR_VALUE_PARAM_KEY_PATTERN + ")=(" +
CP_HTD_ATTR_VALUE_PARAM_VALUE_PATTERN + "),?");

/** The delay when re-trying a socket operation in a loop (HBASE-4712) */
public static final int SOCKET_RETRY_WAIT_MS = 200;

/** Host name of the local machine */
public static final String LOCALHOST = "localhost";

/**
* If this parameter is set to true, then hbase will read
* data and then verify checksums. Checksum verification
* inside hdfs will be switched off.  However, if the hbase-checksum
* verification fails, then it will switch back to using
* hdfs checksums for verifiying data that is being read from storage.
*
* If this parameter is set to false, then hbase will not
* verify any checksums, instead it will depend on checksum verification
* being done in the hdfs client.
*/
public static final String HBASE_CHECKSUM_VERIFICATION =
"hbase.regionserver.checksum.verify";

public static final String LOCALHOST_IP = "127.0.0.1";

/** Conf key that enables unflushed WAL edits directly being replayed to region servers */
public static final String DISTRIBUTED_LOG_REPLAY_KEY = "hbase.master.distributed.log.replay";
public static final boolean DEFAULT_DISTRIBUTED_LOG_REPLAY_CONFIG = false;
public static final String DISALLOW_WRITES_IN_RECOVERING =
"hbase.regionserver.disallow.writes.when.recovering";
public static final boolean DEFAULT_DISALLOW_WRITES_IN_RECOVERING_CONFIG = false;

/** Conf key that specifies timeout value to wait for a region ready */
public static final String LOG_REPLAY_WAIT_REGION_TIMEOUT =
"hbase.master.log.replay.wait.region.timeout";

/**
* The name of the configuration parameter that specifies
* the number of bytes in a newly created checksum chunk.
*/
public static final String BYTES_PER_CHECKSUM =
"hbase.hstore.bytes.per.checksum";

/**
* The name of the configuration parameter that specifies
* the name of an algorithm that is used to compute checksums
* for newly created blocks.
*/
public static final String CHECKSUM_TYPE_NAME =
"hbase.hstore.checksum.algorithm";

/** Enable file permission modification from standard hbase */
public static final String ENABLE_DATA_FILE_UMASK = "hbase.data.umask.enable";
/** File permission umask to use when creating hbase data files */
public static final String DATA_FILE_UMASK_KEY = "hbase.data.umask";

/** Configuration name of HLog Compression */
public static final String ENABLE_WAL_COMPRESSION =
"hbase.regionserver.wal.enablecompression";

/** Region in Transition metrics threshold time */
public static final String METRICS_RIT_STUCK_WARNING_THRESHOLD="hbase.metrics.rit.stuck.warning.threshold";

public static final String LOAD_BALANCER_SLOP_KEY = "hbase.regions.slop";

/**
* The byte array represents for NO_NEXT_INDEXED_KEY;
* The actual value is irrelevant because this is always compared by reference.
*/
public static final byte [] NO_NEXT_INDEXED_KEY = Bytes.toBytes("NO_NEXT_INDEXED_KEY");
/** delimiter used between portions of a region name */
public static final int DELIMITER = ',';
public static final String HBASE_CONFIG_READ_ZOOKEEPER_CONFIG =
"hbase.config.read.zookeeper.config";
public static final boolean DEFAULT_HBASE_CONFIG_READ_ZOOKEEPER_CONFIG =
false;

/**
* QOS attributes: these attributes are used to demarcate RPC call processing
* by different set of handlers. For example, HIGH_QOS tagged methods are
* handled by high priority handlers.
*/
public static final int NORMAL_QOS = 0;
public static final int QOS_THRESHOLD = 10;
public static final int HIGH_QOS = 100;
public static final int REPLICATION_QOS = 5; // normal_QOS < replication_QOS < high_QOS
public static final int REPLAY_QOS = 6; // REPLICATION_QOS < REPLAY_QOS < high_QOS

/** Directory under /hbase where archived hfiles are stored */
public static final String HFILE_ARCHIVE_DIRECTORY = "archive";

/**
* Name of the directory to store all snapshots. See SnapshotDescriptionUtils for
* remaining snapshot constants; this is here to keep HConstants dependencies at a minimum and
* uni-directional.
*/
public static final String SNAPSHOT_DIR_NAME = ".hbase-snapshot";

/* Name of old snapshot directory. See HBASE-8352 for details on why it needs to be renamed */
public static final String OLD_SNAPSHOT_DIR_NAME = ".snapshot";

/** Temporary directory used for table creation and deletion */
public static final String HBASE_TEMP_DIRECTORY = ".tmp";

/** Directories that are not HBase table directories */
public static final List<String> HBASE_NON_TABLE_DIRS =
Collections.unmodifiableList(Arrays.asList(new String[] { HREGION_LOGDIR_NAME,
HREGION_OLDLOGDIR_NAME, CORRUPT_DIR_NAME, SPLIT_LOGDIR_NAME,
HBCK_SIDELINEDIR_NAME, HFILE_ARCHIVE_DIRECTORY, SNAPSHOT_DIR_NAME, HBASE_TEMP_DIRECTORY,
OLD_SNAPSHOT_DIR_NAME, BASE_NAMESPACE_DIR, MIGRATION_NAME}));

/** Directories that are not HBase user table directories */
public static final List<String> HBASE_NON_USER_TABLE_DIRS =
Collections.unmodifiableList(Arrays.asList((String[])ArrayUtils.addAll(
new String[] { TableName.META_TABLE_NAME.getNameAsString(), TableName.ROOT_TABLE_NAME.getNameAsString() },
HBASE_NON_TABLE_DIRS.toArray())));

/** Health script related settings. */
public static final String HEALTH_SCRIPT_LOC = "hbase.node.health.script.location";
public static final String HEALTH_SCRIPT_TIMEOUT = "hbase.node.health.script.timeout";
public static final String HEALTH_CHORE_WAKE_FREQ =
"hbase.node.health.script.frequency";
public static final long DEFAULT_HEALTH_SCRIPT_TIMEOUT = 60000;
/**
* The maximum number of health check failures a server can encounter consecutively.
*/
public static final String HEALTH_FAILURE_THRESHOLD =
"hbase.node.health.failure.threshold";
public static final int DEFAULT_HEALTH_FAILURE_THRESHOLD = 3;


/**
* IP to use for the multicast status messages between the master and the clients.
* The default address is chosen as one among others within the ones suitable for multicast
* messages.
*/
public static final String STATUS_MULTICAST_ADDRESS = "hbase.status.multicast.address.ip";
public static final String DEFAULT_STATUS_MULTICAST_ADDRESS = "226.1.1.3";

/**
* The port to use for the multicast messages.
*/
public static final String STATUS_MULTICAST_PORT = "hbase.status.multicast.port";
public static final int DEFAULT_STATUS_MULTICAST_PORT = 60100;



private HConstants() {
// Can't be instantiated with this ctor.
}
}