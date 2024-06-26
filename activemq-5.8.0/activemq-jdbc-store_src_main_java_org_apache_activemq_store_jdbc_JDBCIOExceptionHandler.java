/**
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.activemq.store.jdbc;

import java.io.IOException;

import org.apache.activemq.broker.Locker;
import org.apache.activemq.util.DefaultIOExceptionHandler;

/**
* @org.apache.xbean.XBean
*/
public class JDBCIOExceptionHandler extends DefaultIOExceptionHandler {

public JDBCIOExceptionHandler() {
setIgnoreSQLExceptions(false);
setStopStartConnectors(true);
}

@Override
protected boolean hasLockOwnership() throws IOException {
boolean hasLock = true;
if (broker.getPersistenceAdapter() instanceof JDBCPersistenceAdapter) {
JDBCPersistenceAdapter jdbcPersistenceAdapter = (JDBCPersistenceAdapter) broker.getPersistenceAdapter();
Locker locker = jdbcPersistenceAdapter.getLocker();
if (locker != null) {
try {
if (!locker.keepAlive()) {
hasLock = false;
}
} catch (IOException ignored) {
}

if (!hasLock) {
throw new IOException("PersistenceAdapter lock no longer valid using: " + locker);
}
}
}
return hasLock;
}

}