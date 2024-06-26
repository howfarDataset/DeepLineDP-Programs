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
package org.apache.camel.component.krati;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import krati.core.segment.ChannelSegmentFactory;
import krati.core.segment.SegmentFactory;
import krati.io.Serializer;
import krati.store.DataStore;
import krati.util.FnvHashFunction;
import krati.util.HashFunction;
import org.apache.camel.Consumer;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.component.krati.serializer.KratiDefaultSerializer;
import org.apache.camel.impl.DefaultEndpoint;

/**
* Represents a Krati endpoint.
*/
public class KratiEndpoint extends DefaultEndpoint {

protected static Map<String, KratiDataStoreRegistration> dataStoreRegistry = new HashMap<String, KratiDataStoreRegistration>();

protected String key;
protected String value;
protected String operation;

protected int initialCapacity = 100;
protected int segmentFileSize = 64;
@SuppressWarnings({"unchecked", "rawtypes"})
protected Serializer<Object> keySerializer = new KratiDefaultSerializer();
@SuppressWarnings({"unchecked", "rawtypes"})
protected Serializer<Object> valueSerializer = new KratiDefaultSerializer();
protected SegmentFactory segmentFactory = new ChannelSegmentFactory();
protected HashFunction<byte[]> hashFunction = new FnvHashFunction();

protected String path;

public KratiEndpoint(String uri, KratiComponent component) throws URISyntaxException {
super(uri, component);
this.path = getPath(uri);
}

@Override
public void stop() throws Exception {
super.stop();
KratiDataStoreRegistration registration = dataStoreRegistry.get(path);
if (registration != null) {
registration.unregister();
}
}

public Producer createProducer() throws Exception {
DataStore<Object, Object> dataStore = null;
KratiDataStoreRegistration registration = dataStoreRegistry.get(path);
if (registration != null) {
dataStore = registration.getDataStore();
}
if (dataStore == null || !dataStore.isOpen()) {
dataStore = KratiHelper.<Object, Object>createDataStore(path, initialCapacity, segmentFileSize, segmentFactory, hashFunction, keySerializer, valueSerializer);
dataStoreRegistry.put(path, new KratiDataStoreRegistration(dataStore));
}
return new KratiProducer(this, dataStore);
}

public Consumer createConsumer(Processor processor) throws Exception {
DataStore<Object, Object> dataStore = null;
KratiDataStoreRegistration registration = dataStoreRegistry.get(path);
if (registration != null) {
dataStore = registration.getDataStore();
}
if (dataStore == null || !dataStore.isOpen()) {
dataStore = KratiHelper.createDataStore(path, initialCapacity, segmentFileSize, segmentFactory, hashFunction, keySerializer, valueSerializer);
dataStoreRegistry.put(path, new KratiDataStoreRegistration(dataStore));
}
return new KratiConsumer(this, processor, dataStore);
}

public boolean isSingleton() {
return true;
}


/**
* Returns the path from the URI.
*
* @param uri
* @return
*/
protected String getPath(String uri) throws URISyntaxException {
URI u = new URI(uri);
StringBuilder pathBuilder = new StringBuilder();
if (u.getHost() != null) {
pathBuilder.append(u.getHost());
}
if (u.getPath() != null) {
pathBuilder.append(u.getPath());
}
return pathBuilder.toString();
}

public String getKey() {
return key;
}

public void setKey(String key) {
this.key = key;
}

public String getValue() {
return value;
}

public void setValue(String value) {
this.value = value;
}

public String getOperation() {
return operation;
}

public void setOperation(String operation) {
this.operation = operation;
}

public int getInitialCapacity() {
return initialCapacity;
}

public void setInitialCapacity(int initialCapacity) {
this.initialCapacity = initialCapacity;
}

public int getSegmentFileSize() {
return segmentFileSize;
}

public void setSegmentFileSize(int segmentFileSize) {
this.segmentFileSize = segmentFileSize;
}

public SegmentFactory getSegmentFactory() {
return segmentFactory;
}

public void setSegmentFactory(SegmentFactory segmentFactory) {
this.segmentFactory = segmentFactory;
}

public HashFunction<byte[]> getHashFunction() {
return hashFunction;
}

public void setHashFunction(HashFunction<byte[]> hashFunction) {
this.hashFunction = hashFunction;
}

public String getPath() {
return path;
}


}