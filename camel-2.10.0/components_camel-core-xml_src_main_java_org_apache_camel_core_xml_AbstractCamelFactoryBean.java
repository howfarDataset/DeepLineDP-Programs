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
package org.apache.camel.core.xml;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlTransient;

import org.apache.camel.CamelContext;
import org.apache.camel.CamelContextAware;
import org.apache.camel.model.IdentifiedType;

@XmlAccessorType(XmlAccessType.FIELD)
public abstract class AbstractCamelFactoryBean<T> extends IdentifiedType implements CamelContextAware {

@XmlAttribute
private String camelContextId;
@XmlTransient
private CamelContext camelContext;

public abstract T getObject() throws Exception;

protected abstract CamelContext getCamelContextWithId(String camelContextId);

/**
* If no explicit camelContext or camelContextId has been set
* then try to discover a default {@link CamelContext} to use.
*/
protected CamelContext discoverDefaultCamelContext() {
return null;
}

public void afterPropertiesSet() throws Exception {
}

public void destroy() throws Exception {
}

public CamelContext getCamelContext() {
if (camelContext == null && camelContextId != null) {
camelContext = getCamelContextWithId(camelContextId);
if (camelContext == null) {
throw new IllegalStateException("Cannot find CamelContext with id: " + camelContextId);
}
}
if (camelContext == null) {
camelContext = discoverDefaultCamelContext();
}
return camelContext;
}

public void setCamelContext(CamelContext camelContext) {
this.camelContext = camelContext;
}

public String getCamelContextId() {
return camelContextId;
}

public void setCamelContextId(String camelContextId) {
this.camelContextId = camelContextId;
}

public boolean isSingleton() {
return true;
}

public abstract Class<? extends T> getObjectType();

}