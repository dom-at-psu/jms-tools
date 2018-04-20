package edu.psu.activemq;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Queue;
import javax.jms.QueueBrowser;
import javax.jms.Session;

import org.apache.activemq.ActiveMQConnectionFactory;

import edu.psu.activemq.util.PropertyUtil;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
public class MessageHandler {

  public static final int MIN_THRESHOLD = 3;
  public static final int MIN_RECHECK_PERIOD = 250;

  public static final String BROKER_URL_PROP_NAME = "broker.url";
  public static final String TRANSPORT_NAME_PROP_NAME = "queue.name";
  public static final String BROKER_USERNAME_PROP_NAME = "broker.username";
  public static final String BROKER_PASSWORD_PROP_NAME = "broker.password";
  public static final String REQUEST_RETRY_THRESHOLD = "broker.retry.threshold";
  public static final String ERROR_TRANSPORT_NAME_PROP_NAME = "error.transport.name";
  public static final String ERROR_TRANSPORT_TYPE_PROP_NAME = "error.transport.type";

  public static final String MESSAGE_QUEUE_OR_TOPIC_ONLY = "If provided, the error transport type parameter must be set to either QUEUE or TOPIC";
  public static final String MESSAGE_NO_VALUE_FOR_REQUIRED_PROPERTY = "Broker url, queue name, username and password are required configuration properties with no defaults";
  
  @Getter(value=AccessLevel.NONE)
  @Setter(value=AccessLevel.NONE)
  List<MessageProcessor> handlerList = new ArrayList<>();

  @Getter(value=AccessLevel.NONE)
  @Setter(value=AccessLevel.NONE)
  Class<? extends MessageProcessor> clazz;

  @Getter(value=AccessLevel.NONE)
  @Setter(value=AccessLevel.NONE)
  Constructor<? extends MessageProcessor> constructor;
  
  @Getter(value=AccessLevel.NONE)
  @Setter(value=AccessLevel.NONE)
  Thread monitorThread;
  
  boolean loadFromProperties = false;
  String brokerUrl;
  String transportName;  
  String errorTransportName;
  String username;
  String password;
  TransportType errorTransportType = TransportType.QUEUE;
  int requestRetryThreshold;
  
  int messageThreshold = 10;
  int recheckPeriod = 3000;
  int cores;

  public MessageHandler(Class<? extends MessageProcessor> clazz) throws NoSuchMethodException, SecurityException, InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
    constructor = clazz.getConstructor();            
  }

  public void init(){
    log.trace("init()");

    try {
      if(loadFromProperties){
        parseConfigurationFromProperties();
      }
      validateConfiguration();
      
      if(log.isInfoEnabled()) {
        log.info("Broker URL: {}", brokerUrl);
        log.info("Queue name: {}", transportName);
        log.info("User name: {}", username);
        log.info("Password: ************");
      }

      cores = Runtime.getRuntime().availableProcessors();
      log.trace("Calling start monitor");    
      startMonitor();
    } catch (IllegalArgumentException e) {
      log.error(e.getMessage());
    }
  }
  
  public void setMessageThreshold(int threshold) throws IllegalStateException {
    if (threshold <= MIN_THRESHOLD) {
      throw new IllegalStateException("Threshold must be greater that 3");
    }

    messageThreshold = threshold;
  }

  public void setRecheckPeriod(int millis) throws IllegalStateException {
    if (millis <= MIN_RECHECK_PERIOD) {
      throw new IllegalStateException("Recheck period must be at least 250 milliseconds");
    }

    recheckPeriod = millis;
  }

  public void terminate() {
    for (MessageProcessor mp : handlerList) {
      mp.terminate();
    }
  }

  void parseConfigurationFromProperties() {
    brokerUrl = PropertyUtil.getProperty(BROKER_URL_PROP_NAME);
    transportName = PropertyUtil.getProperty(TRANSPORT_NAME_PROP_NAME);
    errorTransportName = PropertyUtil.getProperty(ERROR_TRANSPORT_NAME_PROP_NAME);
    username = PropertyUtil.getProperty(BROKER_USERNAME_PROP_NAME);
    password = PropertyUtil.getProperty(BROKER_PASSWORD_PROP_NAME);
    if(errorTransportName != null && !errorTransportName.isEmpty()) {
      try {
        errorTransportType = TransportType.valueOf(PropertyUtil.getProperty(ERROR_TRANSPORT_TYPE_PROP_NAME));
      } catch (NullPointerException e) {
        errorTransportType = TransportType.QUEUE;
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(MESSAGE_QUEUE_OR_TOPIC_ONLY);
      }
    }
    String retryThreshold = PropertyUtil.getProperty(REQUEST_RETRY_THRESHOLD);
    if(retryThreshold == null || retryThreshold.isEmpty()) {
      log.warn("Broker retry threshold was not supplied - defaulting to 3");
    }
    requestRetryThreshold = retryThreshold == null ? 3 : Integer.parseInt(retryThreshold);
  }

  void validateConfiguration() {
    if(isNullOrEmpty(brokerUrl) || isNullOrEmpty(transportName) || isNullOrEmpty(username) || isNullOrEmpty(password)) {
      throw new IllegalArgumentException(MESSAGE_NO_VALUE_FOR_REQUIRED_PROPERTY);
    }
  }

  boolean isNullOrEmpty(String value) {
    return value == null || value.isEmpty();
  }

  private void startMonitor() {
    log.info("Starting the monitor");

    try {
      log.trace("Creating a connection");
      Connection connection = buildActivemqConnection(brokerUrl, username, password);
      log.trace("Starting the connection");
      connection.start();
      log.trace("Creating a session");
      Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

      Queue destination = session.createQueue(transportName);

      QueueBrowser browser;
      try {
        browser = session.createBrowser(destination);
      } catch (JMSException e1) {
        throw new RuntimeException("Boom");
      }

      @SuppressWarnings("unchecked")
      int startingMessageCount = Collections.list(browser.getEnumeration())
                                            .size();

      while (true) {
        @SuppressWarnings("unchecked")
        int msgCount = Collections.list(browser.getEnumeration())
                                  .size();
        log.info("Processed: " + (startingMessageCount - msgCount));
        startingMessageCount = msgCount;
        try {
          log.trace("Checking thresholds, count = " + msgCount + " threshold = " + messageThreshold);
          if (msgCount > messageThreshold && handlerList.size() < cores) {
            log.trace("Constructing a new Message Processor");
            handlerList.add(buildNewMessageProcessor());
            log.trace("############# Now " + handlerList.size() + " processors");
          } else {
            if (handlerList.size() > 1) {
              log.trace("Removing a Message Processor");
              MessageProcessor mp = handlerList.remove(handlerList.size() - 1);
              mp.terminate();
              log.trace("############# Now " + handlerList.size() + " processors");
            }
          }

          for (int i = 0; i < handlerList.size(); i++) {
            MessageProcessor mp = handlerList.get(i);
            if (mp.isStopped()) {
              log.trace("Removing stopped processor");
              handlerList.remove(i);
            }
          }

          if (handlerList.isEmpty()) {
            log.trace("Seeding the processing pool with a single instance");
            try {
              handlerList.add(buildNewMessageProcessor());
            } catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e1) {
              log.error("Error building new message processor: " + e1.getMessage(), e1);
            }
          }
          
          Thread.sleep(recheckPeriod);
        } catch (InterruptedException | InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
          log.error("Error in message handler", e);
        }
      }
    } catch (JMSException e) {
      log.error("Error connecting to queue", e);
      // try reconnecting after 30 seconds
      try {
        Thread.sleep(30000L);
      } catch (InterruptedException ie) {
        log.warn(ie.toString());
      }
      
      this.startMonitor();
    }
      
  }

  private MessageProcessor buildNewMessageProcessor() throws InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
    MessageProcessor mp = constructor.newInstance();
    mp.setBrokerUrl(brokerUrl);
    mp.setErrorTransportName(errorTransportName);
    mp.setErrorTransportType(errorTransportType);
    mp.setTransportName(transportName);
    mp.setUsername(username);
    mp.setPassword(password);
    mp.setRequestRetryThreshold(requestRetryThreshold);
    
    mp.initialize();
    return mp;
  }
  
  protected static Connection buildActivemqConnection(String url, String username, String password) throws JMSException{
    ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(url);
    if(username != null){
      factory.setUserName(username);
    }
    if(password != null){
      factory.setPassword(password);
    }
    
    return factory.createConnection();
  }
}
