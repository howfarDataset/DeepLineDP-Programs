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
package org.apache.camel.component.rmi;

import java.net.URI;
import java.net.URISyntaxException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.Arrays;
import java.util.List;

import org.apache.camel.Consumer;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.impl.DefaultEndpoint;
import org.apache.camel.util.ObjectHelper;

/**
* @version
*/
public class RmiEndpoint extends DefaultEndpoint {

private List<Class<?>> remoteInterfaces;
private ClassLoader classLoader;
private URI uri;
private int port;
private String method;

public RmiEndpoint() {
}

protected RmiEndpoint(String endpointUri, RmiComponent component) throws URISyntaxException {
super(endpointUri, component);
this.uri = new URI(endpointUri);
}

@Deprecated
public RmiEndpoint(String endpointUri) throws URISyntaxException {
super(endpointUri);
this.uri = new URI(endpointUri);
}

public boolean isSingleton() {
return false;
}

@Override
protected String createEndpointUri() {
return uri.toString();
}

public Consumer createConsumer(Processor processor) {
ObjectHelper.notNull(uri, "uri");
if (remoteInterfaces == null || remoteInterfaces.size() == 0) {
throw new IllegalArgumentException("To create a RMI consumer, the RMI endpoint's remoteInterfaces property must be be configured.");
}
return new RmiConsumer(this, processor);
}

public Producer createProducer() throws Exception {
ObjectHelper.notNull(uri, "uri");
return new RmiProducer(this);
}

public String getName() {
String path = uri.getPath();
if (path == null) {
path = uri.getSchemeSpecificPart();
}
// skip leading slash
if (path.startsWith("/")) {
return path.substring(1);
}
return path;
}

public Registry getRegistry() throws RemoteException {
if (uri.getHost() != null) {
if (uri.getPort() == -1) {
return LocateRegistry.getRegistry(uri.getHost());
} else {
return LocateRegistry.getRegistry(uri.getHost(), uri.getPort());
}
} else {
return LocateRegistry.getRegistry();
}
}

public List<Class<?>> getRemoteInterfaces() {
return remoteInterfaces;
}

public void setRemoteInterfaces(List<Class<?>> remoteInterfaces) {
this.remoteInterfaces = remoteInterfaces;
if (classLoader == null && !remoteInterfaces.isEmpty()) {
classLoader = remoteInterfaces.get(0).getClassLoader();
}
}

public void setRemoteInterfaces(Class<?>... remoteInterfaces) {
setRemoteInterfaces(Arrays.asList(remoteInterfaces));
}

public ClassLoader getClassLoader() {
return classLoader;
}

public void setClassLoader(ClassLoader classLoader) {
this.classLoader = classLoader;
}

public int getPort() {
return port;
}

public void setPort(int port) {
this.port = port;
}

public String getMethod() {
return method;
}

public void setMethod(String method) {
this.method = method;
}

public URI getUri() {
return uri;
}

public void setUri(URI uri) {
this.uri = uri;
}
}