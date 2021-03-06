/*******************************************************************************
 * Copyright (C) 2014, International Business Machines Corporation
 * All Rights Reserved
 *******************************************************************************/

/* Generated by Streams Studio: 28 February, 2014 12:15:29 PM EST */
package com.ibm.streamsx.messaging.mqtt;


import java.io.IOException;
import java.io.InputStream;
import java.lang.Thread.State;
import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;

import org.apache.log4j.Logger;
import org.eclipse.paho.client.mqttv3.MqttException;

import com.ibm.streams.operator.Attribute;
import com.ibm.streams.operator.OperatorContext;
import com.ibm.streams.operator.OperatorContext.ContextCheck;
import com.ibm.streams.operator.OutputTuple;
import com.ibm.streams.operator.StreamSchema;
import com.ibm.streams.operator.StreamingData.Punctuation;
import com.ibm.streams.operator.StreamingInput;
import com.ibm.streams.operator.StreamingOutput;
import com.ibm.streams.operator.Tuple;
import com.ibm.streams.operator.Type;
import com.ibm.streams.operator.Type.MetaType;
import com.ibm.streams.operator.compile.OperatorContextChecker;
import com.ibm.streams.operator.log4j.LoggerNames;
import com.ibm.streams.operator.log4j.TraceLevel;
import com.ibm.streams.operator.model.Icons;
import com.ibm.streams.operator.model.InputPortSet;
import com.ibm.streams.operator.model.InputPortSet.WindowMode;
import com.ibm.streams.operator.model.InputPortSet.WindowPunctuationInputMode;
import com.ibm.streams.operator.model.InputPorts;
import com.ibm.streams.operator.model.Libraries;
import com.ibm.streams.operator.model.OutputPortSet;
import com.ibm.streams.operator.model.OutputPortSet.WindowPunctuationOutputMode;
import com.ibm.streams.operator.model.OutputPorts;
import com.ibm.streams.operator.model.Parameter;
import com.ibm.streams.operator.model.PrimitiveOperator;
import com.ibm.streams.operator.state.Checkpoint;
import com.ibm.streams.operator.state.ConsistentRegionContext;
import com.ibm.streams.operator.state.StateHandler;
import com.ibm.streams.operator.types.Blob;
import com.ibm.streams.operator.types.RString;
import com.ibm.streamsx.messaging.common.DataGovernanceUtil;
import com.ibm.streamsx.messaging.common.IGovernanceConstants;
import com.ibm.streamsx.messaging.common.PropertyProvider;
import com.ibm.streamsx.messaging.mqtt.Messages;

/**
 * Class for an operator that consumes tuples and does not produce an output stream. 
 * This pattern supports a number of input streams and no output streams. 
 * <P>
 * The following event methods from the Operator interface can be called:
 * </p> 
 * <ul>
 * <li><code>initialize()</code> to perform operator initialization</li>
 * <li>allPortsReady() notification indicates the operator's ports are ready to process and submit tuples</li> 
 * <li>process() handles a tuple arriving on an input port 
 * <li>processPuncuation() handles a punctuation mark arriving on an input port 
 * <li>shutdown() to shutdown the operator. A shutdown request may occur at any time, 
 * such as a request to stop a PE or cancel a job. 
 * Thus the shutdown() may occur while the operator is processing tuples, punctuation marks, 
 * or even during port ready notification.</li>
 * </ul>
 * <p>With the exception of operator initialization, all the other events may occur concurrently with each other, 
 * which lead to these methods being called concurrently by different threads.</p> 
 */
@PrimitiveOperator(name="MQTTSink", namespace="com.ibm.streamsx.messaging.mqtt",
description=SPLDocConstants.MQTTSINK_OP_DESCRIPTION) 
@InputPorts({
		@InputPortSet(description = SPLDocConstants.MQTTSINK_INPUTPORT0, cardinality = 1, optional = false, windowingMode = WindowMode.NonWindowed, windowPunctuationInputMode = WindowPunctuationInputMode.Oblivious),
		@InputPortSet(description = SPLDocConstants.MQTTSINK_INPUTPORT1, optional = true, windowingMode = WindowMode.NonWindowed, windowPunctuationInputMode = WindowPunctuationInputMode.Oblivious) })
@OutputPorts({
		@OutputPortSet(description = SPLDocConstants.MQTTSINK_OUTPUT_PORT0, cardinality = 1, optional = true, windowPunctuationOutputMode = WindowPunctuationOutputMode.Free) })
@Libraries(value = {"opt/downloaded/*"} )
@Icons(location16="icons/MQTTSink_16.gif", location32="icons/MQTTSink_32.gif")
public class MqttSinkOperator extends AbstractMqttOperator implements StateHandler{
	 
	private static final String CLASS_NAME = "com.ibm.streamsx.messaging.mqtt.MqttSinkOperator"; //$NON-NLS-1$
	static Logger TRACE = Logger.getLogger(MqttSinkOperator.class);
	static Logger LOGGER = Logger.getLogger(LoggerNames.LOG_FACILITY + "." + CLASS_NAME); //$NON-NLS-1$
	
	// Parameters
	private String topic;
	private int qos = 0;
	private int reconnectionBound = IMqttConstants.DEFAULT_RECONNECTION_BOUND;		// default 5, 0 = no retry, -1 = infinite retry
	private long period = IMqttConstants.DEFAULT_RECONNECTION_PERIOD;
	private boolean retain = false;
	private String topicAttributeName;
	private String qosAttributeName;
	private MqttClientWrapper mqttWrapper;
	
	private ArrayBlockingQueue<Tuple> tupleQueue;
	private boolean shutdown;
	
	private ConsistentRegionContext crContext;
	
	private Object drainLock = new Object();

	private Thread publishThread;
	
	private InitialState initState;
	
	private boolean isRelaunching;
	
	private class InitialState {
		String initialServerUri;
		
		private InitialState() {
			initialServerUri = getServerUri();
		}
	}
	
	private class PublishRunnable implements Runnable {

		@Override
		public void run() {
			
			StreamSchema streamSchema = getInput(0).getStreamSchema();
			String dataAttributeName = getDataAttributeName() == null ? IMqttConstants.MQTT_DEFAULT_DATA_ATTRIBUTE_NAME : getDataAttributeName();
			
			int dataAttrIndex = streamSchema.getAttributeIndex(dataAttributeName);
			
			// if neither dataAttributeName is specified or schema attribute named "data" can be found
			// then it is assumed this schema contains only a single attribute and it is the data attribute
			if(dataAttrIndex == -1) {
				dataAttrIndex = 0;
			}
			
			Type.MetaType dataAttributeType = streamSchema.getAttribute(dataAttrIndex).getType().getMetaType();
			
			boolean isBlob = false;
			if(dataAttributeType.equals(MetaType.BLOB))
				isBlob = true;
			else if (dataAttributeType.equals(MetaType.RSTRING))
				isBlob = false;
			
			while (!shutdown)
			{
				// publish tuple in the background thread
				// max 50 tuples in flight
				try {
					
					Tuple tuple = tupleQueue.take();	
					
					String pubTopic = topic;
					int msgQos = qos;
					
					if (topicAttributeName != null)
					{
						pubTopic = tuple.getString(topicAttributeName);
					}
					
					if (qosAttributeName != null)
					{
						msgQos = tuple.getInt(qosAttributeName);
					}
					
					// disconnect if we have received a control signal
					if (!mqttWrapper.getPendingBrokerUri().isEmpty())
					{
						mqttWrapper.disconnect();
					}
					
					// if connected, go straight to publishing
					if (mqttWrapper.isConnected())
					{
						// inline this block of code instead of method call
						// to avoid unnecessary method call overhead
						if (pubTopic != null && pubTopic.length() > 0
							&& msgQos >= 0 && msgQos < 3){
							byte[] byteArray;
							if(isBlob) {
								Blob blockMsg = tuple.getBlob(dataAttrIndex);
						        InputStream inputStream = blockMsg.getInputStream();
						        int length = (int) blockMsg.getLength();
						        byteArray = new byte[length];
						        inputStream.read(byteArray, 0, length);
							}
							else {
								RString rstringObj = (RString)tuple.getObject(dataAttrIndex);
								byteArray = rstringObj.getData();
							}
							
					        mqttWrapper.publish(pubTopic, msgQos, byteArray, retain);
						}
						else
						{
							String errorMsg = Messages.getString("TOPIC_OR_QOS_INVALID", pubTopic, msgQos); //$NON-NLS-1$
							TRACE.log(TraceLevel.ERROR, errorMsg); 
							submitToErrorPort(errorMsg, crContext);
						}
					}
					else
					{
						// if not connected, connect before publishing
						boolean connected = validateConnection();
						
						while (!connected && mqttWrapper.isUriChanged(mqttWrapper.getBrokerUri()))
						{
							connected = validateConnection();
						}
						
						if (!connected)
						{
							String errorMsg = Messages.getString("UNABLE_TO_CONNECT_TO_SERVER_WITH_URI", getServerUri()); //$NON-NLS-1$
							submitToErrorPort(errorMsg, crContext);
							throw new RuntimeException(errorMsg); 
						}
						
						// inline this block of code instead of method call
						// to avoid unnecessary method call overhead
						if (pubTopic != null && pubTopic.length() > 0
								&& msgQos >= 0 && msgQos < 3){
							byte[] byteArray;
							if(isBlob) {
								Blob blockMsg = tuple.getBlob(dataAttrIndex);
						        InputStream inputStream = blockMsg.getInputStream();
						        int length = (int) blockMsg.getLength();
						        byteArray = new byte[length];
						        inputStream.read(byteArray, 0, length);
							}
							else {
								RString rstringObj = (RString)tuple.getObject(dataAttrIndex);
								byteArray = rstringObj.getData();
							}
					        
					        mqttWrapper.publish(pubTopic, msgQos, byteArray, retain);
						}
						else
						{
							String errorMsg = Messages.getString("TOPIC_OR_QOS_INVALID", pubTopic, msgQos); //$NON-NLS-1$
							TRACE.log(TraceLevel.ERROR, errorMsg); //$NON-NLS-1$
							submitToErrorPort(errorMsg, crContext);
						}
					}
				} 
				catch (MqttClientConnectException e)
				{
					// we should exit if we get a connect exception
					if (e instanceof MqttClientConnectException)
					{
						throw new RuntimeException(e);
					}
				}				
				catch (Exception e) {
					// do not rethrow exception, log and keep going
					if(e instanceof MqttException && ((MqttException) e).getReasonCode() == MqttException.REASON_CODE_CLIENT_TIMEOUT ) {
						// defected a command timeout
						TRACE.log(TraceLevel.WARN, Messages.getString("TIMED_OUT_WAITING_FOR_SERVER_RESPONSE")); //$NON-NLS-1$
					}
					else {
						String errorMsg = Messages.getString("UNABLE_TO_PUBLISH_MSG"); //$NON-NLS-1$
						TRACE.log(TraceLevel.ERROR, errorMsg, e); 
						submitToErrorPort(errorMsg, crContext);
					}
					
				}
				finally {
                    if(crContext != null) {
						
						// if internal buffer has been cleared, notify waiting thread.
						if(tupleQueue.peek() == null) {
							synchronized(drainLock) {
								drainLock.notifyAll();
							}
						}
					}
				}
			}			
		}
		
		private boolean validateConnection() throws MqttClientConnectException{
			if (!mqttWrapper.isConnected())
			{
				try {
					if (!mqttWrapper.getPendingBrokerUri().isEmpty())
					{
						mqttWrapper.setBrokerUri(mqttWrapper.getPendingBrokerUri());
						
						// need to update parameter value too
						setServerUri(mqttWrapper.getPendingBrokerUri());
					}
					
					mqttWrapper.connect(getReconnectionBound(), getPeriod());
				} catch (URISyntaxException e) {
					String errorMsg = Messages.getString(Messages.getString("UNABLE_TO_CONNECT_TO_SERVER")); //$NON-NLS-1$
					TRACE.log(TraceLevel.ERROR, errorMsg, e);
					submitToErrorPort(errorMsg, crContext);	
					throw new RuntimeException(e);
				} catch (Exception e) {
					String errorMsg = Messages.getString(Messages.getString("UNABLE_TO_CONNECT_TO_SERVER")); //$NON-NLS-1$
					TRACE.log(TraceLevel.ERROR, errorMsg, e);
					submitToErrorPort(errorMsg, crContext);			
					
					if (e instanceof MqttClientConnectException)
					{
						throw (MqttClientConnectException)e;
					}
				} 
			}
			
			return mqttWrapper.isConnected();
		}		
	}
	
	@ContextCheck(compile=true)
	public static void checkConsistentRegion(OperatorContextChecker checker) {
		
		// check if this operator is placed at start of a consistent region
		OperatorContext oContext = checker.getOperatorContext();
		ConsistentRegionContext cContext = oContext.getOptionalContext(ConsistentRegionContext.class);
		
		if(cContext != null) {
			List<StreamingInput<Tuple>> inputPorts = checker.getOperatorContext().getStreamingInputs();
			
			// if there is a control port, a warning message is issued as control port is not supported in a consistent region
			if(inputPorts.size() > 1) {
				LOGGER.warn(Messages.getString("CTRL_PORT_IN_CONSISTENT_REGION_NOT_SUPPORTED")); //$NON-NLS-1$
			}
			
			if(cContext.isStartOfRegion()) {
				checker.setInvalidContext(Messages.getString("OP_CANNOT_BE_START_OF_CONSISTENT_REGION"), new String[] {"MQTTSink"}); //$NON-NLS-1$
			}
		}
	}
	
	@ContextCheck(compile=true, runtime=false)
	public static boolean compileCheckTopic(OperatorContextChecker checker)
	{
		OperatorContext context = checker.getOperatorContext();
		
		// check the topic and topicAttributeName parameters are mutually exclusive
		boolean check = checker.checkExcludedParameters("topic", "topicAttributeName") && //$NON-NLS-1$ //$NON-NLS-2$
				checker.checkExcludedParameters("topicAttributeName", "topic"); //$NON-NLS-1$ //$NON-NLS-2$
		
		// check that at least one of topic or topicAttributeName parameter is specified
		Set<String> parameterNames = context.getParameterNames();		
		boolean hasTopic = parameterNames.contains("topic") || parameterNames.contains("topicAttributeName"); //$NON-NLS-1$ //$NON-NLS-2$
		
		if (!hasTopic)
		{
			checker.setInvalidContext(Messages.getString("TOPIC_OR_TOPICATTRIB_MUST_BE_SPECIFIED"), null); //$NON-NLS-1$
		}
		
		check = check & hasTopic;
		
		return check;
		
	}
	
	   @ContextCheck(compile=false)
	    public  static void runtimeChecks(OperatorContextChecker checker) {
	    	
	    	validateNumber(checker, "period", 0, Long.MAX_VALUE); //$NON-NLS-1$
	    	validateNumber(checker, "qos", 0, 2); //$NON-NLS-1$
	    	validateNumber(checker, "reconnectionBound", -1, Long.MAX_VALUE); //$NON-NLS-1$
	    	
	    	checkInputAttribute(checker, "qosAttributeName", MetaType.INT32); //$NON-NLS-1$
	    	checkInputAttribute(checker, "topicAttributeName", MetaType.RSTRING, MetaType.USTRING); //$NON-NLS-1$
	    	
	    	checkInputAttribute(checker, "dataAttributeName", MetaType.RSTRING, MetaType.BLOB); //$NON-NLS-1$
	    	
	    }

	private static void checkInputAttribute(OperatorContextChecker checker, String parameterName, MetaType... validTypes) {
		if (checker.getOperatorContext().getParameterNames().contains(parameterName)) {
			
			List<String> parameterValues = checker.getOperatorContext().getParameterValues(parameterName);
			String attributeName = parameterValues.get(0);
			List<StreamingInput<Tuple>> inputPorts = checker.getOperatorContext().getStreamingInputs();
			if (inputPorts.size() > 0)
			{
				StreamingInput<Tuple> inputPort = inputPorts.get(0);
				StreamSchema streamSchema = inputPort.getStreamSchema();
				boolean check = checker.checkRequiredAttributes(streamSchema, attributeName);
				if (check)
					checker.checkAttributeType(streamSchema.getAttribute(attributeName), validTypes);
			}
		}
	}
	
	@ContextCheck(compile=true, runtime=false)
	public static void checkInputPortSchema(OperatorContextChecker checker) {
		List<StreamingInput<Tuple>> inputPorts = checker.getOperatorContext().getStreamingInputs();
		
		if (inputPorts.size() > 0)
		{
			// if user is not specifying dataAttributeName attribute
			// then we check if stream schema contains default data attribute
			// or if schema contains only single attribute
			if(!checker.getOperatorContext().getParameterNames().contains("dataAttributeName")) { //$NON-NLS-1$
							
				StreamingInput<Tuple> dataPort = inputPorts.get(0);
			    StreamSchema streamSchema = dataPort.getStreamSchema();
			    
			    Attribute dataAttribute = null;
			    if(streamSchema.getAttributeCount() == 1) {
			    	dataAttribute = streamSchema.getAttribute(0);
			    }
			    else {
			    	dataAttribute = streamSchema.getAttribute("data"); //$NON-NLS-1$
			    }
			    							
			    // the default data attribute must be present and must be either BLOB or RSTRING
			    if(dataAttribute != null) {
			    	checker.checkAttributeType(dataAttribute, MetaType.RSTRING, MetaType.BLOB );
			    }
			    else {
				    checker.setInvalidContext(Messages.getString("DATA_ATTRIB_NOT_FOUND_FROM_INPUT_PORT"), new Object[]{}); //$NON-NLS-1$
			    }
			}
			
		}
		//TODO:  check control input port
	}
	
	@ContextCheck(compile = true, runtime = false)
	public static void checkOutputPort(OperatorContextChecker checker) {
		validateSchemaForErrorOutputPort(checker, getErrorPortFromContext(checker.getOperatorContext()));
	}
	
    /**
     * Initialize this operator. Called once before any tuples are processed.
     * @param context OperatorContext for this operator.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
	@Override
	public synchronized void initialize(OperatorContext context)
			throws Exception {
    	// Must call super.initialize(context) to correctly setup an operator.
		super.initialize(context);
        Logger.getLogger(this.getClass()).trace("Operator " + context.getName() + " initializing in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId() ); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        
        crContext = context.getOptionalContext(ConsistentRegionContext.class);
        
       tupleQueue = new ArrayBlockingQueue<Tuple>(50);
        
       mqttWrapper = new MqttClientWrapper();       
       initFromConnectionDocument();
       mqttWrapper.setBrokerUri(getServerUri());
       mqttWrapper.setReconnectionBound(getReconnectionBound());
       mqttWrapper.setPeriod(getPeriod());
       mqttWrapper.setUserID(getUserID());
       mqttWrapper.setPassword(getPassword());
       mqttWrapper.setClientID(getClientID());
       mqttWrapper.setCommandTimeout(getCommandTimeout());
       mqttWrapper.setKeepAliveInterval(getKeepAliveInterval());
       mqttWrapper.setConnectionLostMetric(nConnectionLost);
       mqttWrapper.setIsConnectedMetric(isConnected);
       
       setupSslProperties(mqttWrapper);
    
       if(getAppConfigName() != null) {
    	   mqttWrapper.setPropProvider(new PropertyProvider(context.getPE(), getAppConfigName()));
    	   mqttWrapper.setUserPropName(getUserPropName());
    	   mqttWrapper.setPasswordPropName(getPasswordPropName());
       }
       
       
       if(crContext != null) {
    	   initState = new InitialState();
       }
       
       initRelaunching(context);
       // do not connect here... connection is done on the publish thread when a message
       // is ready to be published
    
		// register for data governance
		// if static topic, then register topic, else only register the server
		if (topicAttributeName == null) {
			registerForDataGovernance();
		} else {
			// register the "server" for governance
			registerServerForDataGovernance();
		}
	}

	private void registerForDataGovernance() {
		String uri = getServerUri();
		String topic = getTopics();
		TRACE.log(TraceLevel.INFO,
				"MQTTSink - Registering for data governance with server uri: " + uri + " and topic: " + topic); //$NON-NLS-1$ //$NON-NLS-2$

		if (topic != null && !topic.isEmpty() && uri != null && !uri.isEmpty()) {
			DataGovernanceUtil.registerForDataGovernance(this, topic, IGovernanceConstants.ASSET_MQTT_TOPIC_TYPE, uri,
					IGovernanceConstants.ASSET_MQTT_SERVER_TYPE, false, "MQTTSink"); //$NON-NLS-1$
		} else {
			TRACE.log(TraceLevel.INFO,
					"MQTTSink - Registering for data governance -- aborted. topic and/or url is null"); //$NON-NLS-1$
		}
	}

	private void registerServerForDataGovernance() {
		String uri = getServerUri();
		TRACE.log(TraceLevel.INFO, "MQTTSource - Registering only server for data governance with server uri: " + uri); //$NON-NLS-1$

		if (uri != null && !uri.isEmpty()) {
			DataGovernanceUtil.registerForDataGovernance(this, uri, IGovernanceConstants.ASSET_MQTT_SERVER_TYPE, null,
					null, false, "MQTTSink"); //$NON-NLS-1$
		} else {
			TRACE.log(TraceLevel.INFO,
					"MQTTSource - Registering only server for data governance -- aborted. uri is null"); //$NON-NLS-1$
		}
	}
	
	/**
     * Notification that initialization is complete and all input and output ports 
     * are connected and ready to receive and submit tuples.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    @Override
    public synchronized void allPortsReady() throws Exception {
    	// This method is commonly used by source operators. 
    	// Operators that process incoming tuples generally do not need this notification. 
        OperatorContext context = getOperatorContext();
        Logger.getLogger(this.getClass()).trace("Operator " + context.getName() + " all ports are ready in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId() ); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        
        publishThread = context.getThreadFactory().newThread(new PublishRunnable());
        publishThread.start();
        
    }

    /**
     * Process an incoming tuple that arrived on the specified port.
     * @param stream Port the tuple is arriving on.
     * @param tuple Object representing the incoming tuple.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    @Override
    public void process(StreamingInput<Tuple> stream, Tuple tuple)  throws Exception {
    	
    	if(isRelaunching()) {
    		TRACE.log(TraceLevel.DEBUG, "Operator is re-launching, discard incoming tuple " + tuple.toString()); //$NON-NLS-1$
    	    return;
    	}
    	
    	// if data port
    	if (stream.getPortNumber() == 0)
    	{
    		// put tuple to queue
    		tupleQueue.put(tuple);
    	}
    	
    	// else if control input port
    	else {
			TRACE.log(TraceLevel.DEBUG, "[Control Port:] Control Signal Received"); //$NON-NLS-1$

    		handleControlSignal(tuple);
    	}
    }

	private void handleControlSignal(Tuple tuple) {
		// handle control signal to switch server
		try {
			Object object = tuple.getObject(0);
			TRACE.log(TraceLevel.DEBUG, "[Control Port:] object: " + object + " " + object.getClass().getName()); //$NON-NLS-1$ //$NON-NLS-2$

			if (object instanceof Map)
			{									
				Map map = (Map)object;
				Set keySet = map.keySet();
				for (Iterator iterator = keySet.iterator(); iterator
						.hasNext();) {
					Object key = (Object) iterator.next();
					TRACE.log(TraceLevel.DEBUG, "[Control Port:] " + key + " " + key.getClass()); //$NON-NLS-1$ //$NON-NLS-2$
					
					String keyStr = key.toString();
					
					// case insensitive checks
					if (keyStr.toLowerCase().equals(IMqttConstants.CONN_SERVERURI.toLowerCase()))
					{
						Object serverUri = map.get(key);				
						
						String serverUriStr = serverUri.toString();
						
						// only handle if server URI has changed
						if (!serverUriStr.toLowerCase().equals(getServerUri().toLowerCase()))
						{						
							TRACE.log(TraceLevel.DEBUG, "[Control Port:] " + IMqttConstants.CONN_SERVERURI + ":" + serverUri); //$NON-NLS-1$ //$NON-NLS-2$
						
							// set pending broker URI to get wrapper out of retry loop
							mqttWrapper.setPendingBrokerUri(serverUriStr);
							
							// interrupt the publish thread in case it is sleeping
							if (publishThread.getState() == State.TIMED_WAITING)
								publishThread.interrupt();								
						}
					}					
				}
			}
		} catch (Exception e) {
			String errorMsg = Messages.getString("CANNOT_PROCESS_CTRL_SIGNAL", tuple.toString()); //$NON-NLS-1$
			TRACE.log(TraceLevel.ERROR, errorMsg); //$NON-NLS-1$
			submitToErrorPort(errorMsg, crContext);
		}
	}
    
    /**
     * Process an incoming punctuation that arrived on the specified port.
     * @param stream Port the punctuation is arriving on.
     * @param mark The punctuation mark
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    @Override
    public void processPunctuation(StreamingInput<Tuple> stream,
    		Punctuation mark) throws Exception {
    	// TODO: If window punctuations are meaningful to the external system or data store, 
    	// insert code here to process the incoming punctuation.
    }

    /**
     * Shutdown this operator.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    @Override
    public synchronized void shutdown() throws Exception {
        OperatorContext context = getOperatorContext();
        Logger.getLogger(this.getClass()).trace("Operator " + context.getName() + " shutting down in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId() ); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                
        shutdown = true;
        mqttWrapper.disconnect();
        mqttWrapper.shutdown();
        
        // Must call super.shutdown()
        super.shutdown();
    }

    @Parameter(name="topic", description=SPLDocConstants.MQTTSINK_PARAM_TOPIC_DESC, optional=true)
	public void setTopics(String topic) {
		this.topic = topic;
		
		if (topic.startsWith("$")) //$NON-NLS-1$
		{
			topicAttributeName = topic.substring(1);
		}
	}

    @Parameter(name="qos", description=SPLDocConstants.MQTTSINK_PARAM_QOS_DESC, optional=true)
	public void setQos(int qos) {
		this.qos = qos;
	}

    public String getTopics() {
		return topic;
	}

	public int getQos() {
		return qos;
	}

	@Parameter(name="reconnectionBound", description=SPLDocConstants.MQTTSINK_PARAM_RECONN_BOUND_DESC, optional=true)
	public void setReconnectionBound(int reconnectionBound) {
		this.reconnectionBound = reconnectionBound;
	}
	
	@Parameter(name="period", description=SPLDocConstants.MQTTSINK_PARAM_PERIOD_DESC, optional=true)
	public void setPeriod(long period) {
		this.period = period;
	}
	
	public int getReconnectionBound() {
		return reconnectionBound;
	}
	
	public long getPeriod() {
		return period;
	}

	public boolean isRetain() {
		return retain;
	}

	@Parameter(name="retain", description=SPLDocConstants.MQTTSINK_PARAM_RETAIN_DESC, optional=true)
	public void setRetain(boolean retain) {
		this.retain = retain;
	}
	
	@Parameter(name="topicAttributeName", description=SPLDocConstants.MQTTSINK_PARAM_TOPIC_ATTR_NAME_DESC, optional=true)
	public void setTopicAttrName(String topicAttr) {
		this.topicAttributeName = topicAttr;
	}
	
	public String getTopicAttrName() {
		return topicAttributeName;
	}
	
	@Parameter(name="qosAttributeName", description=SPLDocConstants.MQTTSINK_PARAM_QOS_ATTR_NAME_DESC, optional=true)
	public void setQosAttributeName(String qosAttributeName) {
		this.qosAttributeName = qosAttributeName;
	}
	
	public String getQosAttributeName() {
		return qosAttributeName;
	}
	
	protected StreamingOutput<OutputTuple> getErrorOutputPort() {
		return getErrorPortFromContext(getOperatorContext());
	}
    
	private static StreamingOutput<OutputTuple> getErrorPortFromContext(OperatorContext opContext) {
		List<StreamingOutput<OutputTuple>> streamingOutputs = opContext.getStreamingOutputs();
		if (streamingOutputs.size() > 0) {
			return streamingOutputs.get(0);
		}
		return null;
	}

	@Override
	public void close() throws IOException {
		TRACE.log(TraceLevel.DEBUG, "StateHandler close"); //$NON-NLS-1$
	}

	@Override
	public void checkpoint(Checkpoint checkpoint) throws Exception {
		TRACE.log(TraceLevel.DEBUG, "Checkpoint " + checkpoint.getSequenceId()); //$NON-NLS-1$
		
		String currentServerUri = this.getServerUri();
		checkpoint.getOutputStream().writeObject(currentServerUri);
		
	}

	@Override
	public void drain() throws Exception {
		TRACE.log(TraceLevel.DEBUG, "Drain pending tuples..."); //$NON-NLS-1$

		if(tupleQueue.peek() != null) {
			synchronized(drainLock) {
				if(tupleQueue.peek() != null) {
					drainLock.wait(IMqttConstants.CONSISTENT_REGION_DRAIN_WAIT_TIME);
					if(tupleQueue.peek() != null) {
						throw new Exception(Messages.getString("TIMED_OUT_WAITING_FOR_TUPLES_DRAINING")); //$NON-NLS-1$
					}
				}
			}
		}
	}

	@Override
	public void reset(Checkpoint checkpoint) throws Exception {
		TRACE.log(TraceLevel.DEBUG, "Reset to checkpoint " + checkpoint.getSequenceId()); //$NON-NLS-1$
		
		resetServerUri((String) checkpoint.getInputStream().readObject());
		resetRelaunching();
	}

	@Override
	public void resetToInitialState() throws Exception {
		TRACE.log(TraceLevel.DEBUG, "Reset to initial state"); //$NON-NLS-1$
		
		if(initState != null && initState.initialServerUri != null) {
			resetServerUri(initState.initialServerUri);
		}
		resetRelaunching();
	}

	@Override
	public void retireCheckpoint(long id) throws Exception {
		TRACE.log(TraceLevel.DEBUG, "Retire checkpoint" + id);	 //$NON-NLS-1$
	}
	
	private void resetServerUri(String serverUri) {
		
		// if current server uri is not same as the uri saved in last checkpoint
		// then we want to set it as pending broker uri.
		if(!getServerUri().equals(serverUri)) {
			mqttWrapper.setPendingBrokerUri(serverUri);
		}
	}
	
	private void initRelaunching(OperatorContext opContext) {
		TRACE.log(TraceLevel.DEBUG, "Relaunching set to true");		  //$NON-NLS-1$

		isRelaunching = false; 
		 		if (crContext != null ) 
		 		{ 
		 			int relaunchCount = opContext.getPE().getRelaunchCount(); 
					if (relaunchCount > 0) { 
						isRelaunching = true; 
		 			} 
	 		} 
	}
	
	private void resetRelaunching() {
		TRACE.log(TraceLevel.DEBUG, "Relaunching set to false");  //$NON-NLS-1$
		isRelaunching = false; 
	}
	
	private boolean isRelaunching() {
		return isRelaunching;
	}
}
