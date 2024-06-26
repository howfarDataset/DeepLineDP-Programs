/**
*
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
package org.apache.hadoop.hbase.client;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Abortable;
import org.apache.hadoop.hbase.ClusterStatus;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HBaseIOException;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.NamespaceDescriptor;
import org.apache.hadoop.hbase.MasterNotRunningException;
import org.apache.hadoop.hbase.NotServingRegionException;
import org.apache.hadoop.hbase.RegionException;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableExistsException;
import org.apache.hadoop.hbase.TableNotEnabledException;
import org.apache.hadoop.hbase.TableNotFoundException;
import org.apache.hadoop.hbase.UnknownRegionException;
import org.apache.hadoop.hbase.ZooKeeperConnectionException;
import org.apache.hadoop.hbase.catalog.CatalogTracker;
import org.apache.hadoop.hbase.catalog.MetaReader;
import org.apache.hadoop.hbase.client.MetaScanner.MetaScannerVisitor;
import org.apache.hadoop.hbase.client.MetaScanner.MetaScannerVisitorBase;
import org.apache.hadoop.hbase.exceptions.DeserializationException;
import org.apache.hadoop.hbase.regionserver.wal.FailedLogCloseException;
import org.apache.hadoop.hbase.snapshot.HBaseSnapshotException;
import org.apache.hadoop.hbase.exceptions.MergeRegionException;
import org.apache.hadoop.hbase.snapshot.RestoreSnapshotException;
import org.apache.hadoop.hbase.snapshot.SnapshotCreationException;
import org.apache.hadoop.hbase.snapshot.UnknownSnapshotException;
import org.apache.hadoop.hbase.ipc.CoprocessorRpcChannel;
import org.apache.hadoop.hbase.ipc.MasterCoprocessorRpcChannel;
import org.apache.hadoop.hbase.ipc.PayloadCarryingRpcController;
import org.apache.hadoop.hbase.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.protobuf.RequestConverter;
import org.apache.hadoop.hbase.protobuf.ResponseConverter;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.AdminService;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.CloseRegionRequest;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.CloseRegionResponse;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.CompactRegionRequest;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.FlushRegionRequest;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.GetRegionInfoRequest;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.GetRegionInfoResponse;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.GetRegionInfoResponse.CompactionState;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.RollWALWriterRequest;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.RollWALWriterResponse;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.StopServerRequest;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.ClientService;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.ScanRequest;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.ScanResponse;
import org.apache.hadoop.hbase.protobuf.generated.HBaseProtos;
import org.apache.hadoop.hbase.protobuf.generated.HBaseProtos.SnapshotDescription;
import org.apache.hadoop.hbase.protobuf.generated.HBaseProtos.TableSchema;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.AddColumnRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.AssignRegionRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.CreateTableRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.DeleteColumnRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.DeleteSnapshotRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.DeleteTableRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.DisableTableRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.DispatchMergingRegionsRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.EnableTableRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.IsRestoreSnapshotDoneRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.IsRestoreSnapshotDoneResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.IsSnapshotDoneRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.IsSnapshotDoneResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.ListSnapshotRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.ModifyColumnRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.ModifyTableRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.MoveRegionRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.RestoreSnapshotRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.RestoreSnapshotResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.SetBalancerRunningRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.ShutdownRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.StopMasterRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.TakeSnapshotRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.TakeSnapshotResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.UnassignRegionRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterMonitorProtos.GetClusterStatusRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterMonitorProtos.GetSchemaAlterStatusRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterMonitorProtos.GetSchemaAlterStatusResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterMonitorProtos.GetTableDescriptorsRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterMonitorProtos.GetTableDescriptorsResponse;
import org.apache.hadoop.hbase.snapshot.ClientSnapshotDescriptionUtils;
import org.apache.hadoop.hbase.util.Addressing;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.util.StringUtils;
import org.apache.zookeeper.KeeperException;

import com.google.protobuf.ByteString;
import com.google.protobuf.ServiceException;

/**
* Provides an interface to manage HBase database table metadata + general
* administrative functions.  Use HBaseAdmin to create, drop, list, enable and
* disable tables. Use it also to add and drop table column families.
*
* <p>See {@link HTable} to add, update, and delete data from an individual table.
* <p>Currently HBaseAdmin instances are not expected to be long-lived.  For
* example, an HBaseAdmin instance will not ride over a Master restart.
*/
@InterfaceAudience.Public
@InterfaceStability.Evolving
public class HBaseAdmin implements Abortable, Closeable {
private static final Log LOG = LogFactory.getLog(HBaseAdmin.class);

// We use the implementation class rather then the interface because we
//  need the package protected functions to get the connection to master
private HConnection connection;

private volatile Configuration conf;
private final long pause;
private final int numRetries;
// Some operations can take a long time such as disable of big table.
// numRetries is for 'normal' stuff... Multiply by this factor when
// want to wait a long time.
private final int retryLongerMultiplier;
private boolean aborted;
private boolean cleanupConnectionOnClose = false; // close the connection in close()

private RpcRetryingCallerFactory rpcCallerFactory;

/**
* Constructor.
* See {@link #HBaseAdmin(HConnection connection)}
*
* @param c Configuration object. Copied internally.
*/
public HBaseAdmin(Configuration c)
throws MasterNotRunningException, ZooKeeperConnectionException, IOException {
// Will not leak connections, as the new implementation of the constructor
// does not throw exceptions anymore.
this(HConnectionManager.getConnection(new Configuration(c)));
this.cleanupConnectionOnClose = true;
}

/**
* Constructor for externally managed HConnections.
* The connection to master will be created when required by admin functions.
*
* @param connection The HConnection instance to use
* @throws MasterNotRunningException, ZooKeeperConnectionException are not
*  thrown anymore but kept into the interface for backward api compatibility
*/
public HBaseAdmin(HConnection connection)
throws MasterNotRunningException, ZooKeeperConnectionException {
this.conf = connection.getConfiguration();
this.connection = connection;

this.pause = this.conf.getLong("hbase.client.pause", 1000);
this.numRetries = this.conf.getInt(HConstants.HBASE_CLIENT_RETRIES_NUMBER,
HConstants.DEFAULT_HBASE_CLIENT_RETRIES_NUMBER);
this.retryLongerMultiplier = this.conf.getInt(
"hbase.client.retries.longer.multiplier", 10);
this.rpcCallerFactory = RpcRetryingCallerFactory.instantiate(this.conf);
}

/**
* @return A new CatalogTracker instance; call {@link #cleanupCatalogTracker(CatalogTracker)}
* to cleanup the returned catalog tracker.
* @throws org.apache.hadoop.hbase.ZooKeeperConnectionException
* @throws IOException
* @see #cleanupCatalogTracker(CatalogTracker)
*/
private synchronized CatalogTracker getCatalogTracker()
throws ZooKeeperConnectionException, IOException {
CatalogTracker ct = null;
try {
ct = new CatalogTracker(this.conf);
ct.start();
} catch (InterruptedException e) {
// Let it out as an IOE for now until we redo all so tolerate IEs
Thread.currentThread().interrupt();
throw new IOException("Interrupted", e);
}
return ct;
}

private void cleanupCatalogTracker(final CatalogTracker ct) {
ct.stop();
}

@Override
public void abort(String why, Throwable e) {
// Currently does nothing but throw the passed message and exception
this.aborted = true;
throw new RuntimeException(why, e);
}

@Override
public boolean isAborted(){
return this.aborted;
}

/** @return HConnection used by this object. */
public HConnection getConnection() {
return connection;
}

/** @return - true if the master server is running. Throws an exception
*  otherwise.
* @throws ZooKeeperConnectionException
* @throws MasterNotRunningException
*/
public boolean isMasterRunning()
throws MasterNotRunningException, ZooKeeperConnectionException {
return connection.isMasterRunning();
}

/**
* @param tableName Table to check.
* @return True if table exists already.
* @throws IOException
*/
public boolean tableExists(final TableName tableName)
throws IOException {
boolean b = false;
CatalogTracker ct = getCatalogTracker();
try {
b = MetaReader.tableExists(ct, tableName);
} finally {
cleanupCatalogTracker(ct);
}
return b;
}

public boolean tableExists(final byte[] tableName)
throws IOException {
return tableExists(TableName.valueOf(tableName));
}

public boolean tableExists(final String tableName)
throws IOException {
return tableExists(TableName.valueOf(tableName));
}

/**
* List all the userspace tables.  In other words, scan the META table.
*
* If we wanted this to be really fast, we could implement a special
* catalog table that just contains table names and their descriptors.
* Right now, it only exists as part of the META table's region info.
*
* @return - returns an array of HTableDescriptors
* @throws IOException if a remote or network exception occurs
*/
public HTableDescriptor[] listTables() throws IOException {
return this.connection.listTables();
}

/**
* List all the userspace tables matching the given pattern.
*
* @param pattern The compiled regular expression to match against
* @return - returns an array of HTableDescriptors
* @throws IOException if a remote or network exception occurs
* @see #listTables()
*/
public HTableDescriptor[] listTables(Pattern pattern) throws IOException {
List<HTableDescriptor> matched = new LinkedList<HTableDescriptor>();
HTableDescriptor[] tables = listTables();
for (HTableDescriptor table : tables) {
if (pattern.matcher(table.getTableName().getNameAsString()).matches()) {
matched.add(table);
}
}
return matched.toArray(new HTableDescriptor[matched.size()]);
}

/**
* List all the userspace tables matching the given regular expression.
*
* @param regex The regular expression to match against
* @return - returns an array of HTableDescriptors
* @throws IOException if a remote or network exception occurs
* @see #listTables(java.util.regex.Pattern)
*/
public HTableDescriptor[] listTables(String regex) throws IOException {
return listTables(Pattern.compile(regex));
}

/**
* List all of the names of userspace tables.
* @return String[] table names
* @throws IOException if a remote or network exception occurs
*/
@Deprecated
public String[] getTableNames() throws IOException {
return this.connection.getTableNames();
}

/**
* List all of the names of userspace tables matching the given regular expression.
* @param pattern The regular expression to match against
* @return String[] table names
* @throws IOException if a remote or network exception occurs
*/
@Deprecated
public String[] getTableNames(Pattern pattern) throws IOException {
List<String> matched = new ArrayList<String>();
for (String name: this.connection.getTableNames()) {
if (pattern.matcher(name).matches()) {
matched.add(name);
}
}
return matched.toArray(new String[matched.size()]);
}

/**
* List all of the names of userspace tables matching the given regular expression.
* @param regex The regular expression to match against
* @return String[] table names
* @throws IOException if a remote or network exception occurs
*/
@Deprecated
public String[] getTableNames(String regex) throws IOException {
return getTableNames(Pattern.compile(regex));
}

/**
* List all of the names of userspace tables.
* @return TableName[] table names
* @throws IOException if a remote or network exception occurs
*/
public TableName[] listTableNames() throws IOException {
return this.connection.listTableNames();
}

/**
* Method for getting the tableDescriptor
* @param tableName as a byte []
* @return the tableDescriptor
* @throws TableNotFoundException
* @throws IOException if a remote or network exception occurs
*/
public HTableDescriptor getTableDescriptor(final TableName tableName)
throws TableNotFoundException, IOException {
return this.connection.getHTableDescriptor(tableName);
}

public HTableDescriptor getTableDescriptor(final byte[] tableName)
throws TableNotFoundException, IOException {
return getTableDescriptor(TableName.valueOf(tableName));
}

private long getPauseTime(int tries) {
int triesCount = tries;
if (triesCount >= HConstants.RETRY_BACKOFF.length) {
triesCount = HConstants.RETRY_BACKOFF.length - 1;
}
return this.pause * HConstants.RETRY_BACKOFF[triesCount];
}

/**
* Creates a new table.
* Synchronous operation.
*
* @param desc table descriptor for table
*
* @throws IllegalArgumentException if the table name is reserved
* @throws MasterNotRunningException if master is not running
* @throws TableExistsException if table already exists (If concurrent
* threads, the table may have been created between test-for-existence
* and attempt-at-creation).
* @throws IOException if a remote or network exception occurs
*/
public void createTable(HTableDescriptor desc)
throws IOException {
createTable(desc, null);
}

/**
* Creates a new table with the specified number of regions.  The start key
* specified will become the end key of the first region of the table, and
* the end key specified will become the start key of the last region of the
* table (the first region has a null start key and the last region has a
* null end key).
*
* BigInteger math will be used to divide the key range specified into
* enough segments to make the required number of total regions.
*
* Synchronous operation.
*
* @param desc table descriptor for table
* @param startKey beginning of key range
* @param endKey end of key range
* @param numRegions the total number of regions to create
*
* @throws IllegalArgumentException if the table name is reserved
* @throws MasterNotRunningException if master is not running
* @throws org.apache.hadoop.hbase.TableExistsException if table already exists (If concurrent
* threads, the table may have been created between test-for-existence
* and attempt-at-creation).
* @throws IOException
*/
public void createTable(HTableDescriptor desc, byte [] startKey,
byte [] endKey, int numRegions)
throws IOException {
if(numRegions < 3) {
throw new IllegalArgumentException("Must create at least three regions");
} else if(Bytes.compareTo(startKey, endKey) >= 0) {
throw new IllegalArgumentException("Start key must be smaller than end key");
}
if (numRegions == 3) {
createTable(desc, new byte[][]{startKey, endKey});
return;
}
byte [][] splitKeys = Bytes.split(startKey, endKey, numRegions - 3);
if(splitKeys == null || splitKeys.length != numRegions - 1) {
throw new IllegalArgumentException("Unable to split key range into enough regions");
}
createTable(desc, splitKeys);
}

/**
* Creates a new table with an initial set of empty regions defined by the
* specified split keys.  The total number of regions created will be the
* number of split keys plus one. Synchronous operation.
* Note : Avoid passing empty split key.
*
* @param desc table descriptor for table
* @param splitKeys array of split keys for the initial regions of the table
*
* @throws IllegalArgumentException if the table name is reserved, if the split keys
* are repeated and if the split key has empty byte array.
* @throws MasterNotRunningException if master is not running
* @throws org.apache.hadoop.hbase.TableExistsException if table already exists (If concurrent
* threads, the table may have been created between test-for-existence
* and attempt-at-creation).
* @throws IOException
*/
public void createTable(final HTableDescriptor desc, byte [][] splitKeys)
throws IOException {
try {
createTableAsync(desc, splitKeys);
} catch (SocketTimeoutException ste) {
LOG.warn("Creating " + desc.getTableName() + " took too long", ste);
}
int numRegs = splitKeys == null ? 1 : splitKeys.length + 1;
int prevRegCount = 0;
boolean doneWithMetaScan = false;
for (int tries = 0; tries < this.numRetries * this.retryLongerMultiplier;
++tries) {
if (!doneWithMetaScan) {
// Wait for new table to come on-line
final AtomicInteger actualRegCount = new AtomicInteger(0);
MetaScannerVisitor visitor = new MetaScannerVisitorBase() {
@Override
public boolean processRow(Result rowResult) throws IOException {
HRegionInfo info = HRegionInfo.getHRegionInfo(rowResult);
if (info == null) {
LOG.warn("No serialized HRegionInfo in " + rowResult);
return true;
}
if (!info.getTableName().equals(desc.getTableName())) {
return false;
}
ServerName serverName = HRegionInfo.getServerName(rowResult);
// Make sure that regions are assigned to server
if (!(info.isOffline() || info.isSplit()) && serverName != null
&& serverName.getHostAndPort() != null) {
actualRegCount.incrementAndGet();
}
return true;
}
};
MetaScanner.metaScan(conf, connection, visitor, desc.getTableName());
if (actualRegCount.get() != numRegs) {
if (tries == this.numRetries * this.retryLongerMultiplier - 1) {
throw new RegionOfflineException("Only " + actualRegCount.get() +
" of " + numRegs + " regions are online; retries exhausted.");
}
try { // Sleep
Thread.sleep(getPauseTime(tries));
} catch (InterruptedException e) {
throw new InterruptedIOException("Interrupted when opening" +
" regions; " + actualRegCount.get() + " of " + numRegs +
" regions processed so far");
}
if (actualRegCount.get() > prevRegCount) { // Making progress
prevRegCount = actualRegCount.get();
tries = -1;
}
} else {
doneWithMetaScan = true;
tries = -1;
}
} else if (isTableEnabled(desc.getTableName())) {
return;
} else {
try { // Sleep
Thread.sleep(getPauseTime(tries));
} catch (InterruptedException e) {
throw new InterruptedIOException("Interrupted when waiting" +
" for table to be enabled; meta scan was done");
}
}
}
throw new TableNotEnabledException(
"Retries exhausted while still waiting for table: "
+ desc.getTableName() + " to be enabled");
}

/**
* Creates a new table but does not block and wait for it to come online.
* Asynchronous operation.  To check if the table exists, use
* {@link #isTableAvailable} -- it is not safe to create an HTable
* instance to this table before it is available.
* Note : Avoid passing empty split key.
* @param desc table descriptor for table
*
* @throws IllegalArgumentException Bad table name, if the split keys
* are repeated and if the split key has empty byte array.
* @throws MasterNotRunningException if master is not running
* @throws org.apache.hadoop.hbase.TableExistsException if table already exists (If concurrent
* threads, the table may have been created between test-for-existence
* and attempt-at-creation).
* @throws IOException
*/
public void createTableAsync(
final HTableDescriptor desc, final byte [][] splitKeys)
throws IOException {
if(desc.getTableName() == null) {
throw new IllegalArgumentException("TableName cannot be null");
}
if(splitKeys != null && splitKeys.length > 0) {
Arrays.sort(splitKeys, Bytes.BYTES_COMPARATOR);
// Verify there are no duplicate split keys
byte [] lastKey = null;
for(byte [] splitKey : splitKeys) {
if (Bytes.compareTo(splitKey, HConstants.EMPTY_BYTE_ARRAY) == 0) {
throw new IllegalArgumentException(
"Empty split key must not be passed in the split keys.");
}
if(lastKey != null && Bytes.equals(splitKey, lastKey)) {
throw new IllegalArgumentException("All split keys must be unique, " +
"found duplicate: " + Bytes.toStringBinary(splitKey) +
", " + Bytes.toStringBinary(lastKey));
}
lastKey = splitKey;
}
}

executeCallable(new MasterAdminCallable<Void>(getConnection()) {
@Override
public Void call() throws ServiceException {
CreateTableRequest request = RequestConverter.buildCreateTableRequest(desc, splitKeys);
masterAdmin.createTable(null, request);
return null;
}
});
}

public void deleteTable(final String tableName) throws IOException {
deleteTable(TableName.valueOf(tableName));
}

public void deleteTable(final byte[] tableName) throws IOException {
deleteTable(TableName.valueOf(tableName));
}

/**
* Deletes a table.
* Synchronous operation.
*
* @param tableName name of table to delete
* @throws IOException if a remote or network exception occurs
*/
public void deleteTable(final TableName tableName) throws IOException {
HRegionLocation firstMetaServer = getFirstMetaServerForTable(tableName);
boolean tableExists = true;

executeCallable(new MasterAdminCallable<Void>(getConnection()) {
@Override
public Void call() throws ServiceException {
DeleteTableRequest req = RequestConverter.buildDeleteTableRequest(tableName);
masterAdmin.deleteTable(null,req);
return null;
}
});

// Wait until all regions deleted
for (int tries = 0; tries < (this.numRetries * this.retryLongerMultiplier); tries++) {
try {

Scan scan = MetaReader.getScanForTableName(tableName);
scan.addColumn(HConstants.CATALOG_FAMILY, HConstants.REGIONINFO_QUALIFIER);
ScanRequest request = RequestConverter.buildScanRequest(
firstMetaServer.getRegionInfo().getRegionName(), scan, 1, true);
Result[] values = null;
// Get a batch at a time.
ClientService.BlockingInterface server = connection.getClient(firstMetaServer
.getServerName());
PayloadCarryingRpcController controller = new PayloadCarryingRpcController();
try {
ScanResponse response = server.scan(controller, request);
values = ResponseConverter.getResults(controller.cellScanner(), response);
} catch (ServiceException se) {
throw ProtobufUtil.getRemoteException(se);
}

// let us wait until .META. table is updated and
// HMaster removes the table from its HTableDescriptors
if (values == null || values.length == 0) {
tableExists = false;
GetTableDescriptorsResponse htds;
MasterMonitorKeepAliveConnection master = connection.getKeepAliveMasterMonitorService();
try {
GetTableDescriptorsRequest req =
RequestConverter.buildGetTableDescriptorsRequest(tableName);
htds = master.getTableDescriptors(null, req);
} catch (ServiceException se) {
throw ProtobufUtil.getRemoteException(se);
} finally {
master.close();
}
tableExists = !htds.getTableSchemaList().isEmpty();
if (!tableExists) {
break;
}
}
} catch (IOException ex) {
if(tries == numRetries - 1) {           // no more tries left
if (ex instanceof RemoteException) {
throw ((RemoteException) ex).unwrapRemoteException();
} else {
throw ex;
}
}
}
try {
Thread.sleep(getPauseTime(tries));
} catch (InterruptedException e) {
// continue
}
}

if (tableExists) {
throw new IOException("Retries exhausted, it took too long to wait"+
" for the table " + tableName + " to be deleted.");
}
// Delete cached information to prevent clients from using old locations
this.connection.clearRegionCache(tableName);
LOG.info("Deleted " + tableName);
}

/**
* Deletes tables matching the passed in pattern and wait on completion.
*
* Warning: Use this method carefully, there is no prompting and the effect is
* immediate. Consider using {@link #listTables(java.lang.String)} and
* {@link #deleteTable(byte[])}
*
* @param regex The regular expression to match table names against
* @return Table descriptors for tables that couldn't be deleted
* @throws IOException
* @see #deleteTables(java.util.regex.Pattern)
* @see #deleteTable(java.lang.String)
*/
public HTableDescriptor[] deleteTables(String regex) throws IOException {
return deleteTables(Pattern.compile(regex));
}

/**
* Delete tables matching the passed in pattern and wait on completion.
*
* Warning: Use this method carefully, there is no prompting and the effect is
* immediate. Consider using {@link #listTables(java.util.regex.Pattern) } and
* {@link #deleteTable(byte[])}
*
* @param pattern The pattern to match table names against
* @return Table descriptors for tables that couldn't be deleted
* @throws IOException
*/
public HTableDescriptor[] deleteTables(Pattern pattern) throws IOException {
List<HTableDescriptor> failed = new LinkedList<HTableDescriptor>();
for (HTableDescriptor table : listTables(pattern)) {
try {
deleteTable(table.getTableName());
} catch (IOException ex) {
LOG.info("Failed to delete table " + table.getTableName(), ex);
failed.add(table);
}
}
return failed.toArray(new HTableDescriptor[failed.size()]);
}


/**
* Enable a table.  May timeout.  Use {@link #enableTableAsync(byte[])}
* and {@link #isTableEnabled(byte[])} instead.
* The table has to be in disabled state for it to be enabled.
* @param tableName name of the table
* @throws IOException if a remote or network exception occurs
* There could be couple types of IOException
* TableNotFoundException means the table doesn't exist.
* TableNotDisabledException means the table isn't in disabled state.
* @see #isTableEnabled(byte[])
* @see #disableTable(byte[])
* @see #enableTableAsync(byte[])
*/
public void enableTable(final TableName tableName)
throws IOException {
enableTableAsync(tableName);

// Wait until all regions are enabled
waitUntilTableIsEnabled(tableName);

LOG.info("Enabled table " + tableName);
}

public void enableTable(final byte[] tableName)
throws IOException {
enableTable(TableName.valueOf(tableName));
}

public void enableTable(final String tableName)
throws IOException {
enableTable(TableName.valueOf(tableName));
}

/**
* Wait for the table to be enabled and available
* If enabling the table exceeds the retry period, an exception is thrown.
* @param tableName name of the table
* @throws IOException if a remote or network exception occurs or
*    table is not enabled after the retries period.
*/
private void waitUntilTableIsEnabled(final TableName tableName) throws IOException {
boolean enabled = false;
long start = EnvironmentEdgeManager.currentTimeMillis();
for (int tries = 0; tries < (this.numRetries * this.retryLongerMultiplier); tries++) {
enabled = isTableEnabled(tableName) && isTableAvailable(tableName);
if (enabled) {
break;
}
long sleep = getPauseTime(tries);
if (LOG.isDebugEnabled()) {
LOG.debug("Sleeping= " + sleep + "ms, waiting for all regions to be " +
"enabled in " + tableName);
}
try {
Thread.sleep(sleep);
} catch (InterruptedException e) {
Thread.currentThread().interrupt();
// Do this conversion rather than let it out because do not want to
// change the method signature.
throw new IOException("Interrupted", e);
}
}
if (!enabled) {
long msec = EnvironmentEdgeManager.currentTimeMillis() - start;
throw new IOException("Table '" + tableName +
"' not yet enabled, after " + msec + "ms.");
}
}

/**
* Brings a table on-line (enables it).  Method returns immediately though
* enable of table may take some time to complete, especially if the table
* is large (All regions are opened as part of enabling process).  Check
* {@link #isTableEnabled(byte[])} to learn when table is fully online.  If
* table is taking too long to online, check server logs.
* @param tableName
* @throws IOException
* @since 0.90.0
*/
public void enableTableAsync(final TableName tableName)
throws IOException {
TableName.isLegalFullyQualifiedTableName(tableName.getName());
executeCallable(new MasterAdminCallable<Void>(getConnection()) {
@Override
public Void call() throws ServiceException {
LOG.info("Started enable of " + tableName);
EnableTableRequest req = RequestConverter.buildEnableTableRequest(tableName);
masterAdmin.enableTable(null,req);
return null;
}
});
}

public void enableTableAsync(final byte[] tableName)
throws IOException {
enableTable(TableName.valueOf(tableName));
}

public void enableTableAsync(final String tableName)
throws IOException {
enableTableAsync(TableName.valueOf(tableName));
}

/**
* Enable tables matching the passed in pattern and wait on completion.
*
* Warning: Use this method carefully, there is no prompting and the effect is
* immediate. Consider using {@link #listTables(java.lang.String)} and
* {@link #enableTable(byte[])}
*
* @param regex The regular expression to match table names against
* @throws IOException
* @see #enableTables(java.util.regex.Pattern)
* @see #enableTable(java.lang.String)
*/
public HTableDescriptor[] enableTables(String regex) throws IOException {
return enableTables(Pattern.compile(regex));
}

/**
* Enable tables matching the passed in pattern and wait on completion.
*
* Warning: Use this method carefully, there is no prompting and the effect is
* immediate. Consider using {@link #listTables(java.util.regex.Pattern) } and
* {@link #enableTable(byte[])}
*
* @param pattern The pattern to match table names against
* @throws IOException
*/
public HTableDescriptor[] enableTables(Pattern pattern) throws IOException {
List<HTableDescriptor> failed = new LinkedList<HTableDescriptor>();
for (HTableDescriptor table : listTables(pattern)) {
if (isTableDisabled(table.getTableName())) {
try {
enableTable(table.getTableName());
} catch (IOException ex) {
LOG.info("Failed to enable table " + table.getTableName(), ex);
failed.add(table);
}
}
}
return failed.toArray(new HTableDescriptor[failed.size()]);
}

/**
* Starts the disable of a table.  If it is being served, the master
* will tell the servers to stop serving it.  This method returns immediately.
* The disable of a table can take some time if the table is large (all
* regions are closed as part of table disable operation).
* Call {@link #isTableDisabled(byte[])} to check for when disable completes.
* If table is taking too long to online, check server logs.
* @param tableName name of table
* @throws IOException if a remote or network exception occurs
* @see #isTableDisabled(byte[])
* @see #isTableEnabled(byte[])
* @since 0.90.0
*/
public void disableTableAsync(final TableName tableName) throws IOException {
TableName.isLegalFullyQualifiedTableName(tableName.getName());
executeCallable(new MasterAdminCallable<Void>(getConnection()) {
@Override
public Void call() throws ServiceException {
LOG.info("Started disable of " + tableName);
DisableTableRequest req = RequestConverter.buildDisableTableRequest(tableName);
masterAdmin.disableTable(null,req);
return null;
}
});
}

public void disableTableAsync(final byte[] tableName) throws IOException {
disableTableAsync(TableName.valueOf(tableName));
}

public void disableTableAsync(final String tableName) throws IOException {
disableTableAsync(TableName.valueOf(tableName));
}

/**
* Disable table and wait on completion.  May timeout eventually.  Use
* {@link #disableTableAsync(byte[])} and {@link #isTableDisabled(String)}
* instead.
* The table has to be in enabled state for it to be disabled.
* @param tableName
* @throws IOException
* There could be couple types of IOException
* TableNotFoundException means the table doesn't exist.
* TableNotEnabledException means the table isn't in enabled state.
*/
public void disableTable(final TableName tableName)
throws IOException {
disableTableAsync(tableName);
// Wait until table is disabled
boolean disabled = false;
for (int tries = 0; tries < (this.numRetries * this.retryLongerMultiplier); tries++) {
disabled = isTableDisabled(tableName);
if (disabled) {
break;
}
long sleep = getPauseTime(tries);
if (LOG.isDebugEnabled()) {
LOG.debug("Sleeping= " + sleep + "ms, waiting for all regions to be " +
"disabled in " + tableName);
}
try {
Thread.sleep(sleep);
} catch (InterruptedException e) {
// Do this conversion rather than let it out because do not want to
// change the method signature.
Thread.currentThread().interrupt();
throw new IOException("Interrupted", e);
}
}
if (!disabled) {
throw new RegionException("Retries exhausted, it took too long to wait"+
" for the table " + tableName + " to be disabled.");
}
LOG.info("Disabled " + tableName);
}

public void disableTable(final byte[] tableName)
throws IOException {
disableTable(TableName.valueOf(tableName));
}

public void disableTable(final String tableName)
throws IOException {
disableTable(TableName.valueOf(tableName));
}

/**
* Disable tables matching the passed in pattern and wait on completion.
*
* Warning: Use this method carefully, there is no prompting and the effect is
* immediate. Consider using {@link #listTables(java.lang.String)} and
* {@link #disableTable(byte[])}
*
* @param regex The regular expression to match table names against
* @return Table descriptors for tables that couldn't be disabled
* @throws IOException
* @see #disableTables(java.util.regex.Pattern)
* @see #disableTable(java.lang.String)
*/
public HTableDescriptor[] disableTables(String regex) throws IOException {
return disableTables(Pattern.compile(regex));
}

/**
* Disable tables matching the passed in pattern and wait on completion.
*
* Warning: Use this method carefully, there is no prompting and the effect is
* immediate. Consider using {@link #listTables(java.util.regex.Pattern) } and
* {@link #disableTable(byte[])}
*
* @param pattern The pattern to match table names against
* @return Table descriptors for tables that couldn't be disabled
* @throws IOException
*/
public HTableDescriptor[] disableTables(Pattern pattern) throws IOException {
List<HTableDescriptor> failed = new LinkedList<HTableDescriptor>();
for (HTableDescriptor table : listTables(pattern)) {
if (isTableEnabled(table.getTableName())) {
try {
disableTable(table.getTableName());
} catch (IOException ex) {
LOG.info("Failed to disable table " + table.getTableName(), ex);
failed.add(table);
}
}
}
return failed.toArray(new HTableDescriptor[failed.size()]);
}

/**
* @param tableName name of table to check
* @return true if table is on-line
* @throws IOException if a remote or network exception occurs
*/
public boolean isTableEnabled(TableName tableName) throws IOException {
return connection.isTableEnabled(tableName);
}

public boolean isTableEnabled(byte[] tableName) throws IOException {
return isTableEnabled(TableName.valueOf(tableName));
}

public boolean isTableEnabled(String tableName) throws IOException {
return isTableEnabled(TableName.valueOf(tableName));
}



/**
* @param tableName name of table to check
* @return true if table is off-line
* @throws IOException if a remote or network exception occurs
*/
public boolean isTableDisabled(TableName tableName) throws IOException {
return connection.isTableDisabled(tableName);
}

public boolean isTableDisabled(byte[] tableName) throws IOException {
return isTableDisabled(TableName.valueOf(tableName));
}

public boolean isTableDisabled(String tableName) throws IOException {
return isTableDisabled(TableName.valueOf(tableName));
}

/**
* @param tableName name of table to check
* @return true if all regions of the table are available
* @throws IOException if a remote or network exception occurs
*/
public boolean isTableAvailable(TableName tableName) throws IOException {
return connection.isTableAvailable(tableName);
}

public boolean isTableAvailable(byte[] tableName) throws IOException {
return isTableAvailable(TableName.valueOf(tableName));
}

public boolean isTableAvailable(String tableName) throws IOException {
return isTableAvailable(TableName.valueOf(tableName));
}

/**
* Use this api to check if the table has been created with the specified number of
* splitkeys which was used while creating the given table.
* Note : If this api is used after a table's region gets splitted, the api may return
* false.
* @param tableName
*          name of table to check
* @param splitKeys
*          keys to check if the table has been created with all split keys
* @throws IOException
*           if a remote or network excpetion occurs
*/
public boolean isTableAvailable(TableName tableName,
byte[][] splitKeys) throws IOException {
return connection.isTableAvailable(tableName, splitKeys);
}

public boolean isTableAvailable(byte[] tableName,
byte[][] splitKeys) throws IOException {
return isTableAvailable(TableName.valueOf(tableName), splitKeys);
}

public boolean isTableAvailable(String tableName,
byte[][] splitKeys) throws IOException {
return isTableAvailable(TableName.valueOf(tableName), splitKeys);
}

/**
* Get the status of alter command - indicates how many regions have received
* the updated schema Asynchronous operation.
*
* @param tableName TableName instance
* @return Pair indicating the number of regions updated Pair.getFirst() is the
*         regions that are yet to be updated Pair.getSecond() is the total number
*         of regions of the table
* @throws IOException
*           if a remote or network exception occurs
*/
public Pair<Integer, Integer> getAlterStatus(final TableName tableName)
throws IOException {
return executeCallable(new MasterMonitorCallable<Pair<Integer, Integer>>(getConnection()) {
@Override
public Pair<Integer, Integer> call() throws ServiceException {
GetSchemaAlterStatusRequest req = RequestConverter
.buildGetSchemaAlterStatusRequest(tableName);
GetSchemaAlterStatusResponse ret = masterMonitor.getSchemaAlterStatus(null, req);
Pair<Integer, Integer> pair = new Pair<Integer, Integer>(Integer.valueOf(ret
.getYetToUpdateRegions()), Integer.valueOf(ret.getTotalRegions()));
return pair;
}
});
}

/**
* Get the status of alter command - indicates how many regions have received
* the updated schema Asynchronous operation.
*
* @param tableName
*          name of the table to get the status of
* @return Pair indicating the number of regions updated Pair.getFirst() is the
*         regions that are yet to be updated Pair.getSecond() is the total number
*         of regions of the table
* @throws IOException
*           if a remote or network exception occurs
*/
public Pair<Integer, Integer> getAlterStatus(final byte[] tableName)
throws IOException {
return getAlterStatus(TableName.valueOf(tableName));
}

/**
* Add a column to an existing table.
* Asynchronous operation.
*
* @param tableName name of the table to add column to
* @param column column descriptor of column to be added
* @throws IOException if a remote or network exception occurs
*/
public void addColumn(final byte[] tableName, HColumnDescriptor column)
throws IOException {
addColumn(TableName.valueOf(tableName), column);
}


/**
* Add a column to an existing table.
* Asynchronous operation.
*
* @param tableName name of the table to add column to
* @param column column descriptor of column to be added
* @throws IOException if a remote or network exception occurs
*/
public void addColumn(final String tableName, HColumnDescriptor column)
throws IOException {
addColumn(TableName.valueOf(tableName), column);
}

/**
* Add a column to an existing table.
* Asynchronous operation.
*
* @param tableName name of the table to add column to
* @param column column descriptor of column to be added
* @throws IOException if a remote or network exception occurs
*/
public void addColumn(final TableName tableName, final HColumnDescriptor column)
throws IOException {
executeCallable(new MasterAdminCallable<Void>(getConnection()) {
@Override
public Void call() throws ServiceException {
AddColumnRequest req = RequestConverter.buildAddColumnRequest(tableName, column);
masterAdmin.addColumn(null,req);
return null;
}
});
}

/**
* Delete a column from a table.
* Asynchronous operation.
*
* @param tableName name of table
* @param columnName name of column to be deleted
* @throws IOException if a remote or network exception occurs
*/
public void deleteColumn(final byte[] tableName, final String columnName)
throws IOException {
deleteColumn(TableName.valueOf(tableName), Bytes.toBytes(columnName));
}

/**
* Delete a column from a table.
* Asynchronous operation.
*
* @param tableName name of table
* @param columnName name of column to be deleted
* @throws IOException if a remote or network exception occurs
*/
public void deleteColumn(final String tableName, final String columnName)
throws IOException {
deleteColumn(TableName.valueOf(tableName), Bytes.toBytes(columnName));
}

/**
* Delete a column from a table.
* Asynchronous operation.
*
* @param tableName name of table
* @param columnName name of column to be deleted
* @throws IOException if a remote or network exception occurs
*/
public void deleteColumn(final TableName tableName, final byte [] columnName)
throws IOException {
executeCallable(new MasterAdminCallable<Void>(getConnection()) {
@Override
public Void call() throws ServiceException {
DeleteColumnRequest req = RequestConverter.buildDeleteColumnRequest(tableName, columnName);
masterAdmin.deleteColumn(null,req);
return null;
}
});
}

/**
* Modify an existing column family on a table.
* Asynchronous operation.
*
* @param tableName name of table
* @param descriptor new column descriptor to use
* @throws IOException if a remote or network exception occurs
*/
public void modifyColumn(final String tableName, HColumnDescriptor descriptor)
throws IOException {
modifyColumn(TableName.valueOf(tableName), descriptor);
}

/**
* Modify an existing column family on a table.
* Asynchronous operation.
*
* @param tableName name of table
* @param descriptor new column descriptor to use
* @throws IOException if a remote or network exception occurs
*/
public void modifyColumn(final byte[] tableName, HColumnDescriptor descriptor)
throws IOException {
modifyColumn(TableName.valueOf(tableName), descriptor);
}



/**
* Modify an existing column family on a table.
* Asynchronous operation.
*
* @param tableName name of table
* @param descriptor new column descriptor to use
* @throws IOException if a remote or network exception occurs
*/
public void modifyColumn(final TableName tableName, final HColumnDescriptor descriptor)
throws IOException {
executeCallable(new MasterAdminCallable<Void>(getConnection()) {
@Override
public Void call() throws ServiceException {
ModifyColumnRequest req = RequestConverter.buildModifyColumnRequest(tableName, descriptor);
masterAdmin.modifyColumn(null,req);
return null;
}
});
}

/**
* Close a region. For expert-admins.  Runs close on the regionserver.  The
* master will not be informed of the close.
* @param regionname region name to close
* @param serverName If supplied, we'll use this location rather than
* the one currently in <code>.META.</code>
* @throws IOException if a remote or network exception occurs
*/
public void closeRegion(final String regionname, final String serverName)
throws IOException {
closeRegion(Bytes.toBytes(regionname), serverName);
}

/**
* Close a region.  For expert-admins  Runs close on the regionserver.  The
* master will not be informed of the close.
* @param regionname region name to close
* @param serverName The servername of the regionserver.  If passed null we
* will use servername found in the .META. table. A server name
* is made of host, port and startcode.  Here is an example:
* <code> host187.example.com,60020,1289493121758</code>
* @throws IOException if a remote or network exception occurs
*/
public void closeRegion(final byte [] regionname, final String serverName)
throws IOException {
CatalogTracker ct = getCatalogTracker();
try {
if (serverName != null) {
Pair<HRegionInfo, ServerName> pair = MetaReader.getRegion(ct, regionname);
if (pair == null || pair.getFirst() == null) {
throw new UnknownRegionException(Bytes.toStringBinary(regionname));
} else {
closeRegion(new ServerName(serverName), pair.getFirst());
}
} else {
Pair<HRegionInfo, ServerName> pair = MetaReader.getRegion(ct, regionname);
if (pair == null) {
throw new UnknownRegionException(Bytes.toStringBinary(regionname));
} else if (pair.getSecond() == null) {
throw new NoServerForRegionException(Bytes.toStringBinary(regionname));
} else {
closeRegion(pair.getSecond(), pair.getFirst());
}
}
} finally {
cleanupCatalogTracker(ct);
}
}

/**
* For expert-admins. Runs close on the regionserver. Closes a region based on
* the encoded region name. The region server name is mandatory. If the
* servername is provided then based on the online regions in the specified
* regionserver the specified region will be closed. The master will not be
* informed of the close. Note that the regionname is the encoded regionname.
*
* @param encodedRegionName
*          The encoded region name; i.e. the hash that makes up the region
*          name suffix: e.g. if regionname is
*          <code>TestTable,0094429456,1289497600452.527db22f95c8a9e0116f0cc13c680396.</code>
*          , then the encoded region name is:
*          <code>527db22f95c8a9e0116f0cc13c680396</code>.
* @param serverName
*          The servername of the regionserver. A server name is made of host,
*          port and startcode. This is mandatory. Here is an example:
*          <code> host187.example.com,60020,1289493121758</code>
* @return true if the region was closed, false if not.
* @throws IOException
*           if a remote or network exception occurs
*/
public boolean closeRegionWithEncodedRegionName(final String encodedRegionName,
final String serverName) throws IOException {
if (null == serverName || ("").equals(serverName.trim())) {
throw new IllegalArgumentException(
"The servername cannot be null or empty.");
}
ServerName sn = new ServerName(serverName);
AdminService.BlockingInterface admin = this.connection.getAdmin(sn);
// Close the region without updating zk state.
CloseRegionRequest request =
RequestConverter.buildCloseRegionRequest(encodedRegionName, false);
try {
CloseRegionResponse response = admin.closeRegion(null, request);
boolean isRegionClosed = response.getClosed();
if (false == isRegionClosed) {
LOG.error("Not able to close the region " + encodedRegionName + ".");
}
return isRegionClosed;
} catch (ServiceException se) {
throw ProtobufUtil.getRemoteException(se);
}
}

/**
* Close a region.  For expert-admins  Runs close on the regionserver.  The
* master will not be informed of the close.
* @param sn
* @param hri
* @throws IOException
*/
public void closeRegion(final ServerName sn, final HRegionInfo hri)
throws IOException {
AdminService.BlockingInterface admin = this.connection.getAdmin(sn);
// Close the region without updating zk state.
ProtobufUtil.closeRegion(admin, hri.getRegionName(), false);
}

/**
* Get all the online regions on a region server.
*/
public List<HRegionInfo> getOnlineRegions(
final ServerName sn) throws IOException {
AdminService.BlockingInterface admin = this.connection.getAdmin(sn);
return ProtobufUtil.getOnlineRegions(admin);
}

/**
* Flush a table or an individual region.
* Synchronous operation.
*
* @param tableNameOrRegionName table or region to flush
* @throws IOException if a remote or network exception occurs
* @throws InterruptedException
*/
public void flush(final String tableNameOrRegionName)
throws IOException, InterruptedException {
flush(Bytes.toBytes(tableNameOrRegionName));
}

/**
* Flush a table or an individual region.
* Synchronous operation.
*
* @param tableNameOrRegionName table or region to flush
* @throws IOException if a remote or network exception occurs
* @throws InterruptedException
*/
public void flush(final byte[] tableNameOrRegionName)
throws IOException, InterruptedException {
CatalogTracker ct = getCatalogTracker();
try {
Pair<HRegionInfo, ServerName> regionServerPair
= getRegion(tableNameOrRegionName, ct);
if (regionServerPair != null) {
if (regionServerPair.getSecond() == null) {
throw new NoServerForRegionException(Bytes.toStringBinary(tableNameOrRegionName));
} else {
flush(regionServerPair.getSecond(), regionServerPair.getFirst());
}
} else {
final TableName tableName = checkTableExists(
TableName.valueOf(tableNameOrRegionName), ct);
List<Pair<HRegionInfo, ServerName>> pairs =
MetaReader.getTableRegionsAndLocations(ct,
tableName);
for (Pair<HRegionInfo, ServerName> pair: pairs) {
if (pair.getFirst().isOffline()) continue;
if (pair.getSecond() == null) continue;
try {
flush(pair.getSecond(), pair.getFirst());
} catch (NotServingRegionException e) {
if (LOG.isDebugEnabled()) {
LOG.debug("Trying to flush " + pair.getFirst() + ": " +
StringUtils.stringifyException(e));
}
}
}
}
} finally {
cleanupCatalogTracker(ct);
}
}

private void flush(final ServerName sn, final HRegionInfo hri)
throws IOException {
AdminService.BlockingInterface admin = this.connection.getAdmin(sn);
FlushRegionRequest request =
RequestConverter.buildFlushRegionRequest(hri.getRegionName());
try {
admin.flushRegion(null, request);
} catch (ServiceException se) {
throw ProtobufUtil.getRemoteException(se);
}
}

/**
* Compact a table or an individual region.
* Asynchronous operation.
*
* @param tableNameOrRegionName table or region to compact
* @throws IOException if a remote or network exception occurs
* @throws InterruptedException
*/
public void compact(final String tableNameOrRegionName)
throws IOException, InterruptedException {
compact(Bytes.toBytes(tableNameOrRegionName));
}

/**
* Compact a table or an individual region.
* Asynchronous operation.
*
* @param tableNameOrRegionName table or region to compact
* @throws IOException if a remote or network exception occurs
* @throws InterruptedException
*/
public void compact(final byte[] tableNameOrRegionName)
throws IOException, InterruptedException {
compact(tableNameOrRegionName, null, false);
}

/**
* Compact a column family within a table or region.
* Asynchronous operation.
*
* @param tableOrRegionName table or region to compact
* @param columnFamily column family within a table or region
* @throws IOException if a remote or network exception occurs
* @throws InterruptedException
*/
public void compact(String tableOrRegionName, String columnFamily)
throws IOException,  InterruptedException {
compact(Bytes.toBytes(tableOrRegionName), Bytes.toBytes(columnFamily));
}

/**
* Compact a column family within a table or region.
* Asynchronous operation.
*
* @param tableNameOrRegionName table or region to compact
* @param columnFamily column family within a table or region
* @throws IOException if a remote or network exception occurs
* @throws InterruptedException
*/
public void compact(final byte[] tableNameOrRegionName, final byte[] columnFamily)
throws IOException, InterruptedException {
compact(tableNameOrRegionName, columnFamily, false);
}

/**
* Major compact a table or an individual region.
* Asynchronous operation.
*
* @param tableNameOrRegionName table or region to major compact
* @throws IOException if a remote or network exception occurs
* @throws InterruptedException
*/
public void majorCompact(final String tableNameOrRegionName)
throws IOException, InterruptedException {
majorCompact(Bytes.toBytes(tableNameOrRegionName));
}

/**
* Major compact a table or an individual region.
* Asynchronous operation.
*
* @param tableNameOrRegionName table or region to major compact
* @throws IOException if a remote or network exception occurs
* @throws InterruptedException
*/
public void majorCompact(final byte[] tableNameOrRegionName)
throws IOException, InterruptedException {
compact(tableNameOrRegionName, null, true);
}

/**
* Major compact a column family within a table or region.
* Asynchronous operation.
*
* @param tableNameOrRegionName table or region to major compact
* @param columnFamily column family within a table or region
* @throws IOException if a remote or network exception occurs
* @throws InterruptedException
*/
public void majorCompact(final String tableNameOrRegionName,
final String columnFamily) throws IOException, InterruptedException {
majorCompact(Bytes.toBytes(tableNameOrRegionName),
Bytes.toBytes(columnFamily));
}

/**
* Major compact a column family within a table or region.
* Asynchronous operation.
*
* @param tableNameOrRegionName table or region to major compact
* @param columnFamily column family within a table or region
* @throws IOException if a remote or network exception occurs
* @throws InterruptedException
*/
public void majorCompact(final byte[] tableNameOrRegionName,
final byte[] columnFamily) throws IOException, InterruptedException {
compact(tableNameOrRegionName, columnFamily, true);
}

/**
* Compact a table or an individual region.
* Asynchronous operation.
*
* @param tableNameOrRegionName table or region to compact
* @param columnFamily column family within a table or region
* @param major True if we are to do a major compaction.
* @throws IOException if a remote or network exception occurs
* @throws InterruptedException
*/
private void compact(final byte[] tableNameOrRegionName,
final byte[] columnFamily,final boolean major)
throws IOException, InterruptedException {
CatalogTracker ct = getCatalogTracker();
try {
Pair<HRegionInfo, ServerName> regionServerPair
= getRegion(tableNameOrRegionName, ct);
if (regionServerPair != null) {
if (regionServerPair.getSecond() == null) {
throw new NoServerForRegionException(Bytes.toStringBinary(tableNameOrRegionName));
} else {
compact(regionServerPair.getSecond(), regionServerPair.getFirst(), major, columnFamily);
}
} else {
final TableName tableName =
checkTableExists(TableName.valueOf(tableNameOrRegionName), ct);
List<Pair<HRegionInfo, ServerName>> pairs =
MetaReader.getTableRegionsAndLocations(ct,
tableName);
for (Pair<HRegionInfo, ServerName> pair: pairs) {
if (pair.getFirst().isOffline()) continue;
if (pair.getSecond() == null) continue;
try {
compact(pair.getSecond(), pair.getFirst(), major, columnFamily);
} catch (NotServingRegionException e) {
if (LOG.isDebugEnabled()) {
LOG.debug("Trying to" + (major ? " major" : "") + " compact " +
pair.getFirst() + ": " +
StringUtils.stringifyException(e));
}
}
}
}
} finally {
cleanupCatalogTracker(ct);
}
}

private void compact(final ServerName sn, final HRegionInfo hri,
final boolean major, final byte [] family)
throws IOException {
AdminService.BlockingInterface admin = this.connection.getAdmin(sn);
CompactRegionRequest request =
RequestConverter.buildCompactRegionRequest(hri.getRegionName(), major, family);
try {
admin.compactRegion(null, request);
} catch (ServiceException se) {
throw ProtobufUtil.getRemoteException(se);
}
}

/**
* Move the region <code>r</code> to <code>dest</code>.
* @param encodedRegionName The encoded region name; i.e. the hash that makes
* up the region name suffix: e.g. if regionname is
* <code>TestTable,0094429456,1289497600452.527db22f95c8a9e0116f0cc13c680396.</code>,
* then the encoded region name is: <code>527db22f95c8a9e0116f0cc13c680396</code>.
* @param destServerName The servername of the destination regionserver.  If
* passed the empty byte array we'll assign to a random server.  A server name
* is made of host, port and startcode.  Here is an example:
* <code> host187.example.com,60020,1289493121758</code>
* @throws UnknownRegionException Thrown if we can't find a region named
* <code>encodedRegionName</code>
* @throws ZooKeeperConnectionException
* @throws MasterNotRunningException
*/
public void move(final byte [] encodedRegionName, final byte [] destServerName)
throws HBaseIOException, MasterNotRunningException, ZooKeeperConnectionException {
MasterAdminKeepAliveConnection stub = connection.getKeepAliveMasterAdminService();
try {
MoveRegionRequest request =
RequestConverter.buildMoveRegionRequest(encodedRegionName, destServerName);
stub.moveRegion(null,request);
} catch (ServiceException se) {
IOException ioe = ProtobufUtil.getRemoteException(se);
if (ioe instanceof HBaseIOException) {
throw (HBaseIOException)ioe;
}
LOG.error("Unexpected exception: " + se + " from calling HMaster.moveRegion");
} catch (DeserializationException de) {
LOG.error("Could not parse destination server name: " + de);
} finally {
stub.close();
}
}

/**
* @param regionName
*          Region name to assign.
* @throws MasterNotRunningException
* @throws ZooKeeperConnectionException
* @throws IOException
*/
public void assign(final byte[] regionName) throws MasterNotRunningException,
ZooKeeperConnectionException, IOException {
executeCallable(new MasterAdminCallable<Void>(getConnection()) {
@Override
public Void call() throws ServiceException {
AssignRegionRequest request = RequestConverter.buildAssignRegionRequest(regionName);
masterAdmin.assignRegion(null,request);
return null;
}
});
}

/**
* Unassign a region from current hosting regionserver.  Region will then be
* assigned to a regionserver chosen at random.  Region could be reassigned
* back to the same server.  Use {@link #move(byte[], byte[])} if you want
* to control the region movement.
* @param regionName Region to unassign. Will clear any existing RegionPlan
* if one found.
* @param force If true, force unassign (Will remove region from
* regions-in-transition too if present. If results in double assignment
* use hbck -fix to resolve. To be used by experts).
* @throws MasterNotRunningException
* @throws ZooKeeperConnectionException
* @throws IOException
*/
public void unassign(final byte [] regionName, final boolean force)
throws MasterNotRunningException, ZooKeeperConnectionException, IOException {
executeCallable(new MasterAdminCallable<Void>(getConnection()) {
@Override
public Void call() throws ServiceException {
UnassignRegionRequest request =
RequestConverter.buildUnassignRegionRequest(regionName, force);
masterAdmin.unassignRegion(null,request);
return null;
}
});
}

/**
* Special method, only used by hbck.
*/
public void offline(final byte [] regionName)
throws IOException {
MasterAdminKeepAliveConnection master = connection.getKeepAliveMasterAdminService();
try {
master.offlineRegion(null,RequestConverter.buildOfflineRegionRequest(regionName));
} catch (ServiceException se) {
throw ProtobufUtil.getRemoteException(se);
} finally {
master.close();
}
}

/**
* Turn the load balancer on or off.
* @param on If true, enable balancer. If false, disable balancer.
* @param synchronous If true, it waits until current balance() call, if outstanding, to return.
* @return Previous balancer value
*/
public boolean setBalancerRunning(final boolean on, final boolean synchronous)
throws MasterNotRunningException, ZooKeeperConnectionException {
MasterAdminKeepAliveConnection stub = connection.getKeepAliveMasterAdminService();
try {
SetBalancerRunningRequest req =
RequestConverter.buildSetBalancerRunningRequest(on, synchronous);
return stub.setBalancerRunning(null, req).getPrevBalanceValue();
} catch (ServiceException se) {
IOException ioe = ProtobufUtil.getRemoteException(se);
if (ioe instanceof MasterNotRunningException) {
throw (MasterNotRunningException)ioe;
}
if (ioe instanceof ZooKeeperConnectionException) {
throw (ZooKeeperConnectionException)ioe;
}

// Throwing MasterNotRunningException even though not really valid in order to not
// break interface by adding additional exception type.
throw new MasterNotRunningException("Unexpected exception when calling balanceSwitch",se);
} finally {
stub.close();
}
}

/**
* Invoke the balancer.  Will run the balancer and if regions to move, it will
* go ahead and do the reassignments.  Can NOT run for various reasons.  Check
* logs.
* @return True if balancer ran, false otherwise.
*/
public boolean balancer()
throws MasterNotRunningException, ZooKeeperConnectionException, ServiceException {
MasterAdminKeepAliveConnection stub = connection.getKeepAliveMasterAdminService();
try {
return stub.balance(null,RequestConverter.buildBalanceRequest()).getBalancerRan();
} finally {
stub.close();
}
}

/**
* Enable/Disable the catalog janitor
* @param enable if true enables the catalog janitor
* @return the previous state
* @throws ServiceException
* @throws MasterNotRunningException
*/
public boolean enableCatalogJanitor(boolean enable)
throws ServiceException, MasterNotRunningException {
MasterAdminKeepAliveConnection stub = connection.getKeepAliveMasterAdminService();
try {
return stub.enableCatalogJanitor(null,
RequestConverter.buildEnableCatalogJanitorRequest(enable)).getPrevValue();
} finally {
stub.close();
}
}

/**
* Ask for a scan of the catalog table
* @return the number of entries cleaned
* @throws ServiceException
* @throws MasterNotRunningException
*/
public int runCatalogScan() throws ServiceException, MasterNotRunningException {
MasterAdminKeepAliveConnection stub = connection.getKeepAliveMasterAdminService();
try {
return stub.runCatalogScan(null,
RequestConverter.buildCatalogScanRequest()).getScanResult();
} finally {
stub.close();
}
}

/**
* Query on the catalog janitor state (Enabled/Disabled?)
* @throws ServiceException
* @throws org.apache.hadoop.hbase.MasterNotRunningException
*/
public boolean isCatalogJanitorEnabled() throws ServiceException, MasterNotRunningException {
MasterAdminKeepAliveConnection stub = connection.getKeepAliveMasterAdminService();
try {
return stub.isCatalogJanitorEnabled(null,
RequestConverter.buildIsCatalogJanitorEnabledRequest()).getValue();
} finally {
stub.close();
}
}

/**
* Merge two regions. Asynchronous operation.
* @param encodedNameOfRegionA encoded name of region a
* @param encodedNameOfRegionB encoded name of region b
* @param forcible true if do a compulsory merge, otherwise we will only merge
*          two adjacent regions
* @throws IOException
*/
public void mergeRegions(final byte[] encodedNameOfRegionA,
final byte[] encodedNameOfRegionB, final boolean forcible)
throws IOException {
MasterAdminKeepAliveConnection master = connection
.getKeepAliveMasterAdminService();
try {
DispatchMergingRegionsRequest request = RequestConverter
.buildDispatchMergingRegionsRequest(encodedNameOfRegionA,
encodedNameOfRegionB, forcible);
master.dispatchMergingRegions(null, request);
} catch (ServiceException se) {
IOException ioe = ProtobufUtil.getRemoteException(se);
if (ioe instanceof UnknownRegionException) {
throw (UnknownRegionException) ioe;
}
if (ioe instanceof MergeRegionException) {
throw (MergeRegionException) ioe;
}
LOG.error("Unexpected exception: " + se
+ " from calling HMaster.dispatchMergingRegions");
} catch (DeserializationException de) {
LOG.error("Could not parse destination server name: " + de);
} finally {
master.close();
}
}

/**
* Split a table or an individual region.
* Asynchronous operation.
*
* @param tableNameOrRegionName table or region to split
* @throws IOException if a remote or network exception occurs
* @throws InterruptedException
*/
public void split(final String tableNameOrRegionName)
throws IOException, InterruptedException {
split(Bytes.toBytes(tableNameOrRegionName));
}

/**
* Split a table or an individual region.  Implicitly finds an optimal split
* point.  Asynchronous operation.
*
* @param tableNameOrRegionName table to region to split
* @throws IOException if a remote or network exception occurs
* @throws InterruptedException
*/
public void split(final byte[] tableNameOrRegionName)
throws IOException, InterruptedException {
split(tableNameOrRegionName, null);
}

public void split(final String tableNameOrRegionName,
final String splitPoint) throws IOException, InterruptedException {
split(Bytes.toBytes(tableNameOrRegionName), Bytes.toBytes(splitPoint));
}

/**
* Split a table or an individual region.
* Asynchronous operation.
*
* @param tableNameOrRegionName table to region to split
* @param splitPoint the explicit position to split on
* @throws IOException if a remote or network exception occurs
* @throws InterruptedException interrupt exception occurred
*/
public void split(final byte[] tableNameOrRegionName,
final byte [] splitPoint) throws IOException, InterruptedException {
CatalogTracker ct = getCatalogTracker();
try {
Pair<HRegionInfo, ServerName> regionServerPair
= getRegion(tableNameOrRegionName, ct);
if (regionServerPair != null) {
if (regionServerPair.getSecond() == null) {
throw new NoServerForRegionException(Bytes.toStringBinary(tableNameOrRegionName));
} else {
split(regionServerPair.getSecond(), regionServerPair.getFirst(), splitPoint);
}
} else {
final TableName tableName =
checkTableExists(TableName.valueOf(tableNameOrRegionName), ct);
List<Pair<HRegionInfo, ServerName>> pairs =
MetaReader.getTableRegionsAndLocations(ct,
tableName);
for (Pair<HRegionInfo, ServerName> pair: pairs) {
// May not be a server for a particular row
if (pair.getSecond() == null) continue;
HRegionInfo r = pair.getFirst();
// check for parents
if (r.isSplitParent()) continue;
// if a split point given, only split that particular region
if (splitPoint != null && !r.containsRow(splitPoint)) continue;
// call out to region server to do split now
split(pair.getSecond(), pair.getFirst(), splitPoint);
}
}
} finally {
cleanupCatalogTracker(ct);
}
}

private void split(final ServerName sn, final HRegionInfo hri,
byte[] splitPoint) throws IOException {
AdminService.BlockingInterface admin = this.connection.getAdmin(sn);
ProtobufUtil.split(admin, hri, splitPoint);
}

/**
* Modify an existing table, more IRB friendly version.
* Asynchronous operation.  This means that it may be a while before your
* schema change is updated across all of the table.
*
* @param tableName name of table.
* @param htd modified description of the table
* @throws IOException if a remote or network exception occurs
*/
public void modifyTable(final TableName tableName, final HTableDescriptor htd)
throws IOException {
if (!tableName.equals(htd.getTableName())) {
throw new IllegalArgumentException("the specified table name '" + tableName +
"' doesn't match with the HTD one: " + htd.getTableName());
}

executeCallable(new MasterAdminCallable<Void>(getConnection()) {
@Override
public Void call() throws ServiceException {
ModifyTableRequest request = RequestConverter.buildModifyTableRequest(tableName, htd);
masterAdmin.modifyTable(null, request);
return null;
}
});
}

public void modifyTable(final byte[] tableName, final HTableDescriptor htd)
throws IOException {
modifyTable(TableName.valueOf(tableName), htd);
}

public void modifyTable(final String tableName, final HTableDescriptor htd)
throws IOException {
modifyTable(TableName.valueOf(tableName), htd);
}

/**
* @param tableNameOrRegionName Name of a table or name of a region.
* @param ct A {@link CatalogTracker} instance (caller of this method usually has one).
* @return a pair of HRegionInfo and ServerName if <code>tableNameOrRegionName</code> is
*  a verified region name (we call {@link  MetaReader#getRegion( CatalogTracker, byte[])}
*  else null.
* Throw an exception if <code>tableNameOrRegionName</code> is null.
* @throws IOException
*/
Pair<HRegionInfo, ServerName> getRegion(final byte[] tableNameOrRegionName,
final CatalogTracker ct) throws IOException {
if (tableNameOrRegionName == null) {
throw new IllegalArgumentException("Pass a table name or region name");
}
Pair<HRegionInfo, ServerName> pair = MetaReader.getRegion(ct, tableNameOrRegionName);
if (pair == null) {
final AtomicReference<Pair<HRegionInfo, ServerName>> result =
new AtomicReference<Pair<HRegionInfo, ServerName>>(null);
final String encodedName = Bytes.toString(tableNameOrRegionName);
MetaScannerVisitor visitor = new MetaScannerVisitorBase() {
@Override
public boolean processRow(Result data) throws IOException {
HRegionInfo info = HRegionInfo.getHRegionInfo(data);
if (info == null) {
LOG.warn("No serialized HRegionInfo in " + data);
return true;
}
if (!encodedName.equals(info.getEncodedName())) return true;
ServerName sn = HRegionInfo.getServerName(data);
result.set(new Pair<HRegionInfo, ServerName>(info, sn));
return false; // found the region, stop
}
};

MetaScanner.metaScan(conf, connection, visitor, null);
pair = result.get();
}
return pair;
}

/**
* Check if table exists or not
* @param tableName Name of a table.
* @param ct A {@link CatalogTracker} instance (caller of this method usually has one).
* @return tableName instance
* @throws IOException if a remote or network exception occurs.
* @throws TableNotFoundException if table does not exist.
*/
//TODO rename this method
private TableName checkTableExists(
final TableName tableName, CatalogTracker ct)
throws IOException {
if (!MetaReader.tableExists(ct, tableName)) {
throw new TableNotFoundException(tableName);
}
return tableName;
}

/**
* Shuts down the HBase cluster
* @throws IOException if a remote or network exception occurs
*/
public synchronized void shutdown() throws IOException {
executeCallable(new MasterAdminCallable<Void>(getConnection()) {
@Override
public Void call() throws ServiceException {
masterAdmin.shutdown(null,ShutdownRequest.newBuilder().build());
return null;
}
});
}

/**
* Shuts down the current HBase master only.
* Does not shutdown the cluster.
* @see #shutdown()
* @throws IOException if a remote or network exception occurs
*/
public synchronized void stopMaster() throws IOException {
executeCallable(new MasterAdminCallable<Void>(getConnection()) {
@Override
public Void call() throws ServiceException {
masterAdmin.stopMaster(null,StopMasterRequest.newBuilder().build());
return null;
}
});
}

/**
* Stop the designated regionserver
* @param hostnamePort Hostname and port delimited by a <code>:</code> as in
* <code>example.org:1234</code>
* @throws IOException if a remote or network exception occurs
*/
public synchronized void stopRegionServer(final String hostnamePort)
throws IOException {
String hostname = Addressing.parseHostname(hostnamePort);
int port = Addressing.parsePort(hostnamePort);
AdminService.BlockingInterface admin =
this.connection.getAdmin(new ServerName(hostname, port, 0));
StopServerRequest request = RequestConverter.buildStopServerRequest(
"Called by admin client " + this.connection.toString());
try {
admin.stopServer(null, request);
} catch (ServiceException se) {
throw ProtobufUtil.getRemoteException(se);
}
}


/**
* @return cluster status
* @throws IOException if a remote or network exception occurs
*/
public ClusterStatus getClusterStatus() throws IOException {
return executeCallable(new MasterMonitorCallable<ClusterStatus>(getConnection()) {
@Override
public ClusterStatus call() throws ServiceException {
GetClusterStatusRequest req = RequestConverter.buildGetClusterStatusRequest();
return ClusterStatus.convert(masterMonitor.getClusterStatus(null,req).getClusterStatus());
}
});
}

private HRegionLocation getFirstMetaServerForTable(final TableName tableName)
throws IOException {
return connection.locateRegion(TableName.META_TABLE_NAME,
HRegionInfo.createRegionName(tableName, null, HConstants.NINES, false));
}

/**
* @return Configuration used by the instance.
*/
public Configuration getConfiguration() {
return this.conf;
}

/**
* Create a new namespace
* @param descriptor descriptor which describes the new namespace
* @throws IOException
*/
public void createNamespace(final NamespaceDescriptor descriptor) throws IOException {
executeCallable(new MasterAdminCallable<Void>(getConnection()) {
@Override
public Void call() throws Exception {
masterAdmin.createNamespace(null,
MasterAdminProtos.CreateNamespaceRequest.newBuilder()
.setNamespaceDescriptor(ProtobufUtil
.toProtoNamespaceDescriptor(descriptor)).build());
return null;
}
});
}

/**
* Modify an existing namespace
* @param descriptor descriptor which describes the new namespace
* @throws IOException
*/
public void modifyNamespace(final NamespaceDescriptor descriptor) throws IOException {
executeCallable(new MasterAdminCallable<Void>(getConnection()) {
@Override
public Void call() throws Exception {
masterAdmin.modifyNamespace(null,
MasterAdminProtos.ModifyNamespaceRequest.newBuilder()
.setNamespaceDescriptor(ProtobufUtil
.toProtoNamespaceDescriptor(descriptor)).build());
return null;
}
});
}

/**
* Delete an existing namespace. Only empty namespaces (no tables) can be removed.
* @param name namespace name
* @throws IOException
*/
public void deleteNamespace(final String name) throws IOException {
executeCallable(new MasterAdminCallable<Void>(getConnection()) {
@Override
public Void call() throws Exception {
masterAdmin.deleteNamespace(null,
MasterAdminProtos.DeleteNamespaceRequest.newBuilder()
.setNamespaceName(name).build());
return null;
}
});
}

/**
* Get a namespace descriptor by name
* @param name name of namespace descriptor
* @return A descriptor
* @throws IOException
*/
public NamespaceDescriptor getNamespaceDescriptor(final String name) throws IOException {
return
executeCallable(new MasterAdminCallable<NamespaceDescriptor>(getConnection()) {
@Override
public NamespaceDescriptor call() throws Exception {
return ProtobufUtil.toNamespaceDescriptor(
masterAdmin.getNamespaceDescriptor(null,
MasterAdminProtos.GetNamespaceDescriptorRequest.newBuilder()
.setNamespaceName(name).build()).getNamespaceDescriptor());
}
});
}

/**
* List available namespace descriptors
* @return List of descriptors
* @throws IOException
*/
public NamespaceDescriptor[] listNamespaceDescriptors() throws IOException {
return
executeCallable(new MasterAdminCallable<NamespaceDescriptor[]>(getConnection()) {
@Override
public NamespaceDescriptor[] call() throws Exception {
List<HBaseProtos.NamespaceDescriptor> list =
masterAdmin.listNamespaceDescriptors(null,
MasterAdminProtos.ListNamespaceDescriptorsRequest.newBuilder().build())
.getNamespaceDescriptorList();
NamespaceDescriptor[] res = new NamespaceDescriptor[list.size()];
for(int i = 0; i < list.size(); i++) {
res[i] = ProtobufUtil.toNamespaceDescriptor(list.get(i));
}
return res;
}
});
}

/**
* Get list of table descriptors by namespace
* @param name namespace name
* @return A descriptor
* @throws IOException
*/
public HTableDescriptor[] listTableDescriptorsByNamespace(final String name) throws IOException {
return
executeCallable(new MasterAdminCallable<HTableDescriptor[]>(getConnection()) {
@Override
public HTableDescriptor[] call() throws Exception {
List<TableSchema> list =
masterAdmin.listTableDescriptorsByNamespace(null,
MasterAdminProtos.ListTableDescriptorsByNamespaceRequest.newBuilder()
.setNamespaceName(name).build())
.getTableSchemaList();
HTableDescriptor[] res = new HTableDescriptor[list.size()];
for(int i=0; i < list.size(); i++) {

res[i] = HTableDescriptor.convert(list.get(i));
}
return res;
}
});
}

/**
* Get list of table names by namespace
* @param name namespace name
* @return The list of table names in the namespace
* @throws IOException
*/
public TableName[] listTableNamesByNamespace(final String name) throws IOException {
return
executeCallable(new MasterAdminCallable<TableName[]>(getConnection()) {
@Override
public TableName[] call() throws Exception {
List<HBaseProtos.TableName> tableNames =
masterAdmin.listTableNamesByNamespace(null,
MasterAdminProtos.ListTableNamesByNamespaceRequest.newBuilder()
.setNamespaceName(name).build())
.getTableNameList();
TableName[] result = new TableName[tableNames.size()];
for (int i = 0; i < tableNames.size(); i++) {
result[i] = ProtobufUtil.toTableName(tableNames.get(i));
}
return result;
}
});
}

/**
* Check to see if HBase is running. Throw an exception if not.
* We consider that HBase is running if ZooKeeper and Master are running.
*
* @param conf system configuration
* @throws MasterNotRunningException if the master is not running
* @throws ZooKeeperConnectionException if unable to connect to zookeeper
*/
public static void checkHBaseAvailable(Configuration conf)
throws MasterNotRunningException, ZooKeeperConnectionException, ServiceException, IOException {
Configuration copyOfConf = HBaseConfiguration.create(conf);

// We set it to make it fail as soon as possible if HBase is not available
copyOfConf.setInt(HConstants.HBASE_CLIENT_RETRIES_NUMBER, 1);
copyOfConf.setInt("zookeeper.recovery.retry", 0);

HConnectionManager.HConnectionImplementation connection
= (HConnectionManager.HConnectionImplementation)
HConnectionManager.getConnection(copyOfConf);

try {
// Check ZK first.
// If the connection exists, we may have a connection to ZK that does
//  not work anymore
ZooKeeperKeepAliveConnection zkw = null;
try {
zkw = connection.getKeepAliveZooKeeperWatcher();
zkw.getRecoverableZooKeeper().getZooKeeper().exists(
zkw.baseZNode, false);

} catch (IOException e) {
throw new ZooKeeperConnectionException("Can't connect to ZooKeeper", e);
} catch (InterruptedException e) {
Thread.currentThread().interrupt();
throw new ZooKeeperConnectionException("Can't connect to ZooKeeper", e);
} catch (KeeperException e) {
throw new ZooKeeperConnectionException("Can't connect to ZooKeeper", e);
} finally {
if (zkw != null) {
zkw.close();
}
}

// Check Master
connection.isMasterRunning();

} finally {
connection.close();
}
}

/**
* get the regions of a given table.
*
* @param tableName the name of the table
* @return Ordered list of {@link HRegionInfo}.
* @throws IOException
*/
public List<HRegionInfo> getTableRegions(final TableName tableName)
throws IOException {
CatalogTracker ct = getCatalogTracker();
List<HRegionInfo> Regions = null;
try {
Regions = MetaReader.getTableRegions(ct, tableName, true);
} finally {
cleanupCatalogTracker(ct);
}
return Regions;
}

public List<HRegionInfo> getTableRegions(final byte[] tableName)
throws IOException {
return getTableRegions(TableName.valueOf(tableName));
}

@Override
public void close() throws IOException {
if (cleanupConnectionOnClose && this.connection != null) {
this.connection.close();
}
}

/**
* Get tableDescriptors
* @param tableNames List of table names
* @return HTD[] the tableDescriptor
* @throws IOException if a remote or network exception occurs
*/
public HTableDescriptor[] getTableDescriptorsByTableName(List<TableName> tableNames)
throws IOException {
return this.connection.getHTableDescriptorsByTableName(tableNames);
}

/**
* Get tableDescriptors
* @param names List of table names
* @return HTD[] the tableDescriptor
* @throws IOException if a remote or network exception occurs
*/
public HTableDescriptor[] getTableDescriptors(List<String> names)
throws IOException {
List<TableName> tableNames = new ArrayList<TableName>(names.size());
for(String name : names) {
tableNames.add(TableName.valueOf(name));
}
return getTableDescriptorsByTableName(tableNames);
}

/**
* Roll the log writer. That is, start writing log messages to a new file.
*
* @param serverName
*          The servername of the regionserver. A server name is made of host,
*          port and startcode. This is mandatory. Here is an example:
*          <code> host187.example.com,60020,1289493121758</code>
* @return If lots of logs, flush the returned regions so next time through
* we can clean logs. Returns null if nothing to flush.  Names are actual
* region names as returned by {@link HRegionInfo#getEncodedName()}
* @throws IOException if a remote or network exception occurs
* @throws FailedLogCloseException
*/
public synchronized  byte[][] rollHLogWriter(String serverName)
throws IOException, FailedLogCloseException {
ServerName sn = new ServerName(serverName);
AdminService.BlockingInterface admin = this.connection.getAdmin(sn);
RollWALWriterRequest request = RequestConverter.buildRollWALWriterRequest();
try {
RollWALWriterResponse response = admin.rollWALWriter(null, request);
int regionCount = response.getRegionToFlushCount();
byte[][] regionsToFlush = new byte[regionCount][];
for (int i = 0; i < regionCount; i++) {
ByteString region = response.getRegionToFlush(i);
regionsToFlush[i] = region.toByteArray();
}
return regionsToFlush;
} catch (ServiceException se) {
throw ProtobufUtil.getRemoteException(se);
}
}

public String[] getMasterCoprocessors() {
try {
return getClusterStatus().getMasterCoprocessors();
} catch (IOException e) {
LOG.error("Could not getClusterStatus()",e);
return null;
}
}

/**
* Get the current compaction state of a table or region.
* It could be in a major compaction, a minor compaction, both, or none.
*
* @param tableNameOrRegionName table or region to major compact
* @throws IOException if a remote or network exception occurs
* @throws InterruptedException
* @return the current compaction state
*/
public CompactionState getCompactionState(final String tableNameOrRegionName)
throws IOException, InterruptedException {
return getCompactionState(Bytes.toBytes(tableNameOrRegionName));
}

/**
* Get the current compaction state of a table or region.
* It could be in a major compaction, a minor compaction, both, or none.
*
* @param tableNameOrRegionName table or region to major compact
* @throws IOException if a remote or network exception occurs
* @throws InterruptedException
* @return the current compaction state
*/
public CompactionState getCompactionState(final byte[] tableNameOrRegionName)
throws IOException, InterruptedException {
CompactionState state = CompactionState.NONE;
CatalogTracker ct = getCatalogTracker();
try {
Pair<HRegionInfo, ServerName> regionServerPair
= getRegion(tableNameOrRegionName, ct);
if (regionServerPair != null) {
if (regionServerPair.getSecond() == null) {
throw new NoServerForRegionException(Bytes.toStringBinary(tableNameOrRegionName));
} else {
ServerName sn = regionServerPair.getSecond();
AdminService.BlockingInterface admin = this.connection.getAdmin(sn);
GetRegionInfoRequest request = RequestConverter.buildGetRegionInfoRequest(
regionServerPair.getFirst().getRegionName(), true);
GetRegionInfoResponse response = admin.getRegionInfo(null, request);
return response.getCompactionState();
}
} else {
final TableName tableName =
checkTableExists(TableName.valueOf(tableNameOrRegionName), ct);
List<Pair<HRegionInfo, ServerName>> pairs =
MetaReader.getTableRegionsAndLocations(ct, tableName);
for (Pair<HRegionInfo, ServerName> pair: pairs) {
if (pair.getFirst().isOffline()) continue;
if (pair.getSecond() == null) continue;
try {
ServerName sn = pair.getSecond();
AdminService.BlockingInterface admin = this.connection.getAdmin(sn);
GetRegionInfoRequest request = RequestConverter.buildGetRegionInfoRequest(
pair.getFirst().getRegionName(), true);
GetRegionInfoResponse response = admin.getRegionInfo(null, request);
switch (response.getCompactionState()) {
case MAJOR_AND_MINOR:
return CompactionState.MAJOR_AND_MINOR;
case MAJOR:
if (state == CompactionState.MINOR) {
return CompactionState.MAJOR_AND_MINOR;
}
state = CompactionState.MAJOR;
break;
case MINOR:
if (state == CompactionState.MAJOR) {
return CompactionState.MAJOR_AND_MINOR;
}
state = CompactionState.MINOR;
break;
case NONE:
default: // nothing, continue
}
} catch (NotServingRegionException e) {
if (LOG.isDebugEnabled()) {
LOG.debug("Trying to get compaction state of " +
pair.getFirst() + ": " +
StringUtils.stringifyException(e));
}
}
}
}
} catch (ServiceException se) {
throw ProtobufUtil.getRemoteException(se);
} finally {
cleanupCatalogTracker(ct);
}
return state;
}

/**
* Take a snapshot for the given table. If the table is enabled, a FLUSH-type snapshot will be
* taken. If the table is disabled, an offline snapshot is taken.
* <p>
* Snapshots are considered unique based on <b>the name of the snapshot</b>. Attempts to take a
* snapshot with the same name (even a different type or with different parameters) will fail with
* a {@link SnapshotCreationException} indicating the duplicate naming.
* <p>
* Snapshot names follow the same naming constraints as tables in HBase. See
* {@link org.apache.hadoop.hbase.TableName#isLegalFullyQualifiedTableName(byte[])}.
* @param snapshotName name of the snapshot to be created
* @param tableName name of the table for which snapshot is created
* @throws IOException if a remote or network exception occurs
* @throws SnapshotCreationException if snapshot creation failed
* @throws IllegalArgumentException if the snapshot request is formatted incorrectly
*/
public void snapshot(final String snapshotName,
final TableName tableName) throws IOException,
SnapshotCreationException, IllegalArgumentException {
snapshot(snapshotName, tableName, SnapshotDescription.Type.FLUSH);
}

public void snapshot(final String snapshotName,
final String tableName) throws IOException,
SnapshotCreationException, IllegalArgumentException {
snapshot(snapshotName, TableName.valueOf(tableName),
SnapshotDescription.Type.FLUSH);
}

/**
public void snapshot(final String snapshotName,
* Create a timestamp consistent snapshot for the given table.
final byte[] tableName) throws IOException,
* <p>
* Snapshots are considered unique based on <b>the name of the snapshot</b>. Attempts to take a
* snapshot with the same name (even a different type or with different parameters) will fail with
* a {@link SnapshotCreationException} indicating the duplicate naming.
* <p>
* Snapshot names follow the same naming constraints as tables in HBase.
* @param snapshotName name of the snapshot to be created
* @param tableName name of the table for which snapshot is created
* @throws IOException if a remote or network exception occurs
* @throws SnapshotCreationException if snapshot creation failed
* @throws IllegalArgumentException if the snapshot request is formatted incorrectly
*/
public void snapshot(final byte[] snapshotName,
final TableName tableName) throws IOException,
SnapshotCreationException, IllegalArgumentException {
snapshot(Bytes.toString(snapshotName), tableName, SnapshotDescription.Type.FLUSH);
}

public void snapshot(final byte[] snapshotName,
final byte[] tableName) throws IOException,
SnapshotCreationException, IllegalArgumentException {
snapshot(Bytes.toString(snapshotName), TableName.valueOf(tableName),
SnapshotDescription.Type.FLUSH);
}

/**
* Create typed snapshot of the table.
* <p>
* Snapshots are considered unique based on <b>the name of the snapshot</b>. Attempts to take a
* snapshot with the same name (even a different type or with different parameters) will fail with
* a {@link SnapshotCreationException} indicating the duplicate naming.
* <p>
* Snapshot names follow the same naming constraints as tables in HBase. See
* {@link org.apache.hadoop.hbase.TableName#isLegalFullyQualifiedTableName(byte[])}.
* <p>
* @param snapshotName name to give the snapshot on the filesystem. Must be unique from all other
*          snapshots stored on the cluster
* @param tableName name of the table to snapshot
* @param type type of snapshot to take
* @throws IOException we fail to reach the master
* @throws SnapshotCreationException if snapshot creation failed
* @throws IllegalArgumentException if the snapshot request is formatted incorrectly
*/
public void snapshot(final String snapshotName,
final TableName tableName,
SnapshotDescription.Type type) throws IOException, SnapshotCreationException,
IllegalArgumentException {
SnapshotDescription.Builder builder = SnapshotDescription.newBuilder();
builder.setTable(tableName.getNameAsString());
builder.setName(snapshotName);
builder.setType(type);
snapshot(builder.build());
}

public void snapshot(final String snapshotName,
final String tableName,
SnapshotDescription.Type type) throws IOException, SnapshotCreationException,
IllegalArgumentException {
snapshot(snapshotName, TableName.valueOf(tableName), type);
}

public void snapshot(final String snapshotName,
final byte[] tableName,
SnapshotDescription.Type type) throws IOException, SnapshotCreationException,
IllegalArgumentException {
snapshot(snapshotName, TableName.valueOf(tableName), type);
}

/**
* Take a snapshot and wait for the server to complete that snapshot (blocking).
* <p>
* Only a single snapshot should be taken at a time for an instance of HBase, or results may be
* undefined (you can tell multiple HBase clusters to snapshot at the same time, but only one at a
* time for a single cluster).
* <p>
* Snapshots are considered unique based on <b>the name of the snapshot</b>. Attempts to take a
* snapshot with the same name (even a different type or with different parameters) will fail with
* a {@link SnapshotCreationException} indicating the duplicate naming.
* <p>
* Snapshot names follow the same naming constraints as tables in HBase. See
* {@link org.apache.hadoop.hbase.TableName#isLegalFullyQualifiedTableName(byte[])}.
* <p>
* You should probably use {@link #snapshot(String, String)} or {@link #snapshot(byte[], byte[])}
* unless you are sure about the type of snapshot that you want to take.
* @param snapshot snapshot to take
* @throws IOException or we lose contact with the master.
* @throws SnapshotCreationException if snapshot failed to be taken
* @throws IllegalArgumentException if the snapshot request is formatted incorrectly
*/
public void snapshot(SnapshotDescription snapshot) throws IOException, SnapshotCreationException,
IllegalArgumentException {
// actually take the snapshot
TakeSnapshotResponse response = takeSnapshotAsync(snapshot);
final IsSnapshotDoneRequest request = IsSnapshotDoneRequest.newBuilder().setSnapshot(snapshot)
.build();
IsSnapshotDoneResponse done = null;
long start = EnvironmentEdgeManager.currentTimeMillis();
long max = response.getExpectedTimeout();
long maxPauseTime = max / this.numRetries;
int tries = 0;
LOG.debug("Waiting a max of " + max + " ms for snapshot '" +
ClientSnapshotDescriptionUtils.toString(snapshot) + "'' to complete. (max " +
maxPauseTime + " ms per retry)");
while (tries == 0
|| ((EnvironmentEdgeManager.currentTimeMillis() - start) < max && !done.getDone())) {
try {
// sleep a backoff <= pauseTime amount
long sleep = getPauseTime(tries++);
sleep = sleep > maxPauseTime ? maxPauseTime : sleep;
LOG.debug("(#" + tries + ") Sleeping: " + sleep +
"ms while waiting for snapshot completion.");
Thread.sleep(sleep);

} catch (InterruptedException e) {
LOG.debug("Interrupted while waiting for snapshot " + snapshot + " to complete");
Thread.currentThread().interrupt();
}
LOG.debug("Getting current status of snapshot from master...");
done = executeCallable(new MasterAdminCallable<IsSnapshotDoneResponse>(getConnection()) {
@Override
public IsSnapshotDoneResponse call() throws ServiceException {
return masterAdmin.isSnapshotDone(null, request);
}
});
};
if (!done.getDone()) {
throw new SnapshotCreationException("Snapshot '" + snapshot.getName()
+ "' wasn't completed in expectedTime:" + max + " ms", snapshot);
}
}

/**
* Take a snapshot without waiting for the server to complete that snapshot (asynchronous)
* <p>
* Only a single snapshot should be taken at a time, or results may be undefined.
* @param snapshot snapshot to take
* @return response from the server indicating the max time to wait for the snapshot
* @throws IOException if the snapshot did not succeed or we lose contact with the master.
* @throws SnapshotCreationException if snapshot creation failed
* @throws IllegalArgumentException if the snapshot request is formatted incorrectly
*/
public TakeSnapshotResponse takeSnapshotAsync(SnapshotDescription snapshot) throws IOException,
SnapshotCreationException {
ClientSnapshotDescriptionUtils.assertSnapshotRequestIsValid(snapshot);
final TakeSnapshotRequest request = TakeSnapshotRequest.newBuilder().setSnapshot(snapshot)
.build();
// run the snapshot on the master
return executeCallable(new MasterAdminCallable<TakeSnapshotResponse>(getConnection()) {
@Override
public TakeSnapshotResponse call() throws ServiceException {
return masterAdmin.snapshot(null, request);
}
});
}

/**
* Check the current state of the passed snapshot.
* <p>
* There are three possible states:
* <ol>
* <li>running - returns <tt>false</tt></li>
* <li>finished - returns <tt>true</tt></li>
* <li>finished with error - throws the exception that caused the snapshot to fail</li>
* </ol>
* <p>
* The cluster only knows about the most recent snapshot. Therefore, if another snapshot has been
* run/started since the snapshot your are checking, you will recieve an
* {@link UnknownSnapshotException}.
* @param snapshot description of the snapshot to check
* @return <tt>true</tt> if the snapshot is completed, <tt>false</tt> if the snapshot is still
*         running
* @throws IOException if we have a network issue
* @throws HBaseSnapshotException if the snapshot failed
* @throws UnknownSnapshotException if the requested snapshot is unknown
*/
public boolean isSnapshotFinished(final SnapshotDescription snapshot)
throws IOException, HBaseSnapshotException, UnknownSnapshotException {

return executeCallable(new MasterAdminCallable<IsSnapshotDoneResponse>(getConnection()) {
@Override
public IsSnapshotDoneResponse call() throws ServiceException {
return masterAdmin.isSnapshotDone(null,
IsSnapshotDoneRequest.newBuilder().setSnapshot(snapshot).build());
}
}).getDone();
}

/**
* Restore the specified snapshot on the original table. (The table must be disabled)
* Before restoring the table, a new snapshot with the current table state is created.
* In case of failure, the table will be rolled back to its original state.
*
* @param snapshotName name of the snapshot to restore
* @throws IOException if a remote or network exception occurs
* @throws RestoreSnapshotException if snapshot failed to be restored
* @throws IllegalArgumentException if the restore request is formatted incorrectly
*/
public void restoreSnapshot(final byte[] snapshotName)
throws IOException, RestoreSnapshotException {
restoreSnapshot(Bytes.toString(snapshotName));
}

/**
* Restore the specified snapshot on the original table. (The table must be disabled)
* Before restoring the table, a new snapshot with the current table state is created.
* In case of failure, the table will be rolled back to the its original state.
*
* @param snapshotName name of the snapshot to restore
* @throws IOException if a remote or network exception occurs
* @throws RestoreSnapshotException if snapshot failed to be restored
* @throws IllegalArgumentException if the restore request is formatted incorrectly
*/
public void restoreSnapshot(final String snapshotName)
throws IOException, RestoreSnapshotException {
String rollbackSnapshot = snapshotName + "-" + EnvironmentEdgeManager.currentTimeMillis();

TableName tableName = null;
for (SnapshotDescription snapshotInfo: listSnapshots()) {
if (snapshotInfo.getName().equals(snapshotName)) {
tableName = TableName.valueOf(snapshotInfo.getTable());
break;
}
}

if (tableName == null) {
throw new RestoreSnapshotException(
"Unable to find the table name for snapshot=" + snapshotName);
}

// Take a snapshot of the current state
snapshot(rollbackSnapshot, tableName);

// Restore snapshot
try {
internalRestoreSnapshot(snapshotName, tableName);
} catch (IOException e) {
// Try to rollback
try {
String msg = "Restore snapshot=" + snapshotName +
" failed. Rollback to snapshot=" + rollbackSnapshot + " succeeded.";
LOG.error(msg, e);
internalRestoreSnapshot(rollbackSnapshot, tableName);
throw new RestoreSnapshotException(msg, e);
} catch (IOException ex) {
String msg = "Failed to restore and rollback to snapshot=" + rollbackSnapshot;
LOG.error(msg, ex);
throw new RestoreSnapshotException(msg, ex);
}
}
}

/**
* Create a new table by cloning the snapshot content.
*
* @param snapshotName name of the snapshot to be cloned
* @param tableName name of the table where the snapshot will be restored
* @throws IOException if a remote or network exception occurs
* @throws TableExistsException if table to be created already exists
* @throws RestoreSnapshotException if snapshot failed to be cloned
* @throws IllegalArgumentException if the specified table has not a valid name
*/
public void cloneSnapshot(final byte[] snapshotName, final byte[] tableName)
throws IOException, TableExistsException, RestoreSnapshotException, InterruptedException {
cloneSnapshot(Bytes.toString(snapshotName), TableName.valueOf(tableName));
}

/**
* Create a new table by cloning the snapshot content.
*
* @param snapshotName name of the snapshot to be cloned
* @param tableName name of the table where the snapshot will be restored
* @throws IOException if a remote or network exception occurs
* @throws TableExistsException if table to be created already exists
* @throws RestoreSnapshotException if snapshot failed to be cloned
* @throws IllegalArgumentException if the specified table has not a valid name
*/
public void cloneSnapshot(final byte[] snapshotName, final TableName tableName)
throws IOException, TableExistsException, RestoreSnapshotException, InterruptedException {
cloneSnapshot(Bytes.toString(snapshotName), tableName);
}



/**
* Create a new table by cloning the snapshot content.
*
* @param snapshotName name of the snapshot to be cloned
* @param tableName name of the table where the snapshot will be restored
* @throws IOException if a remote or network exception occurs
* @throws TableExistsException if table to be created already exists
* @throws RestoreSnapshotException if snapshot failed to be cloned
* @throws IllegalArgumentException if the specified table has not a valid name
*/
public void cloneSnapshot(final String snapshotName, final String tableName)
throws IOException, TableExistsException, RestoreSnapshotException, InterruptedException {
cloneSnapshot(snapshotName, TableName.valueOf(tableName));
}

/**
* Create a new table by cloning the snapshot content.
*
* @param snapshotName name of the snapshot to be cloned
* @param tableName name of the table where the snapshot will be restored
* @throws IOException if a remote or network exception occurs
* @throws TableExistsException if table to be created already exists
* @throws RestoreSnapshotException if snapshot failed to be cloned
* @throws IllegalArgumentException if the specified table has not a valid name
*/
public void cloneSnapshot(final String snapshotName, final TableName tableName)
throws IOException, TableExistsException, RestoreSnapshotException, InterruptedException {
if (tableExists(tableName)) {
throw new TableExistsException("Table " + tableName + " already exists");
}
internalRestoreSnapshot(snapshotName, tableName);
waitUntilTableIsEnabled(tableName);
}

/**
* Execute Restore/Clone snapshot and wait for the server to complete (blocking).
* To check if the cloned table exists, use {@link #isTableAvailable} -- it is not safe to
* create an HTable instance to this table before it is available.
* @param snapshotName snapshot to restore
* @param tableName table name to restore the snapshot on
* @throws IOException if a remote or network exception occurs
* @throws RestoreSnapshotException if snapshot failed to be restored
* @throws IllegalArgumentException if the restore request is formatted incorrectly
*/
private void internalRestoreSnapshot(final String snapshotName, final TableName
tableName)
throws IOException, RestoreSnapshotException {
SnapshotDescription snapshot = SnapshotDescription.newBuilder()
.setName(snapshotName).setTable(tableName.getNameAsString()).build();

// actually restore the snapshot
internalRestoreSnapshotAsync(snapshot);

final IsRestoreSnapshotDoneRequest request = IsRestoreSnapshotDoneRequest.newBuilder()
.setSnapshot(snapshot).build();
IsRestoreSnapshotDoneResponse done = IsRestoreSnapshotDoneResponse.newBuilder().buildPartial();
final long maxPauseTime = 5000;
int tries = 0;
while (!done.getDone()) {
try {
// sleep a backoff <= pauseTime amount
long sleep = getPauseTime(tries++);
sleep = sleep > maxPauseTime ? maxPauseTime : sleep;
LOG.debug(tries + ") Sleeping: " + sleep + " ms while we wait for snapshot restore to complete.");
Thread.sleep(sleep);
} catch (InterruptedException e) {
LOG.debug("Interrupted while waiting for snapshot " + snapshot + " restore to complete");
Thread.currentThread().interrupt();
}
LOG.debug("Getting current status of snapshot restore from master...");
done = executeCallable(new MasterAdminCallable<IsRestoreSnapshotDoneResponse>(
getConnection()) {
@Override
public IsRestoreSnapshotDoneResponse call() throws ServiceException {
return masterAdmin.isRestoreSnapshotDone(null, request);
}
});
}
if (!done.getDone()) {
throw new RestoreSnapshotException("Snapshot '" + snapshot.getName() + "' wasn't restored.");
}
}

/**
* Execute Restore/Clone snapshot and wait for the server to complete (asynchronous)
* <p>
* Only a single snapshot should be restored at a time, or results may be undefined.
* @param snapshot snapshot to restore
* @return response from the server indicating the max time to wait for the snapshot
* @throws IOException if a remote or network exception occurs
* @throws RestoreSnapshotException if snapshot failed to be restored
* @throws IllegalArgumentException if the restore request is formatted incorrectly
*/
private RestoreSnapshotResponse internalRestoreSnapshotAsync(final SnapshotDescription snapshot)
throws IOException, RestoreSnapshotException {
ClientSnapshotDescriptionUtils.assertSnapshotRequestIsValid(snapshot);

final RestoreSnapshotRequest request = RestoreSnapshotRequest.newBuilder().setSnapshot(snapshot)
.build();

// run the snapshot restore on the master
return executeCallable(new MasterAdminCallable<RestoreSnapshotResponse>(getConnection()) {
@Override
public RestoreSnapshotResponse call() throws ServiceException {
return masterAdmin.restoreSnapshot(null, request);
}
});
}

/**
* List completed snapshots.
* @return a list of snapshot descriptors for completed snapshots
* @throws IOException if a network error occurs
*/
public List<SnapshotDescription> listSnapshots() throws IOException {
return executeCallable(new MasterAdminCallable<List<SnapshotDescription>>(getConnection()) {
@Override
public List<SnapshotDescription> call() throws ServiceException {
return masterAdmin.getCompletedSnapshots(null, ListSnapshotRequest.newBuilder().build())
.getSnapshotsList();
}
});
}

/**
* List all the completed snapshots matching the given regular expression.
*
* @param regex The regular expression to match against
* @return - returns a List of SnapshotDescription
* @throws IOException if a remote or network exception occurs
*/
public List<SnapshotDescription> listSnapshots(String regex) throws IOException {
return listSnapshots(Pattern.compile(regex));
}

/**
* List all the completed snapshots matching the given pattern.
*
* @param pattern The compiled regular expression to match against
* @return - returns a List of SnapshotDescription
* @throws IOException if a remote or network exception occurs
*/
public List<SnapshotDescription> listSnapshots(Pattern pattern) throws IOException {
List<SnapshotDescription> matched = new LinkedList<SnapshotDescription>();
List<SnapshotDescription> snapshots = listSnapshots();
for (SnapshotDescription snapshot : snapshots) {
if (pattern.matcher(snapshot.getName()).matches()) {
matched.add(snapshot);
}
}
return matched;
}

/**
* Delete an existing snapshot.
* @param snapshotName name of the snapshot
* @throws IOException if a remote or network exception occurs
*/
public void deleteSnapshot(final byte[] snapshotName) throws IOException {
deleteSnapshot(Bytes.toString(snapshotName));
}

/**
* Delete an existing snapshot.
* @param snapshotName name of the snapshot
* @throws IOException if a remote or network exception occurs
*/
public void deleteSnapshot(final String snapshotName) throws IOException {
// make sure the snapshot is possibly valid
TableName.isLegalFullyQualifiedTableName(Bytes.toBytes(snapshotName));
// do the delete
executeCallable(new MasterAdminCallable<Void>(getConnection()) {
@Override
public Void call() throws ServiceException {
masterAdmin.deleteSnapshot(null,
DeleteSnapshotRequest.newBuilder().
setSnapshot(SnapshotDescription.newBuilder().setName(snapshotName).build()).build());
return null;
}
});
}

/**
* Delete existing snapshots whose names match the pattern passed.
* @param regex The regular expression to match against
* @throws IOException if a remote or network exception occurs
*/
public void deleteSnapshots(final String regex) throws IOException {
deleteSnapshots(Pattern.compile(regex));
}

/**
* Delete existing snapshots whose names match the pattern passed.
* @param pattern pattern for names of the snapshot to match
* @throws IOException if a remote or network exception occurs
*/
public void deleteSnapshots(final Pattern pattern) throws IOException {
List<SnapshotDescription> snapshots = listSnapshots(pattern);
for (final SnapshotDescription snapshot : snapshots) {
// do the delete
executeCallable(new MasterAdminCallable<Void>(getConnection()) {
@Override
public Void call() throws ServiceException {
this.masterAdmin.deleteSnapshot(null,
DeleteSnapshotRequest.newBuilder().setSnapshot(snapshot).build());
return null;
}
});
}
}

/**
* @see {@link #executeCallable(org.apache.hadoop.hbase.client.HBaseAdmin.MasterCallable)}
*/
abstract static class MasterAdminCallable<V> extends MasterCallable<V> {
protected MasterAdminKeepAliveConnection masterAdmin;

public MasterAdminCallable(final HConnection connection) {
super(connection);
}

@Override
public void prepare(boolean reload) throws IOException {
this.masterAdmin = this.connection.getKeepAliveMasterAdminService();
}

@Override
public void close() throws IOException {
this.masterAdmin.close();
}
}

/**
* @see {@link #executeCallable(org.apache.hadoop.hbase.client.HBaseAdmin.MasterCallable)}
*/
abstract static class MasterMonitorCallable<V> extends MasterCallable<V> {
protected MasterMonitorKeepAliveConnection masterMonitor;

public MasterMonitorCallable(final HConnection connection) {
super(connection);
}

@Override
public void prepare(boolean reload) throws IOException {
this.masterMonitor = this.connection.getKeepAliveMasterMonitorService();
}

@Override
public void close() throws IOException {
this.masterMonitor.close();
}
}

/**
* Parent of {@link MasterMonitorCallable} and {@link MasterAdminCallable}.
* Has common methods.
* @param <V>
*/
abstract static class MasterCallable<V> implements RetryingCallable<V>, Closeable {
protected HConnection connection;

public MasterCallable(final HConnection connection) {
this.connection = connection;
}

@Override
public void throwable(Throwable t, boolean retrying) {
}

@Override
public String getExceptionMessageAdditionalDetail() {
return "";
}

@Override
public long sleep(long pause, int tries) {
return ConnectionUtils.getPauseTime(pause, tries);
}
}

private <V> V executeCallable(MasterCallable<V> callable) throws IOException {
RpcRetryingCaller<V> caller = rpcCallerFactory.newCaller();
try {
return caller.callWithRetries(callable);
} finally {
callable.close();
}
}

/**
* Creates and returns a {@link com.google.protobuf.RpcChannel} instance
* connected to the active master.
*
* <p>
* The obtained {@link com.google.protobuf.RpcChannel} instance can be used to access a published
* coprocessor {@link com.google.protobuf.Service} using standard protobuf service invocations:
* </p>
*
* <div style="background-color: #cccccc; padding: 2px">
* <blockquote><pre>
* CoprocessorRpcChannel channel = myAdmin.coprocessorService();
* MyService.BlockingInterface service = MyService.newBlockingStub(channel);
* MyCallRequest request = MyCallRequest.newBuilder()
*     ...
*     .build();
* MyCallResponse response = service.myCall(null, request);
* </pre></blockquote></div>
*
* @return A MasterCoprocessorRpcChannel instance
*/
public CoprocessorRpcChannel coprocessorService() {
return new MasterCoprocessorRpcChannel(connection);
}
}