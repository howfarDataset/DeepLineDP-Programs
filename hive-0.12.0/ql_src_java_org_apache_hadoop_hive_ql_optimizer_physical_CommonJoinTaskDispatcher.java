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
package org.apache.hadoop.hive.ql.optimizer.physical;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.common.ObjectPair;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.Context;
import org.apache.hadoop.hive.ql.exec.ConditionalTask;
import org.apache.hadoop.hive.ql.exec.FileSinkOperator;
import org.apache.hadoop.hive.ql.exec.JoinOperator;
import org.apache.hadoop.hive.ql.exec.Operator;
import org.apache.hadoop.hive.ql.exec.OperatorUtils;
import org.apache.hadoop.hive.ql.exec.TableScanOperator;
import org.apache.hadoop.hive.ql.exec.Task;
import org.apache.hadoop.hive.ql.exec.TaskFactory;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.exec.mr.MapRedTask;
import org.apache.hadoop.hive.ql.lib.Dispatcher;
import org.apache.hadoop.hive.ql.optimizer.GenMapRedUtils;
import org.apache.hadoop.hive.ql.optimizer.MapJoinProcessor;
import org.apache.hadoop.hive.ql.parse.ParseContext;
import org.apache.hadoop.hive.ql.parse.QBJoinTree;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.ql.plan.ConditionalResolverCommonJoin;
import org.apache.hadoop.hive.ql.plan.ConditionalResolverCommonJoin.ConditionalResolverCommonJoinCtx;
import org.apache.hadoop.hive.ql.plan.ConditionalWork;
import org.apache.hadoop.hive.ql.plan.JoinDesc;
import org.apache.hadoop.hive.ql.plan.MapWork;
import org.apache.hadoop.hive.ql.plan.MapredLocalWork;
import org.apache.hadoop.hive.ql.plan.MapredWork;
import org.apache.hadoop.hive.ql.plan.OperatorDesc;
import org.apache.hadoop.hive.ql.plan.ReduceWork;

/*
* Convert tasks involving JOIN into MAPJOIN.
* If hive.auto.convert.join is true, the tasks involving join are converted.
* Consider the query:
* select .... from T1 join T2 on T1.key = T2.key join T3 on T1.key = T3.key
*
* There is a map-reduce task which performs a 3-way join (T1, T2, T3).
* The task would be converted to a conditional task which would have 4 children
* a. Mapjoin considering T1 as the big table
* b. Mapjoin considering T2 as the big table
* c. Mapjoin considering T3 as the big table
* d. Map-reduce join (the original task).
*
*  Note that the sizes of all the inputs may not be available at compile time. At runtime, it is
*  determined which branch we want to pick up from the above.
*
* However, if hive.auto.convert.join.noconditionaltask is set to true, and
* the sum of any n-1 tables is smaller than hive.auto.convert.join.noconditionaltask.size,
* then a mapjoin is created instead of the conditional task. For the above, if the size of
* T1 + T2 is less than the threshold, then the task is converted to a mapjoin task with T3 as
* the big table.
*
* In this case, further optimization is performed by merging 2 consecutive map-only jobs.
* Consider the query:
* select ... from T1 join T2 on T1.key1 = T2.key1 join T3 on T1.key2 = T3.key2
*
* Initially, the plan would consist of 2 Map-reduce jobs (1 to perform join for T1 and T2)
* followed by another map-reduce job (to perform join of the result with T3). After the
* optimization, both these tasks would be converted to map-only tasks. These 2 map-only jobs
* are then merged into a single map-only job. As a followup (HIVE-3952), it would be possible to
* merge a map-only task with a map-reduce task.
* Consider the query:
* select T1.key2, count(*) from T1 join T2 on T1.key1 = T2.key1 group by T1.key2;
* Initially, the plan would consist of 2 Map-reduce jobs (1 to perform join for T1 and T2)
* followed by another map-reduce job (to perform groupby of the result). After the
* optimization, the join task would be converted to map-only tasks. After HIVE-3952, the map-only
* task would be merged with the map-reduce task to create a single map-reduce task.
*/

/**
* Iterator each tasks. If this task has a local work,create a new task for this local work, named
* MapredLocalTask. then make this new generated task depends on current task's parent task, and
* make current task depends on this new generated task
*/
public class CommonJoinTaskDispatcher extends AbstractJoinTaskDispatcher implements Dispatcher {

HashMap<String, Long> aliasToSize = null;

public CommonJoinTaskDispatcher(PhysicalContext context) {
super(context);
}

/**
* Calculate the total size of local tables in loclWork.
* @param localWork
* @return the total size of local tables. Or -1, if the total
* size is unknown.
*/
private long calculateLocalTableTotalSize(MapredLocalWork localWork) {
long localTableTotalSize = 0;
if (localWork == null) {
return localTableTotalSize;
}
for (String alias : localWork.getAliasToWork().keySet()) {
Long tabSize = aliasToSize.get(alias);
if (tabSize == null) {
// if the size is unavailable, we need to assume a size 1 greater than
// localTableTotalSizeLimit this implies that merge cannot happen
// so we will return false.
return -1;
}
localTableTotalSize += tabSize;
}
return localTableTotalSize;
}

/**
* Check if the total size of local tables will be under
* the limit after we merge localWork1 and localWork2.
* The limit of the total size of local tables is defined by
* HiveConf.ConfVars.HIVECONVERTJOINNOCONDITIONALTASKTHRESHOLD.
* @param conf
* @param localWorks
* @return
*/
private boolean isLocalTableTotalSizeUnderLimitAfterMerge(
Configuration conf,
MapredLocalWork... localWorks) {
final long localTableTotalSizeLimit = HiveConf.getLongVar(conf,
HiveConf.ConfVars.HIVECONVERTJOINNOCONDITIONALTASKTHRESHOLD);
long localTableTotalSize = 0;
for (int i = 0; i < localWorks.length; i++) {
final long localWorkTableTotalSize = calculateLocalTableTotalSize(localWorks[i]);
if (localWorkTableTotalSize < 0) {
// The total size of local tables in localWork[i] is unknown.
return false;
}
localTableTotalSize += localWorkTableTotalSize;
}

if (localTableTotalSize > localTableTotalSizeLimit) {
// The total size of local tables after we merge localWorks
// is larger than the limit set by
// HiveConf.ConfVars.HIVECONVERTJOINNOCONDITIONALTASKTHRESHOLD.
return false;
}

return true;
}

// Get the position of the big table for this join operator and the given alias
private int getPosition(MapWork work, Operator<? extends OperatorDesc> joinOp,
String alias) {
Operator<? extends OperatorDesc> parentOp = work.getAliasToWork().get(alias);

// reduceSinkOperator's child is null, but joinOperator's parents is reduceSink
while ((parentOp.getChildOperators() != null) &&
(!parentOp.getChildOperators().isEmpty())) {
parentOp = parentOp.getChildOperators().get(0);
}
return joinOp.getParentOperators().indexOf(parentOp);
}

// create map join task and set big table as bigTablePosition
private ObjectPair<MapRedTask, String> convertTaskToMapJoinTask(MapredWork newWork,
int bigTablePosition) throws UnsupportedEncodingException, SemanticException {
// create a mapred task for this work
MapRedTask newTask = (MapRedTask) TaskFactory.get(newWork, physicalContext
.getParseContext().getConf());
JoinOperator newJoinOp = getJoinOp(newTask);

// optimize this newWork given the big table position
String bigTableAlias =
MapJoinProcessor.genMapJoinOpAndLocalWork(newWork, newJoinOp, bigTablePosition);
return new ObjectPair<MapRedTask, String>(newTask, bigTableAlias);
}

/*
* A task and its child task has been converted from join to mapjoin.
* See if the two tasks can be merged.
*/
private void mergeMapJoinTaskIntoItsChildMapRedTask(MapRedTask mapJoinTask, Configuration conf)
throws SemanticException{
// Step 1: Check if mapJoinTask has a single child.
// If so, check if we can merge mapJoinTask into that child.
if (mapJoinTask.getChildTasks() == null
|| mapJoinTask.getChildTasks().size() > 1) {
// No child-task to merge, nothing to do or there are more than one
// child-tasks in which case we don't want to do anything.
return;
}

Task<? extends Serializable> childTask = mapJoinTask.getChildTasks().get(0);
if (!(childTask instanceof MapRedTask)) {
// Nothing to do if it is not a MapReduce task.
return;
}

MapRedTask childMapRedTask = (MapRedTask) childTask;
MapWork mapJoinMapWork = mapJoinTask.getWork().getMapWork();
MapWork childMapWork = childMapRedTask.getWork().getMapWork();

Map<String, Operator<? extends OperatorDesc>> mapJoinAliasToWork =
mapJoinMapWork.getAliasToWork();
if (mapJoinAliasToWork.size() > 1) {
// Do not merge if the MapredWork of MapJoin has multiple input aliases.
return;
}

Entry<String, Operator<? extends OperatorDesc>> mapJoinAliasToWorkEntry =
mapJoinAliasToWork.entrySet().iterator().next();
String mapJoinAlias = mapJoinAliasToWorkEntry.getKey();
TableScanOperator mapJoinTaskTableScanOperator =
OperatorUtils.findSingleOperator(
mapJoinAliasToWorkEntry.getValue(), TableScanOperator.class);
if (mapJoinTaskTableScanOperator == null) {
throw new SemanticException("Expected a " + TableScanOperator.getOperatorName() +
" operator as the work associated with alias " + mapJoinAlias +
". Found a " + mapJoinAliasToWork.get(mapJoinAlias).getName() + " operator.");
}
FileSinkOperator mapJoinTaskFileSinkOperator =
OperatorUtils.findSingleOperator(
mapJoinTaskTableScanOperator, FileSinkOperator.class);
if (mapJoinTaskFileSinkOperator == null) {
throw new SemanticException("Cannot find the " + FileSinkOperator.getOperatorName() +
" operator at the last operator of the MapJoin Task.");
}

// The mapJoinTaskFileSinkOperator writes to a different directory
String childMRPath = mapJoinTaskFileSinkOperator.getConf().getDirName();
List<String> childMRAliases = childMapWork.getPathToAliases().get(childMRPath);
if (childMRAliases == null || childMRAliases.size() != 1) {
return;
}
String childMRAlias = childMRAliases.get(0);

MapredLocalWork mapJoinLocalWork = mapJoinMapWork.getMapLocalWork();
MapredLocalWork childLocalWork = childMapWork.getMapLocalWork();

if ((mapJoinLocalWork != null && mapJoinLocalWork.getBucketMapjoinContext() != null) ||
(childLocalWork != null && childLocalWork.getBucketMapjoinContext() != null)) {
// Right now, we do not handle the case that either of them is bucketed.
// We should relax this constraint with a follow-up jira.
return;
}

// We need to check if the total size of local tables is under the limit.
// At here, we are using a strong condition, which is the total size of
// local tables used by all input paths. Actually, we can relax this condition
// to check the total size of local tables for every input path.
// Example:
//               UNION_ALL
//              /         \
//             /           \
//            /             \
//           /               \
//       MapJoin1          MapJoin2
//      /   |   \         /   |   \
//     /    |    \       /    |    \
//   Big1   S1   S2    Big2   S3   S4
// In this case, we have two MapJoins, MapJoin1 and MapJoin2. Big1 and Big2 are two
// big tables, and S1, S2, S3, and S4 are four small tables. Hash tables of S1 and S2
// will only be used by Map tasks processing Big1. Hash tables of S3 and S4 will only
// be used by Map tasks processing Big2. If Big1!=Big2, we should only check if the size
// of S1 + S2 is under the limit, and if the size of S3 + S4 is under the limit.
// But, right now, we are checking the size of S1 + S2 + S3 + S4 is under the limit.
// If Big1=Big2, we will only scan a path once. So, MapJoin1 and MapJoin2 will be executed
// in the same Map task. In this case, we need to make sure the size of S1 + S2 + S3 + S4
// is under the limit.
if (!isLocalTableTotalSizeUnderLimitAfterMerge(conf, mapJoinLocalWork, childLocalWork)){
// The total size of local tables may not be under
// the limit after we merge mapJoinLocalWork and childLocalWork.
// Do not merge.
return;
}

TableScanOperator childMRTaskTableScanOperator =
OperatorUtils.findSingleOperator(
childMapWork.getAliasToWork().get(childMRAlias), TableScanOperator.class);
if (childMRTaskTableScanOperator == null) {
throw new SemanticException("Expected a " + TableScanOperator.getOperatorName() +
" operator as the work associated with alias " + childMRAlias +
". Found a " + childMapWork.getAliasToWork().get(childMRAlias).getName() + " operator.");
}

List<Operator<? extends OperatorDesc>> parentsInMapJoinTask =
mapJoinTaskFileSinkOperator.getParentOperators();
List<Operator<? extends OperatorDesc>> childrenInChildMRTask =
childMRTaskTableScanOperator.getChildOperators();
if (parentsInMapJoinTask.size() > 1 || childrenInChildMRTask.size() > 1) {
// Do not merge if we do not know how to connect two operator trees.
return;
}

// Step 2: Merge mapJoinTask into the Map-side of its child.
// Step 2.1: Connect the operator trees of two MapRedTasks.
Operator<? extends OperatorDesc> parentInMapJoinTask = parentsInMapJoinTask.get(0);
Operator<? extends OperatorDesc> childInChildMRTask = childrenInChildMRTask.get(0);
parentInMapJoinTask.replaceChild(mapJoinTaskFileSinkOperator, childInChildMRTask);
childInChildMRTask.replaceParent(childMRTaskTableScanOperator, parentInMapJoinTask);

// Step 2.2: Replace the corresponding part childMRWork's MapWork.
GenMapRedUtils.replaceMapWork(mapJoinAlias, childMRAlias, mapJoinMapWork, childMapWork);

// Step 2.3: Fill up stuff in local work
if (mapJoinLocalWork != null) {
if (childLocalWork == null) {
childMapWork.setMapLocalWork(mapJoinLocalWork);
} else {
childLocalWork.getAliasToFetchWork().putAll(mapJoinLocalWork.getAliasToFetchWork());
childLocalWork.getAliasToWork().putAll(mapJoinLocalWork.getAliasToWork());
}
}

// Step 2.4: Remove this MapJoin task
List<Task<? extends Serializable>> parentTasks = mapJoinTask.getParentTasks();
mapJoinTask.setParentTasks(null);
mapJoinTask.setChildTasks(null);
childMapRedTask.getParentTasks().remove(mapJoinTask);
if (parentTasks != null) {
childMapRedTask.getParentTasks().addAll(parentTasks);
for (Task<? extends Serializable> parentTask : parentTasks) {
parentTask.getChildTasks().remove(mapJoinTask);
if (!parentTask.getChildTasks().contains(childMapRedTask)) {
parentTask.getChildTasks().add(childMapRedTask);
}
}
} else {
if (physicalContext.getRootTasks().contains(mapJoinTask)) {
physicalContext.removeFromRootTask(mapJoinTask);
if (childMapRedTask.getParentTasks() != null &&
childMapRedTask.getParentTasks().size() == 0 &&
!physicalContext.getRootTasks().contains(childMapRedTask)) {
physicalContext.addToRootTask(childMapRedTask);
}
}
}
if (childMapRedTask.getParentTasks().size() == 0) {
childMapRedTask.setParentTasks(null);
}
}

public static boolean cannotConvert(String bigTableAlias,
Map<String, Long> aliasToSize, long aliasTotalKnownInputSize,
long ThresholdOfSmallTblSizeSum) {
boolean ret = false;
Long aliasKnownSize = aliasToSize.get(bigTableAlias);
if (aliasKnownSize != null && aliasKnownSize.longValue() > 0) {
long smallTblTotalKnownSize = aliasTotalKnownInputSize
- aliasKnownSize.longValue();
if (smallTblTotalKnownSize > ThresholdOfSmallTblSizeSum) {
//this table is not good to be a big table.
ret = true;
}
}
return ret;
}

@Override
public Task<? extends Serializable> processCurrentTask(MapRedTask currTask,
ConditionalTask conditionalTask, Context context)
throws SemanticException {

// whether it contains common join op; if contains, return this common join op
JoinOperator joinOp = getJoinOp(currTask);
if (joinOp == null || joinOp.getConf().isFixedAsSorted()) {
return null;
}
currTask.setTaskTag(Task.COMMON_JOIN);

MapWork currWork = currTask.getWork().getMapWork();

// create conditional work list and task list
List<Serializable> listWorks = new ArrayList<Serializable>();
List<Task<? extends Serializable>> listTasks = new ArrayList<Task<? extends Serializable>>();

// create alias to task mapping and alias to input file mapping for resolver
HashMap<String, Task<? extends Serializable>> aliasToTask =
new HashMap<String, Task<? extends Serializable>>();
HashMap<String, ArrayList<String>> pathToAliases = currWork.getPathToAliases();
Map<String, Operator<? extends OperatorDesc>> aliasToWork = currWork.getAliasToWork();

// get parseCtx for this Join Operator
ParseContext parseCtx = physicalContext.getParseContext();
QBJoinTree joinTree = parseCtx.getJoinContext().get(joinOp);

// start to generate multiple map join tasks
JoinDesc joinDesc = joinOp.getConf();
Byte[] order = joinDesc.getTagOrder();
int numAliases = order.length;

if (aliasToSize == null) {
aliasToSize = new HashMap<String, Long>();
}

try {
long aliasTotalKnownInputSize =
getTotalKnownInputSize(context, currWork, pathToAliases, aliasToSize);

Set<Integer> bigTableCandidates = MapJoinProcessor.getBigTableCandidates(joinDesc
.getConds());

// no table could be the big table; there is no need to convert
if (bigTableCandidates == null) {
return null;
}

Configuration conf = context.getConf();

// If sizes of atleast n-1 tables in a n-way join is known, and their sum is smaller than
// the threshold size, convert the join into map-join and don't create a conditional task
boolean convertJoinMapJoin = HiveConf.getBoolVar(conf,
HiveConf.ConfVars.HIVECONVERTJOINNOCONDITIONALTASK);
int bigTablePosition = -1;
if (convertJoinMapJoin) {
// This is the threshold that the user has specified to fit in mapjoin
long mapJoinSize = HiveConf.getLongVar(conf,
HiveConf.ConfVars.HIVECONVERTJOINNOCONDITIONALTASKTHRESHOLD);

boolean bigTableFound = false;
long largestBigTableCandidateSize = -1;
long sumTableSizes = 0;
for (String alias : aliasToWork.keySet()) {
int tablePosition = getPosition(currWork, joinOp, alias);
boolean bigTableCandidate = bigTableCandidates.contains(tablePosition);
Long size = aliasToSize.get(alias);
// The size is not available at compile time if the input is a sub-query.
// If the size of atleast n-1 inputs for a n-way join are available at compile time,
// and the sum of them is less than the specified threshold, then convert the join
// into a map-join without the conditional task.
if ((size == null) || (size > mapJoinSize)) {
sumTableSizes += largestBigTableCandidateSize;
if (bigTableFound || (sumTableSizes > mapJoinSize) || !bigTableCandidate) {
convertJoinMapJoin = false;
break;
}
bigTableFound = true;
bigTablePosition = tablePosition;
largestBigTableCandidateSize = mapJoinSize + 1;
} else {
if (bigTableCandidate && size > largestBigTableCandidateSize) {
bigTablePosition = tablePosition;
sumTableSizes += largestBigTableCandidateSize;
largestBigTableCandidateSize = size;
} else {
sumTableSizes += size;
}
if (sumTableSizes > mapJoinSize) {
convertJoinMapJoin = false;
break;
}
}
}
}

String bigTableAlias = null;
currWork.setOpParseCtxMap(parseCtx.getOpParseCtx());
currWork.setJoinTree(joinTree);

if (convertJoinMapJoin) {
// create map join task and set big table as bigTablePosition
MapRedTask newTask = convertTaskToMapJoinTask(currTask.getWork(), bigTablePosition).getFirst();

newTask.setTaskTag(Task.MAPJOIN_ONLY_NOBACKUP);
replaceTask(currTask, newTask, physicalContext);

// Can this task be merged with the child task. This can happen if a big table is being
// joined with multiple small tables on different keys
if ((newTask.getChildTasks() != null) && (newTask.getChildTasks().size() == 1)) {
mergeMapJoinTaskIntoItsChildMapRedTask(newTask, conf);
}

return newTask;
}

long ThresholdOfSmallTblSizeSum = HiveConf.getLongVar(conf,
HiveConf.ConfVars.HIVESMALLTABLESFILESIZE);
for (int i = 0; i < numAliases; i++) {
// this table cannot be big table
if (!bigTableCandidates.contains(i)) {
continue;
}
// deep copy a new mapred work from xml
// Once HIVE-4396 is in, it would be faster to use a cheaper method to clone the plan
MapredWork newWork = Utilities.clonePlan(currTask.getWork());

// create map join task and set big table as i
ObjectPair<MapRedTask, String> newTaskAlias = convertTaskToMapJoinTask(newWork, i);
MapRedTask newTask = newTaskAlias.getFirst();
bigTableAlias = newTaskAlias.getSecond();

if (cannotConvert(bigTableAlias, aliasToSize,
aliasTotalKnownInputSize, ThresholdOfSmallTblSizeSum)) {
continue;
}

// add into conditional task
listWorks.add(newTask.getWork());
listTasks.add(newTask);
newTask.setTaskTag(Task.CONVERTED_MAPJOIN);

// set up backup task
newTask.setBackupTask(currTask);
newTask.setBackupChildrenTasks(currTask.getChildTasks());

// put the mapping alias to task
aliasToTask.put(bigTableAlias, newTask);
}
} catch (Exception e) {
e.printStackTrace();
throw new SemanticException("Generate Map Join Task Error: " + e.getMessage());
}

// insert current common join task to conditional task
listWorks.add(currTask.getWork());
listTasks.add(currTask);
// clear JoinTree and OP Parse Context
currWork.setOpParseCtxMap(null);
currWork.setJoinTree(null);

// create conditional task and insert conditional task into task tree
ConditionalWork cndWork = new ConditionalWork(listWorks);
ConditionalTask cndTsk = (ConditionalTask) TaskFactory.get(cndWork, parseCtx.getConf());
cndTsk.setListTasks(listTasks);

// set resolver and resolver context
cndTsk.setResolver(new ConditionalResolverCommonJoin());
ConditionalResolverCommonJoinCtx resolverCtx = new ConditionalResolverCommonJoinCtx();
resolverCtx.setPathToAliases(pathToAliases);
resolverCtx.setAliasToKnownSize(aliasToSize);
resolverCtx.setAliasToTask(aliasToTask);
resolverCtx.setCommonJoinTask(currTask);
resolverCtx.setLocalTmpDir(context.getLocalScratchDir(false));
resolverCtx.setHdfsTmpDir(context.getMRScratchDir());
cndTsk.setResolverCtx(resolverCtx);

// replace the current task with the new generated conditional task
replaceTaskWithConditionalTask(currTask, cndTsk, physicalContext);
return cndTsk;
}

/*
* If any operator which does not allow map-side conversion is present in the mapper, dont
* convert it into a conditional task.
*/
private boolean checkOperatorOKMapJoinConversion(Operator<? extends OperatorDesc> op) {
if (!op.opAllowedConvertMapJoin()) {
return false;
}

if (op.getChildOperators() == null) {
return true;
}

for (Operator<? extends OperatorDesc> childOp : op.getChildOperators()) {
if (!checkOperatorOKMapJoinConversion(childOp)) {
return false;
}
}

return true;
}

private JoinOperator getJoinOp(MapRedTask task) throws SemanticException {
MapWork mWork = task.getWork().getMapWork();
ReduceWork rWork = task.getWork().getReduceWork();
if (rWork == null) {
return null;
}
Operator<? extends OperatorDesc> reducerOp = rWork.getReducer();
if (reducerOp instanceof JoinOperator) {
/* Is any operator present, which prevents the conversion */
Map<String, Operator<? extends OperatorDesc>> aliasToWork = mWork.getAliasToWork();
for (Operator<? extends OperatorDesc> op : aliasToWork.values()) {
if (!checkOperatorOKMapJoinConversion(op)) {
return null;
}
}
return (JoinOperator) reducerOp;
} else {
return null;
}
}
}