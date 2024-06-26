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
package org.apache.camel.component.websocket;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.management.MBeanServer;

import org.apache.camel.Endpoint;
import org.apache.camel.RuntimeCamelException;
import org.apache.camel.impl.DefaultComponent;
import org.apache.camel.spi.ManagementAgent;
import org.apache.camel.spi.ManagementStrategy;
import org.apache.camel.util.ObjectHelper;
import org.apache.camel.util.jsse.SSLContextParameters;
import org.eclipse.jetty.http.ssl.SslContextFactory;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.DispatcherType;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SessionManager;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.server.session.HashSessionManager;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.server.ssl.SslConnector;
import org.eclipse.jetty.server.ssl.SslSelectChannelConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebsocketComponent extends DefaultComponent {

protected static final Logger LOG = LoggerFactory.getLogger(WebsocketComponent.class);
protected static final HashMap<String, ConnectorRef> CONNECTORS = new HashMap<String, ConnectorRef>();

protected SSLContextParameters sslContextParameters;
protected MBeanContainer mbContainer;
protected ThreadPool threadPool;

protected Integer port = 9292;
protected Integer minThreads;
protected Integer maxThreads;

protected boolean enableJmx;

protected String host = "0.0.0.0";
protected String staticResources;
protected Server staticResourcesServer;
protected String sslKeyPassword;
protected String sslPassword;
protected String sslKeystore;

/**
* Map for storing servlets. {@link WebsocketComponentServlet} is identified by pathSpec {@link String}.
*/
private Map<String, WebsocketComponentServlet> servlets = new HashMap<String, WebsocketComponentServlet>();

class ConnectorRef {
Server server;
Connector connector;
WebsocketComponentServlet servlet;
int refCount;

public ConnectorRef(Server server, Connector connector, WebsocketComponentServlet servlet) {
this.server = server;
this.connector = connector;
this.servlet = servlet;
increment();
}

public int increment() {
return ++refCount;
}

public int decrement() {
return --refCount;
}

public int getRefCount() {
return refCount;
}
}

public WebsocketComponent() {
}

/**
* Connects the URL specified on the endpoint to the specified processor.
*/
public void connect(WebsocketProducerConsumer prodcon) throws Exception {

Server server = null;
WebsocketEndpoint endpoint = prodcon.getEndpoint();

String connectorKey = getConnectorKey(endpoint);

synchronized (CONNECTORS) {
ConnectorRef connectorRef = CONNECTORS.get(connectorKey);
if (connectorRef == null) {
Connector connector;
if (endpoint.getSslContextParameters() != null) {
connector = getSslSocketConnector(endpoint.getSslContextParameters());
} else {
connector = new SelectChannelConnector();
}

LOG.trace("Jetty Connector added: {}", connector.getName());

if (endpoint.getPort() != null) {
connector.setPort(endpoint.getPort());
} else {
connector.setPort(port);
}

if (endpoint.getHost() != null) {
connector.setHost(endpoint.getHost());
} else {
connector.setHost(host);
}

// Create Server and add connector
server = createServer();
if (endpoint.isEnableJmx()) {
enableJmx(server);
}
server.addConnector(connector);

// Create ServletContextHandler
ServletContextHandler context = createContext(server, connector, endpoint.getHandlers());
server.setHandler(context);

// Apply CORS (http://www.w3.org/TR/cors/)
applyCrossOriginFiltering(endpoint, context);

// Create Static resources
if (endpoint.getStaticResources() != null) {
server = createStaticResourcesServer(server, context, endpoint.getStaticResources());
}

// Don't provide a Servlet object as Producer/Consumer will create them later on
connectorRef = new ConnectorRef(server, connector, null);

// must enable session before we start
if (endpoint.isSessionSupport()) {
enableSessionSupport(connectorRef.server, connectorKey);
}
LOG.info("Jetty Server starting on host: {}:{}", connector.getHost(), connector.getPort());
connectorRef.server.start();

CONNECTORS.put(connectorKey, connectorRef);

} else {
connectorRef.increment();
}

// check the session support
if (endpoint.isSessionSupport()) {
enableSessionSupport(connectorRef.server, connectorKey);
}

}

}

/**
* Disconnects the URL specified on the endpoint from the specified
* processor.
*/
public void disconnect(WebsocketProducerConsumer prodcon) throws Exception {
// If the connector is not needed anymore then stop it
WebsocketEndpoint endpoint = prodcon.getEndpoint();
String connectorKey = getConnectorKey(endpoint);

synchronized (CONNECTORS) {
ConnectorRef connectorRef = CONNECTORS.get(connectorKey);
if (connectorRef != null) {
if (connectorRef.decrement() == 0) {
connectorRef.server.removeConnector(connectorRef.connector);
if (connectorRef.connector != null) {
// static server may not have set a connector
connectorRef.connector.stop();
}
connectorRef.server.stop();
CONNECTORS.remove(connectorKey);
// Camel controls the lifecycle of these entities so remove the
// registered MBeans when Camel is done with the managed objects.
if (mbContainer != null) {
mbContainer.removeBean(connectorRef.server);
mbContainer.removeBean(connectorRef.connector);
}
}
if (prodcon instanceof WebsocketConsumer) {
connectorRef.servlet.disconnect((WebsocketConsumer) prodcon);
}
}
}
}

public synchronized MBeanContainer getMbContainer() {
// If null, provide the default implementation.
if (mbContainer == null) {
MBeanServer mbs = null;

final ManagementStrategy mStrategy = this.getCamelContext().getManagementStrategy();
final ManagementAgent mAgent = mStrategy.getManagementAgent();
if (mAgent != null) {
mbs = mAgent.getMBeanServer();
}

if (mbs != null) {
mbContainer = new MBeanContainer(mbs);
startMbContainer();
} else {
LOG.warn("JMX disabled in CamelContext. Jetty JMX extensions will remain disabled.");
}
}

return this.mbContainer;
}

@Override
protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {

SSLContextParameters sslContextParameters = resolveAndRemoveReferenceParameter(parameters, "sslContextParametersRef", SSLContextParameters.class);
Boolean enableJmx = getAndRemoveParameter(parameters, "enableJmx", Boolean.class);
String staticResources = getAndRemoveParameter(parameters, "staticResources", String.class);
int port = extractPortNumber(remaining);
String host = extractHostName(remaining);

WebsocketEndpoint endpoint = new WebsocketEndpoint(this, uri, remaining, parameters);

if (enableJmx != null) {
endpoint.setEnableJmx(enableJmx);
} else {
endpoint.setEnableJmx(isEnableJmx());
}

/*
if (sslContextParameters == null) {
sslContextParameters = this.sslContextParameters;
} */

// prefer to use endpoint configured over component configured
if (sslContextParameters == null) {
// fallback to component configured
sslContextParameters = getSslContextParameters();
}

if (sslContextParameters != null) {
endpoint.setSslContextParameters(sslContextParameters);
}

// prefer to use endpoint configured over component configured
if (staticResources == null) {
// fallback to component configured
staticResources = getStaticResources();
}

if (staticResources != null) {
endpoint.setStaticResources(staticResources);
}

endpoint.setSslContextParameters(sslContextParameters);
endpoint.setPort(port);
endpoint.setHost(host);

setProperties(endpoint, parameters);
return endpoint;
}

protected Server createServer() throws Exception {
Server server = new Server();
ContextHandlerCollection collection = new ContextHandlerCollection();
server.setHandler(collection);

// configure thread pool if min/max given
if (minThreads != null || maxThreads != null) {
if (getThreadPool() != null) {
throw new IllegalArgumentException("You cannot configure both minThreads/maxThreads and a custom threadPool on JettyHttpComponent: " + this);
}
QueuedThreadPool qtp = new QueuedThreadPool();
if (minThreads != null) {
qtp.setMinThreads(minThreads.intValue());
}
if (maxThreads != null) {
qtp.setMaxThreads(maxThreads.intValue());
}
// let the thread names indicate they are from the server
qtp.setName("CamelJettyWebSocketServer(" + ObjectHelper.getIdentityHashCode(server) + ")");
try {
qtp.start();
} catch (Exception e) {
throw new RuntimeCamelException("Error starting JettyWebSocketServer thread pool: " + qtp, e);
}
server.setThreadPool(qtp);
}

if (getThreadPool() != null) {
server.setThreadPool(getThreadPool());
}

return server;
}

protected Server createStaticResourcesServer(Server server, ServletContextHandler context, String home) throws Exception {

context.setContextPath("/");

SessionManager sm = new HashSessionManager();
SessionHandler sh = new SessionHandler(sm);
context.setSessionHandler(sh);

if (home != null) {
String[] resources = home.split(":");
if (LOG.isDebugEnabled()) {
LOG.debug(">>> Protocol found: " + resources[0] + ", and resource: " + resources[1]);
}

if (resources[0].equals("classpath")) {
context.setBaseResource(new JettyClassPathResource(getCamelContext().getClassResolver(), resources[1]));
} else if (resources[0].equals("file")) {
context.setBaseResource(Resource.newResource(resources[1]));
}
DefaultServlet defaultServlet = new DefaultServlet();
ServletHolder holder = new ServletHolder(defaultServlet);

// avoid file locking on windows
// http://stackoverflow.com/questions/184312/how-to-make-jetty-dynamically-load-static-pages
holder.setInitParameter("useFileMappedBuffer", "false");
context.addServlet(holder, "/");
}

server.setHandler(context);

return server;
}

protected Server createStaticResourcesServer(ServletContextHandler context, String host, int port, String home) throws Exception {
Server server = new Server();
Connector connector = new SelectChannelConnector();
connector.setHost(host);
connector.setPort(port);
server.addConnector(connector);
return createStaticResourcesServer(server, context, home);
}

protected WebsocketComponentServlet addServlet(NodeSynchronization sync, WebsocketProducer producer, String remaining) throws Exception {

// Get Connector from one of the Jetty Instances to add WebSocket Servlet
WebsocketEndpoint endpoint = producer.getEndpoint();
String key = getConnectorKey(endpoint);
ConnectorRef connectorRef = getConnectors().get(key);

WebsocketComponentServlet servlet;

if (connectorRef != null) {
String pathSpec = createPathSpec(remaining);
servlet = servlets.get(pathSpec);
if (servlet == null) {
// Retrieve Context
ServletContextHandler context = (ServletContextHandler) connectorRef.server.getHandler();
servlet = createServlet(sync, pathSpec, servlets, context);
connectorRef.servlet = servlet;
LOG.debug("WebSocket Producer Servlet added for the following path : " + pathSpec + ", to the Jetty Server : " + key);
}
return servlet;
} else {
throw new Exception("Jetty instance has not been retrieved for : " + key);
}
}

protected WebsocketComponentServlet addServlet(NodeSynchronization sync, WebsocketConsumer consumer, String remaining) throws Exception {

// Get Connector from one of the Jetty Instances to add WebSocket Servlet
WebsocketEndpoint endpoint = consumer.getEndpoint();
String key = getConnectorKey(endpoint);
ConnectorRef connectorRef = getConnectors().get(key);

WebsocketComponentServlet servlet;

if (connectorRef != null) {
String pathSpec = createPathSpec(remaining);
servlet = servlets.get(pathSpec);
if (servlet == null) {
// Retrieve Context
ServletContextHandler context = (ServletContextHandler) connectorRef.server.getHandler();
servlet = createServlet(sync, pathSpec, servlets, context);
connectorRef.servlet = servlet;
servlets.put(pathSpec, servlet);
LOG.debug("WebSocket servlet added for the following path : " + pathSpec + ", to the Jetty Server : " + key);
}

if (servlet.getConsumer() == null) {
servlet.setConsumer(consumer);
}
// register the consumer here
servlet.connect(consumer);
return servlet;
} else {
throw new Exception("Jetty instance has not been retrieved for : " + key);
}
}

protected WebsocketComponentServlet createServlet(NodeSynchronization sync, String pathSpec, Map<String, WebsocketComponentServlet> servlets, ServletContextHandler handler) {
WebsocketComponentServlet servlet = new WebsocketComponentServlet(sync);
servlets.put(pathSpec, servlet);
handler.addServlet(new ServletHolder(servlet), pathSpec);
return servlet;
}

protected ServletContextHandler createContext(Server server, Connector connector, List<Handler> handlers) throws Exception {
ServletContextHandler context = new ServletContextHandler(server, "/", ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
context.setConnectorNames(new String[]{connector.getName()});

if (handlers != null && !handlers.isEmpty()) {
for (Handler handler : handlers) {
if (handler instanceof HandlerWrapper) {
((HandlerWrapper) handler).setHandler(server.getHandler());
server.setHandler(handler);
} else {
HandlerCollection handlerCollection = new HandlerCollection();
handlerCollection.addHandler(server.getHandler());
handlerCollection.addHandler(handler);
server.setHandler(handlerCollection);
}
}
}

return context;
}

/**
* Starts {@link #mbContainer} and registers the container with itself as a managed bean
* logging an error if there is a problem starting the container.
* Does nothing if {@link #mbContainer} is {@code null}.
*/
protected void startMbContainer() {
if (mbContainer != null && !mbContainer.isStarted()) {
try {
mbContainer.start();
// Publish the container itself for consistency with
// traditional embedded Jetty configurations.
mbContainer.addBean(mbContainer);
} catch (Throwable e) {
LOG.warn("Could not start JettyWebSocket MBeanContainer. Jetty JMX extensions will remain disabled.", e);
}
}
}

private void enableSessionSupport(Server server, String connectorKey) throws Exception {
ServletContextHandler context = server.getChildHandlerByClass(ServletContextHandler.class);
if (context.getSessionHandler() == null) {
SessionHandler sessionHandler = new SessionHandler();
if (context.isStarted()) {
throw new IllegalStateException("Server has already been started. Cannot enabled sessionSupport on " + connectorKey);
} else {
context.setSessionHandler(sessionHandler);
}
}
}

private SslConnector getSslSocketConnector(SSLContextParameters sslContextParameters) throws Exception {
SslSelectChannelConnector sslSocketConnector = null;
if (sslContextParameters != null) {
SslContextFactory sslContextFactory = new WebSocketComponentSslContextFactory();
sslContextFactory.setSslContext(sslContextParameters.createSSLContext());
sslSocketConnector = new SslSelectChannelConnector(sslContextFactory);
} else {
sslSocketConnector = new SslSelectChannelConnector();
// with default null values, jetty ssl system properties
// and console will be read by jetty implementation
sslSocketConnector.getSslContextFactory().setKeyManagerPassword(sslPassword);
sslSocketConnector.getSslContextFactory().setKeyStorePassword(sslKeyPassword);
if (sslKeystore != null) {
sslSocketConnector.getSslContextFactory().setKeyStorePath(sslKeystore);
}

}
return sslSocketConnector;
}

/**
* Override the key/trust store check method as it does not account for a factory that has
* a pre-configured {@link javax.net.ssl.SSLContext}.
*/
private static final class WebSocketComponentSslContextFactory extends SslContextFactory {
// This method is for Jetty 7.0.x ~ 7.4.x
@SuppressWarnings("unused")
public boolean checkConfig() {
if (getSslContext() == null) {
return checkSSLContextFactoryConfig(this);
} else {
return true;
}
}

// This method is for Jetty 7.5.x
@Override
public void checkKeyStore() {
// here we don't check the SslContext as it is already created
}
}

private static boolean checkSSLContextFactoryConfig(Object instance) {
try {
Method method = instance.getClass().getMethod("checkConfig");
return (Boolean) method.invoke(instance);
} catch (NoSuchMethodException ex) {
// ignore
} catch (IllegalArgumentException e) {
// ignore
} catch (IllegalAccessException e) {
// ignore
} catch (InvocationTargetException e) {
// ignore
}
return false;
}

private static String createPathSpec(String remaining) {
// Is not correct as it does not support to add port in the URI
//return String.format("/%s/*", remaining);

int index = remaining.indexOf("/");
if (index != -1) {
return remaining.substring(index, remaining.length());
} else {
return "/" + remaining;
}
}

private int extractPortNumber(String remaining) {
int index1 = remaining.indexOf(":");
int index2 = remaining.indexOf("/");

if ((index1 != -1) && (index2 != -1)) {
String result = remaining.substring(index1 + 1, index2);
return Integer.parseInt(result);
} else {
return port;
}
}

private String extractHostName(String remaining) {
int index = remaining.indexOf(":");
if (index != -1) {
return remaining.substring(0, index);
} else {
return host;
}
}

private static String getConnectorKey(WebsocketEndpoint endpoint) {
return endpoint.getProtocol() + ":" + endpoint.getHost() + ":" + endpoint.getPort();
}

private void enableJmx(Server server) {
MBeanContainer containerToRegister = getMbContainer();
if (containerToRegister != null) {
LOG.info("Jetty JMX Extensions is enabled");
server.getContainer().addEventListener(containerToRegister);
// Since we may have many Servers running, don't tie the MBeanContainer
// to a Server lifecycle or we end up closing it while it is still in use.
//server.addBean(mbContainer);
}
}

private void applyCrossOriginFiltering(WebsocketEndpoint endpoint, ServletContextHandler context) {
if (endpoint.isCrossOriginFilterOn()) {
FilterHolder filterHolder = new FilterHolder();
CrossOriginFilter filter = new CrossOriginFilter();
filterHolder.setFilter(filter);
filterHolder.setInitParameter("allowedOrigins", endpoint.getAllowedOrigins());
context.addFilter(filterHolder, endpoint.getFilterPath(), EnumSet.allOf(DispatcherType.class));
}
}

// Properties
// -------------------------------------------------------------------------

public String getStaticResources() {
return staticResources;
}

/**
* Set a resource path for static resources (such as .html files etc).
* <p/>
* The resources can be loaded from classpath, if you prefix with <tt>classpath:</tt>,
* otherwise the resources is loaded from file system or from JAR files.
* <p/>
* For example to load from root classpath use <tt>classpath:.</tt>, or
* <tt>classpath:WEB-INF/static</tt>
* <p/>
* If not configured (eg <tt>null</tt>) then no static resource is in use.
*
* @param staticResources the base path
*/
public void setStaticResources(String staticResources) {
this.staticResources = staticResources;
}

public String getHost() {
return host;
}

public void setHost(String host) {
this.host = host;
}

public Integer getPort() {
return port;
}

public void setPort(Integer port) {
this.port = port;
}

public String getSslKeyPassword() {
return sslKeyPassword;
}

public String getSslPassword() {
return sslPassword;
}

public String getSslKeystore() {
return sslKeystore;
}

public void setSslKeyPassword(String sslKeyPassword) {
this.sslKeyPassword = sslKeyPassword;
}

public void setSslPassword(String sslPassword) {
this.sslPassword = sslPassword;
}

public void setSslKeystore(String sslKeystore) {
this.sslKeystore = sslKeystore;
}

public void setEnableJmx(boolean enableJmx) {
this.enableJmx = enableJmx;
}

public boolean isEnableJmx() {
return enableJmx;
}

public Integer getMinThreads() {
return minThreads;
}

public void setMinThreads(Integer minThreads) {
this.minThreads = minThreads;
}

public Integer getMaxThreads() {
return maxThreads;
}

public void setMaxThreads(Integer maxThreads) {
this.maxThreads = maxThreads;
}

public ThreadPool getThreadPool() {
return threadPool;
}

public void setThreadPool(ThreadPool threadPool) {
this.threadPool = threadPool;
}

public SSLContextParameters getSslContextParameters() {
return sslContextParameters;
}

public void setSslContextParameters(SSLContextParameters sslContextParameters) {
this.sslContextParameters = sslContextParameters;
}

public static HashMap<String, ConnectorRef> getConnectors() {
return CONNECTORS;
}

@Override
protected void doStart() throws Exception {
super.doStart();

if (staticResources != null) {
// host and port must be configured
ObjectHelper.notEmpty(host, "host", this);
ObjectHelper.notNull(port, "port", this);

LOG.info("Starting static resources server {}:{} with static resource: {}", new Object[]{host, port, staticResources});
ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
staticResourcesServer = createStaticResourcesServer(context, host, port, staticResources);
staticResourcesServer.start();
Connector connector = staticResourcesServer.getConnectors()[0];

// must add static resource server to CONNECTORS in case the websocket producers/consumers
// uses the same port number, and therefore we must be part of this
ConnectorRef ref = new ConnectorRef(staticResourcesServer, connector, null);
String key = "websocket:" + host + ":" + port;
CONNECTORS.put(key, ref);
}
}

@Override
public void doStop() throws Exception {
super.doStop();
if (CONNECTORS.size() > 0) {
for (String connectorKey : CONNECTORS.keySet()) {
ConnectorRef connectorRef = CONNECTORS.get(connectorKey);
if (connectorRef != null && connectorRef.getRefCount() == 0) {
connectorRef.server.removeConnector(connectorRef.connector);
connectorRef.connector.stop();
connectorRef.server.stop();
connectorRef.servlet = null;
}
CONNECTORS.remove(connectorKey);
}
}
CONNECTORS.clear();

if (staticResourcesServer != null) {
LOG.info("Stopping static resources server {}:{} with static resource: {}", new Object[]{host, port, staticResources});
staticResourcesServer.stop();
staticResourcesServer.destroy();
staticResourcesServer = null;
}

servlets.clear();
}
}