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
package org.apache.camel.component.file.remote;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.util.Map;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import org.apache.camel.util.IOHelper;
import org.apache.camel.util.IntrospectionSupport;
import org.apache.camel.util.jsse.SSLContextParameters;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPClientConfig;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPSClient;

/**
* FTP Secure (FTP over SSL/TLS) endpoint
*
* @version
*/
public class FtpsEndpoint extends FtpEndpoint<FTPFile> {

protected Map<String, Object> ftpClientKeyStoreParameters;
protected Map<String, Object> ftpClientTrustStoreParameters;
protected SSLContextParameters sslContextParameters;

public FtpsEndpoint() {
}

public FtpsEndpoint(String uri, RemoteFileComponent<FTPFile> remoteFileComponent, RemoteFileConfiguration configuration) {
super(uri, remoteFileComponent, configuration);
}

@Override
public String getScheme() {
return getFtpsConfiguration().getProtocol();
}

/**
* Create the FTPS client.
*/
protected FTPClient createFtpClient() throws Exception {
FTPSClient client = null;

if (sslContextParameters != null) {
SSLContext context = sslContextParameters.createSSLContext();

client = new FTPSClient(getFtpsConfiguration().isImplicit(), context);

// The FTPSClient tries to manage the following SSLSocket related configuration options
// on its own based on internal configuration options.  FTPSClient does not lend itself
// to subclassing for the purpose of overriding this behavior (private methods, fields, etc.).
// As such, we create a socket (preconfigured by SSLContextParameters) from the context
// we gave to FTPSClient and then setup FTPSClient to reuse the already configured configuration
// from the socket for all future sockets it creates.  Not sexy and a little brittle, but it works.
SSLSocket socket = (SSLSocket)context.getSocketFactory().createSocket();
client.setEnabledCipherSuites(socket.getEnabledCipherSuites());
client.setEnabledProtocols(socket.getEnabledProtocols());
client.setNeedClientAuth(socket.getNeedClientAuth());
client.setWantClientAuth(socket.getWantClientAuth());
client.setEnabledSessionCreation(socket.getEnableSessionCreation());
} else {
client = new FTPSClient(getFtpsConfiguration().getSecurityProtocol(),
getFtpsConfiguration().isImplicit());

if (ftpClientKeyStoreParameters != null) {
String type = (ftpClientKeyStoreParameters.containsKey("type"))
? (String) ftpClientKeyStoreParameters.get("type") : KeyStore.getDefaultType();
String file = (String) ftpClientKeyStoreParameters.get("file");
String password = (String) ftpClientKeyStoreParameters.get("password");
String algorithm = (ftpClientKeyStoreParameters.containsKey("algorithm"))
? (String) ftpClientKeyStoreParameters.get("algorithm")
: KeyManagerFactory.getDefaultAlgorithm();
String keyPassword = (String) ftpClientKeyStoreParameters.get("keyPassword");

KeyStore keyStore = KeyStore.getInstance(type);
FileInputStream keyStoreFileInputStream = new FileInputStream(new File(file));
try {
keyStore.load(keyStoreFileInputStream, password.toCharArray());
} finally {
IOHelper.close(keyStoreFileInputStream, "keyStore", log);
}

KeyManagerFactory keyMgrFactory = KeyManagerFactory.getInstance(algorithm);
keyMgrFactory.init(keyStore, keyPassword.toCharArray());
client.setNeedClientAuth(true);
client.setKeyManager(keyMgrFactory.getKeyManagers()[0]);
}

if (ftpClientTrustStoreParameters != null) {
String type = (ftpClientTrustStoreParameters.containsKey("type"))
? (String) ftpClientTrustStoreParameters.get("type") : KeyStore.getDefaultType();
String file = (String) ftpClientTrustStoreParameters.get("file");
String password = (String) ftpClientTrustStoreParameters.get("password");
String algorithm = (ftpClientTrustStoreParameters.containsKey("algorithm"))
? (String) ftpClientTrustStoreParameters.get("algorithm")
: TrustManagerFactory.getDefaultAlgorithm();

KeyStore trustStore = KeyStore.getInstance(type);
FileInputStream trustStoreFileInputStream = new FileInputStream(new File(file));
try {
trustStore.load(trustStoreFileInputStream, password.toCharArray());
} finally {
IOHelper.close(trustStoreFileInputStream, "trustStore", log);
}

TrustManagerFactory trustMgrFactory = TrustManagerFactory.getInstance(algorithm);
trustMgrFactory.init(trustStore);

client.setTrustManager(trustMgrFactory.getTrustManagers()[0]);
}
}

return client;
}

@Override
public RemoteFileOperations<FTPFile> createRemoteFileOperations() throws Exception {
// configure ftp client
FTPSClient client = getFtpsClient();

if (client == null) {
// must use a new client if not explicit configured to use a custom client
client = (FTPSClient) createFtpClient();
}

// set any endpoint configured timeouts
if (getConfiguration().getConnectTimeout() > -1) {
client.setConnectTimeout(getConfiguration().getConnectTimeout());
}
if (getConfiguration().getSoTimeout() > -1) {
soTimeout = getConfiguration().getSoTimeout();
}
dataTimeout = getConfiguration().getTimeout();

if (ftpClientParameters != null) {
// setting soTimeout has to be done later on FTPClient (after it has connected)
Object timeout = ftpClientParameters.remove("soTimeout");
if (timeout != null) {
soTimeout = getCamelContext().getTypeConverter().convertTo(int.class, timeout);
}
// and we want to keep data timeout so we can log it later
timeout = ftpClientParameters.remove("dataTimeout");
if (timeout != null) {
dataTimeout = getCamelContext().getTypeConverter().convertTo(int.class, dataTimeout);
}
IntrospectionSupport.setProperties(client, ftpClientParameters);
}

if (ftpClientConfigParameters != null) {
// client config is optional so create a new one if we have parameter for it
if (ftpClientConfig == null) {
ftpClientConfig = new FTPClientConfig();
}
IntrospectionSupport.setProperties(ftpClientConfig, ftpClientConfigParameters);
}

if (dataTimeout > 0) {
client.setDataTimeout(dataTimeout);
}

if (log.isDebugEnabled()) {
log.debug("Created FTPSClient [connectTimeout: {}, soTimeout: {}, dataTimeout: {}]: {}",
new Object[]{client.getConnectTimeout(), getSoTimeout(), dataTimeout, client});
}

FtpsOperations operations = new FtpsOperations(client, getFtpClientConfig());
operations.setEndpoint(this);
return operations;
}

/**
* Returns the FTPSClient. This method exists only for convenient.
*
* @return ftpsClient
*/
public FTPSClient getFtpsClient() {
return (FTPSClient) getFtpClient();
}

/**
* Returns the FtpsConfiguration. This method exists only for convenient.
*
* @return ftpsConfiguration
*/
public FtpsConfiguration getFtpsConfiguration() {
return (FtpsConfiguration) getConfiguration();
}

/**
* Set the key store parameters
*/
public void setFtpClientKeyStoreParameters(Map<String, Object> param) {
this.ftpClientKeyStoreParameters = param;
}

/**
* Set the trust store parameters
*/
public void setFtpClientTrustStoreParameters(Map<String, Object> param) {
this.ftpClientTrustStoreParameters = param;
}

/**
* Gets the JSSE configuration that overrides any settings in {@link FtpsEndpoint#ftpClientKeyStoreParameters},
* {@link #ftpClientTrustStoreParameters}, and {@link FtpsConfiguration#getSecurityProtocol()}.
*/
public SSLContextParameters getSslContextParameters() {
return sslContextParameters;
}

/**
* Gets the JSSE configuration that overrides any settings in {@link FtpsEndpoint#ftpClientKeyStoreParameters},
* {@link #ftpClientTrustStoreParameters}, and {@link FtpsConfiguration#getSecurityProtocol()}.
*/
public void setSslContextParameters(SSLContextParameters sslContextParameters) {
this.sslContextParameters = sslContextParameters;
}

}