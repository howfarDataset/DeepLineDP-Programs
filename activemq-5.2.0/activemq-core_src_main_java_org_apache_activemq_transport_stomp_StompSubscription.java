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
package org.apache.activemq.transport.stomp;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import javax.jms.JMSException;

import org.apache.activemq.command.ActiveMQDestination;
import org.apache.activemq.command.ActiveMQMessage;
import org.apache.activemq.command.ConsumerInfo;
import org.apache.activemq.command.MessageAck;
import org.apache.activemq.command.MessageDispatch;
import org.apache.activemq.command.MessageId;

/**
* Keeps track of the STOMP susbscription so that acking is correctly done.
*
* @author <a href="http://hiramchirino.com">chirino</a>
*/
public class StompSubscription {

public static final String AUTO_ACK = Stomp.Headers.Subscribe.AckModeValues.AUTO;
public static final String CLIENT_ACK = Stomp.Headers.Subscribe.AckModeValues.CLIENT;
public static final String INDIVIDUAL_ACK = Stomp.Headers.Subscribe.AckModeValues.INDIVIDUAL;

private final ProtocolConverter protocolConverter;
private final String subscriptionId;
private final ConsumerInfo consumerInfo;

private final LinkedHashMap<String, MessageId> dispatchedMessage = new LinkedHashMap<String, MessageId>();

private String ackMode = AUTO_ACK;
private ActiveMQDestination destination;
private String transformation;

public StompSubscription(ProtocolConverter stompTransport, String subscriptionId, ConsumerInfo consumerInfo, String transformation) {
this.protocolConverter = stompTransport;
this.subscriptionId = subscriptionId;
this.consumerInfo = consumerInfo;
this.transformation = transformation;
}

void onMessageDispatch(MessageDispatch md) throws IOException, JMSException {

ActiveMQMessage message = (ActiveMQMessage)md.getMessage();

if (ackMode == CLIENT_ACK) {
synchronized (this) {
dispatchedMessage.put(message.getJMSMessageID(), message.getMessageId());
}
} else if (ackMode == INDIVIDUAL_ACK) {
synchronized (this) {
dispatchedMessage.put(message.getJMSMessageID(), message.getMessageId());
}
} else if (ackMode == AUTO_ACK) {
MessageAck ack = new MessageAck(md, MessageAck.STANDARD_ACK_TYPE, 1);
protocolConverter.getTransportFilter().sendToActiveMQ(ack);
}

boolean ignoreTransformation = false;

if (transformation != null) {
message.setReadOnlyProperties(false);
message.setStringProperty(Stomp.Headers.TRANSFORMATION, transformation);
} else {
if (message.getStringProperty(Stomp.Headers.TRANSFORMATION) != null) {
ignoreTransformation = true;
}
}
StompFrame command = protocolConverter.convertMessage(message, ignoreTransformation);

command.setAction(Stomp.Responses.MESSAGE);
if (subscriptionId != null) {
command.getHeaders().put(Stomp.Headers.Message.SUBSCRIPTION, subscriptionId);
}

protocolConverter.getTransportFilter().sendToStomp(command);
}

synchronized MessageAck onStompMessageAck(String messageId) {

if (!dispatchedMessage.containsKey(messageId)) {
return null;
}

MessageAck ack = new MessageAck();
ack.setDestination(consumerInfo.getDestination());
ack.setConsumerId(consumerInfo.getConsumerId());

if (ackMode == CLIENT_ACK) {
ack.setAckType(MessageAck.STANDARD_ACK_TYPE);
int count = 0;
for (Iterator iter = dispatchedMessage.entrySet().iterator(); iter.hasNext();) {

Map.Entry entry = (Entry)iter.next();
String id = (String)entry.getKey();
MessageId msgid = (MessageId)entry.getValue();

if (ack.getFirstMessageId() == null) {
ack.setFirstMessageId(msgid);
}

iter.remove();
count++;

if (id.equals(messageId)) {
ack.setLastMessageId(msgid);
break;
}

}
ack.setMessageCount(count);
}
else if (ackMode == INDIVIDUAL_ACK) {
ack.setAckType(MessageAck.INDIVIDUAL_ACK_TYPE);
MessageId msgid = (MessageId)dispatchedMessage.get(messageId);
ack.setMessageID(msgid);
dispatchedMessage.remove(messageId);
}
return ack;
}

public String getAckMode() {
return ackMode;
}

public void setAckMode(String ackMode) {
this.ackMode = ackMode;
}

public String getSubscriptionId() {
return subscriptionId;
}

public void setDestination(ActiveMQDestination destination) {
this.destination = destination;
}

public ActiveMQDestination getDestination() {
return destination;
}

public ConsumerInfo getConsumerInfo() {
return consumerInfo;
}

}