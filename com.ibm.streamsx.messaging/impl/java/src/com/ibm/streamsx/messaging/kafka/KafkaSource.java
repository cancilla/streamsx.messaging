/*******************************************************************************
 * Copyright (C) 2014, International Business Machines Corporation
 * All Rights Reserved
 *******************************************************************************/

package com.ibm.streamsx.messaging.kafka;


import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.WakeupException;

import com.ibm.streams.operator.OperatorContext;
import com.ibm.streams.operator.OperatorContext.ContextCheck;
import com.ibm.streams.operator.OutputTuple;
import com.ibm.streams.operator.StreamSchema;
import com.ibm.streams.operator.StreamingOutput;
import com.ibm.streams.operator.compile.OperatorContextChecker;
import com.ibm.streams.operator.logging.TraceLevel;
import com.ibm.streams.operator.model.Icons;
import com.ibm.streams.operator.model.OutputPortSet;
import com.ibm.streams.operator.model.OutputPorts;
import com.ibm.streams.operator.model.Parameter;
import com.ibm.streams.operator.model.PrimitiveOperator;
import com.ibm.streams.operator.state.Checkpoint;
import com.ibm.streams.operator.state.ConsistentRegionContext;
import com.ibm.streams.operator.state.StateHandler;
import com.ibm.streamsx.messaging.common.DataGovernanceUtil;
import com.ibm.streamsx.messaging.common.IGovernanceConstants;

@OutputPorts(@OutputPortSet(cardinality=1, optional=false, 
	description="Messages received from Kafka are sent on this output port."))
@PrimitiveOperator(name=KafkaSource.OPER_NAME, description=KafkaSource.DESC)
@Icons(location16="icons/KafkaConsumer_16.gif", location32="icons/KafkaConsumer_32.gif")
public class KafkaSource extends KafkaBaseOper implements StateHandler{

	static final String OPER_NAME = "KafkaConsumer";
	private static final int MAX_SHUTDOWN_THREAD = 10;
	private int threadsPerTopic = 1;
	private List<Integer> partitions = new ArrayList<Integer>();
	private static Logger trace = Logger.getLogger(KafkaSource.class.getName());
	private final AtomicBoolean shutdown = new AtomicBoolean(false);
	private AtomicBoolean consumerIsShutdown = new AtomicBoolean(false);
	
	// Need this because KafkaConsumer.close() hangs if we have lost connection
	// to the broker. 
	ExecutorService shutdownExecutor;
	
	@SuppressWarnings("rawtypes")
	KafkaConsumerClient streamsKafkaConsumer;
	private int consumerPollTimeout = 100;
	private int triggerCount = -1;
	Thread processThread;
	
	private ConsistentRegionContext crContext;
	private long triggerIteration = 0;
	
	//consistent region checks
	@ContextCheck(compile = true)
	public static void checkInConsistentRegion(OperatorContextChecker checker) {
		ConsistentRegionContext consistentRegionContext = 
				checker.getOperatorContext().getOptionalContext(ConsistentRegionContext.class);
		OperatorContext operContext = checker.getOperatorContext();

		if(consistentRegionContext != null ) {
			if (!operContext.getParameterNames().contains("partition")){
				checker.setInvalidContext("The partition parameter must be specified in consistent regions.", new String[] {});
			}
		}
	}

	@ContextCheck(runtime = true, compile = false)
	public static void checkRuntimeCompatability(OperatorContextChecker checker) {
		OperatorContext operContext = checker.getOperatorContext();

		if (operContext.getParameterNames().contains("partition")) {

			if (operContext.getParameterValues("topic").size() > 1) {
				checker.setInvalidContext(
						"Invalid topic parameter: Only one topic can be specified when the partition parameter is set.",
						new String[] {});
				throw new IllegalArgumentException(
						"Invalid topic parameter: Only one topic can be specified when the partition parameter is set.");
			}

		}

	}
	
	//check for message attribute
	@ContextCheck(runtime = true, compile=false)
	public static void checkIncomingMessageAttribute(OperatorContextChecker checker) throws Exception {
		OperatorContext operContext = checker.getOperatorContext();
		StreamSchema operSchema = operContext.getStreamingOutputs().get(0).getStreamSchema();
		checkForMessageAttribute(operContext, operSchema);		
	}
	
	@Override
	public void initialize(OperatorContext context)
			throws Exception {
		super.initialize(context);
		super.initSchema(getOutput(0).getStreamSchema());

		if (threadsPerTopic < 1)
			throw new IllegalArgumentException(
					"Number of threads per topic cannot be less than one: "
							+ threadsPerTopic);

		// initialize the client
		trace.log(TraceLevel.INFO, "Initializing source client");
		streamsKafkaConsumer = getNewConsumerClient(topicAH, keyAH, messageAH,
				partitions, consumerPollTimeout, finalProperties, getOutput(0), topics);
		
		// Get consistent region context 
		crContext = getOperatorContext()
				.getOptionalContext(ConsistentRegionContext.class);
		// register for data governance
		registerForDataGovernance();

	}

    
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static KafkaConsumerClient getNewConsumerClient(AttributeHelper topicAH, AttributeHelper keyAH,
			AttributeHelper messageAH, List<Integer> partitions, int consumerPollTimeout, Properties finalProperties,
			StreamingOutput<OutputTuple> streamingOutput, List<String> topics) throws UnsupportedStreamsKafkaConfigurationException {
		KafkaConsumerFactory clientFactory = new KafkaConsumerFactory();
		KafkaConsumerClient kafkaConsumer = clientFactory.getClient(topicAH, keyAH, messageAH,
				partitions, consumerPollTimeout, finalProperties);
		kafkaConsumer.init(streamingOutput, topics);
		return kafkaConsumer;
	}

	private void registerForDataGovernance() {
		trace.log(TraceLevel.INFO, "KafkaSource - Registering for data governance");
		if (topics != null) {
			for (String topic : topics) {
				trace.log(TraceLevel.INFO, "KafkaSource - data governance - topic: " + topic);
				DataGovernanceUtil.registerForDataGovernance(this, topic, IGovernanceConstants.ASSET_KAFKA_TOPIC_TYPE,
						null, null, true, "KafkaSource");
			}
		} else {
			trace.log(TraceLevel.INFO, "KafkaSource - Registering for data governance -- topics is empty");
		}
	}

    
	@Override
	public void allPortsReady() throws Exception {	
		
		// Used to shutdown Kafka consumers since close hangs if we lose server connection
		// (this is a bug in Kafka)
		shutdownExecutor = Executors.newFixedThreadPool(MAX_SHUTDOWN_THREAD);
		
		
		processThread = getOperatorContext()
				.getThreadFactory().newThread(new Runnable() {

			@Override
			public void run() {
				try {
					produceTuples();
				} catch (FileNotFoundException e) {
					trace.log(TraceLevel.ERROR, e.getMessage());
					e.printStackTrace();
				} catch (IOException e) {
					trace.log(TraceLevel.ERROR, e.getMessage());
					e.printStackTrace();
				} catch (UnsupportedStreamsKafkaConfigurationException e) {
					trace.log(TraceLevel.ERROR, e.getMessage());
					e.printStackTrace();
				}
			}

		});
		
		/*
		 * Set the thread not to be a daemon to ensure that the SPL runtime will
		 * wait for the thread to complete before determining the operator is
		 * complete.
		 */
		processThread.setDaemon(false);
		processThread.start();
	}
	
	@SuppressWarnings("unchecked")
	public void produceTuples() throws FileNotFoundException, IOException, UnsupportedStreamsKafkaConfigurationException{	
		while (!shutdown.get()) {
			try {
				if (crContext != null){
					if(trace.isLoggable(TraceLevel.TRACE))
						trace.log(TraceLevel.TRACE, "Acquiring consistent region permit.");
					crContext.acquirePermit();
				}
				
				ConsumerRecords<?,?> records = streamsKafkaConsumer.getRecords(consumerPollTimeout);
				
				if (records.isEmpty()){
					streamsKafkaConsumer.checkConnectionCount();
				} else {
					streamsKafkaConsumer.processAndSubmit(records);
					if (crContext != null
							&& crContext.isTriggerOperator()) {
						triggerIteration += records.count();
						if (triggerIteration >= triggerCount) {
							trace.log(TraceLevel.INFO, "Making consistent..." );
							crContext.makeConsistent();
							triggerIteration = 0;
						}
					}
				}
			} catch (WakeupException e){
	            // Close if we are shutting down, else error
				if (shutdown.get()) {
					trace.log(TraceLevel.ALL, "Shutting down consumer.");
					if (streamsKafkaConsumer != null) {
						shutdownCurrentConsumer();
						consumerIsShutdown.set(true);
						synchronized(consumerIsShutdown){
							consumerIsShutdown.notifyAll();
						}
					}
				} else {
					// Else let's see if we have new properties to reset the consumer
					trace.log(TraceLevel.ERROR, "WakeupException: " + e.getMessage());
					e.printStackTrace();
					resetConsumerIfPropertiesHaveChanges();
				}
			} catch (NoKafkaBrokerConnectionsException 
					| KafkaException e){
				// Let's see if we have new properties to reset the consumer
				trace.log(TraceLevel.ERROR, e.getMessage());
				e.printStackTrace();
				resetConsumerIfPropertiesHaveChanges();
	        } catch (InterruptedException e) {
	        	// Interrupted while acquiring permit
	        	trace.log(TraceLevel.ERROR, "Error while acquiring permit: " + e.getMessage());
				e.printStackTrace();
			} catch (Exception e) {
				trace.log(TraceLevel.ERROR, "Error while processing and submitting messages: " + e.getMessage());
				e.printStackTrace();
			} finally {
				if (crContext != null){
					crContext.releasePermit();
					if(trace.isLoggable(TraceLevel.TRACE))
						trace.log(TraceLevel.TRACE, "Released consistent region permit.");
				}
			}
		}
		
		if (!consumerIsShutdown.get()){
			streamsKafkaConsumer.shutdown();
			consumerIsShutdown.set(true);
			synchronized(consumerIsShutdown){
				consumerIsShutdown.notifyAll();
			}
		}
	}

	private void resetConsumerIfPropertiesHaveChanges() throws FileNotFoundException, IOException, UnsupportedStreamsKafkaConfigurationException {
		OperatorContext context = this.getOperatorContext();
		if (newPropertiesExist(context)){
			trace.log(TraceLevel.INFO,
					"Properties have changed. Initializing consumer with new properties.");
			resetConsumerClient(context);
		} else {
			trace.log(TraceLevel.INFO, "P:roperties have not changed, so we are keeping the same consumer client!");
		}
		
	}

	private void resetConsumerClient(OperatorContext context) throws FileNotFoundException, IOException, UnsupportedStreamsKafkaConfigurationException {
		// Not catching exceptions because we want to fail
		// if we can't initialize a new consumer
		populateKafkaProperties(context);		
        shutdownCurrentConsumer();
		trace.log(TraceLevel.INFO,
				"Shut down consumer. Will attempt to create a new one.");
		streamsKafkaConsumer = getNewConsumerClient(topicAH, keyAH, messageAH,
				partitions, consumerPollTimeout, finalProperties, getOutput(0), topics);
	}

	private void shutdownCurrentConsumer() {
		Future<String> future = shutdownExecutor.submit(new ShutdownTask(streamsKafkaConsumer));
        try {
            future.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException | InterruptedException | ExecutionException e) {
            e.printStackTrace();
            trace.log(TraceLevel.WARN,"Consumer didn't shutdown in the time expected. You have likely lost connection to the Kafka broker.");
        }
	}
	
	@SuppressWarnings("rawtypes")
	class ShutdownTask implements Callable<String> {
		KafkaConsumerClient consumer;
		
		public ShutdownTask(KafkaConsumerClient consumer){
			this.consumer = consumer;
		}
		
	    @Override
	    public String call() throws Exception {
	    	consumer.shutdown();
	        return "Shutdown!";
	    }
	}

	@Parameter(name = "consumerPollTimeout", optional = true, description = "The time, in milliseconds, spent waiting in poll if data is not available. If 0, returns immediately with any records that are available now. Must not be negative. This parameter is only valid when using the KafkaConsumer(0.9) (specified bootstrap.servers instead of zookeeper.connect). Default is 100.")
	public void setConsumerPollTimeout(int value) {
		this.consumerPollTimeout = value;
	}
	
    @Parameter(name="triggerCount", optional=true, 
			description="Approximate number of messages between checkpointing for consistent region. This is only relevant to operator driven checkpointing. Checkpointing is done after a buffer of messages is submitted, so actual triggerCount at checkpoint time may be slightly above specified triggerCount.")
	public void setTriggerCount(int value) {
	   	this.triggerCount = value;
	}

	@Parameter(name = "topic", cardinality = -1, optional = false, description = "Topic to be subscribed to. 1 or more can be provided using comma separation. Ex: \\\"mytopic1\\\",\\\"mytopic2\\\"")
	public void setTopic(List<String> values) {
		if (values != null)
			topics.addAll(values);
	}

	@Parameter(name = "partition", cardinality = -1, optional = true, description = "Partition to be subscribed to. 1 or more can be provided using comma separation. You may only specify 1 topic if you are specifying partitions. Ex: 0,2,3")
	public void setPartition(int[] values) {
		for (int index = 0; index < values.length; index++) {
			partitions.add(values[index]);
		}
	}

	public static final String DESC = 
			"This operator acts as a Kafka consumer receiving messages for one or more topics. " +
			"For parallel consumption, we strongly recommend specifying partitions on each Consumer operator, " +
			"as we have found the automatic partition assignment strategies from Kafka to be unreliable." + 
			"Ordering of messages is only guaranteed per Kafka topic partition." + 
			BASE_DESC + // common description between Source and Sink
			"\\n\\n**Behavior in a Consistent Region**" + 
			"\\nThis operator can be used inside a consistent region. Operator driven and periodical checkpointing " +
			"are supported. Partitions to be read from must be specified. " +
			"Resetting to initial state is not supported because the intial offset cannot be saved and may not be present in the Kafka log. " + 
			"In the case of a reset to initial state after operator crash, messages will start being read from the time of reset."
			;
	
	@Override
	public void shutdown() throws Exception {
		shutdown.set(true);
		if (streamsKafkaConsumer != null){
			streamsKafkaConsumer.wakeupConsumer();
		}
		
		// Wait to make sure we have caught the wakeup exception
		// and submitted shutdown task. 
		if (!consumerIsShutdown.get()){
			synchronized(consumerIsShutdown){
				consumerIsShutdown.wait(); // Wait until shutdown task submitted
			}
		}
		
		shutdownExecutor.shutdown();
		Boolean successfulTerm = shutdownExecutor.awaitTermination(5, TimeUnit.SECONDS);
		trace.log(TraceLevel.INFO, "Executor termination success: " + successfulTerm);
		
		super.shutdown();
	}

	@SuppressWarnings("unchecked")
	@Override
	public void checkpoint(Checkpoint checkpoint) throws Exception {
		Map<Integer, Long> offsetMap = streamsKafkaConsumer.getOffsetPositions();
		trace.log(TraceLevel.INFO, "Checkpointing offsetMap.");
		checkpoint.getOutputStream().writeObject(offsetMap);
	}

	@Override
	public void drain() throws Exception {
		trace.log(TraceLevel.INFO,"Draining....");
	}

	@SuppressWarnings("unchecked")
	@Override
	public void reset(Checkpoint checkpoint) throws Exception {
		Map<Integer, Long> offsetMap = (Map<Integer, Long>) checkpoint.getInputStream().readObject();
		trace.log(TraceLevel.INFO, "Resetting...");
		streamsKafkaConsumer.seekToPositions(offsetMap);		
	}

	@Override
	public void resetToInitialState() throws Exception {
		trace.log(TraceLevel.INFO, "Resetting to initial state. Consumer will begin consuming from the latest offset (initial state is not supported by this operator).");
	}

	@Override
	public void retireCheckpoint(long id) throws Exception {
		trace.log(TraceLevel.INFO, "Retiring Checkpoint.");
	}

	@Override
	public void close() throws IOException {
		
	}

}

