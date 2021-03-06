package com.ociweb.gl.benchmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import com.ociweb.gl.api.HTTPRequestReader;
import com.ociweb.gl.api.HTTPResponseService;
import com.ociweb.pronghorn.network.config.HTTPContentTypeDefaults;
import com.ociweb.pronghorn.pipe.ObjectPipe;

import io.reactiverse.pgclient.PgIterator;
import io.reactiverse.pgclient.Tuple;

public class ProcessUpdate {
	
	private transient ObjectPipe<ResultObject> DBUpdateInFlight;	
	private final transient List<ResultObject> collectorDBUpdate = new ArrayList<ResultObject>();
	private final transient ThreadLocalRandom localRandom = ThreadLocalRandom.current();
	private final HTTPResponseService service;
	private final transient PoolManager pm;
	private final AtomicInteger requestsInFlight = new AtomicInteger();
	
	public ProcessUpdate(int pipelineBits, HTTPResponseService service, PoolManager pm) {
		this.DBUpdateInFlight = new ObjectPipe<ResultObject>(pipelineBits, ResultObject.class,	ResultObject::new);
		this.service = service;
		this.pm = pm;
				
	}
	
	private int randomValue() {
		return 1+localRandom.nextInt(10000);
	}
	
	public void tickEvent() { 

			ResultObject temp = DBUpdateInFlight.tailObject();
			while (null!=temp && temp.getStatus()>=0) {			
				consumeResultObjectDBUpdate(temp);
				temp = DBUpdateInFlight.tailObject();				
			}	   
		
	}
	


	
	public boolean updateRestRequest(HTTPRequestReader request) {
		int queries;
		if (Struct.UPDATES_ROUTE_INT == request.getRouteAssoc() ) {		
			queries = Math.min(Math.max(1, (request.structured().readInt(Field.QUERIES))),500);		
		} else {
			queries = 1;
		}
		long conId = request.getConnectionId();
		long seqCode = request.getSequenceCode();
		int temp = requestsInFlight.incrementAndGet();
		
		if (DBUpdateInFlight.hasRoomFor(queries) || service.hasRoomFor(temp)) {		
			    
				final AtomicInteger outstanding = new AtomicInteger(queries);
				final List<ResultObject> toUpdate = new ArrayList<ResultObject>();
				
				int q = queries;
				while (--q >= 0) {
				
						final ResultObject worldObject = DBUpdateInFlight.headObject();
						assert(null!=worldObject);
											
						worldObject.setConnectionId(conId);
						worldObject.setSequenceId(seqCode);
						worldObject.setStatus(-2);//out for work	
						worldObject.setGroupSize(queries);
						
						worldObject.setId(randomValue());
											 	
						pm.pool().preparedQuery("SELECT * FROM world WHERE id=$1", Tuple.of(worldObject.getId()), r -> {
								if (r.succeeded()) {
																		
									PgIterator resultSet = r.result().iterator();
							        Tuple row = resultSet.next();			        
							        
							        assert(worldObject.getId()==row.getInteger(0));
							        
							        //read the existing random value and store it in the world object
							        worldObject.setResult(row.getInteger(1));
							        ///////////////////////////////////
							        //the object can be used here with the old value
							        ///////////////////////////////////
							        //set the new random value in this object
							        worldObject.setResult(randomValue());							        
							        toUpdate.add(worldObject);
							        
							        
								} else {	
									System.out.println("unable to query");
									if (r.cause()!=null) {
										r.cause().printStackTrace();
									}
									
									worldObject.setStatus(500);
								}		
								
								if (0 == outstanding.decrementAndGet()) {
									//call update for all the query updates...
																		
									List<Tuple> args = new ArrayList<Tuple>();
									toUpdate.forEach(w-> {										
										args.add(Tuple.of(w.getResult(), w.getId()));										
									});
									Collections.sort(args, (a,b) -> {
										return Integer.compare( ((Tuple)a).getInteger(0),
														        ((Tuple)b).getInteger(0));
									
									});
									
									System.out.println("call for update to "+args.size());
							        pm.pool().preparedBatch("UPDATE world SET randomnumber=$1 WHERE id=$2", 							        		
							        		args, ar -> {	
							        			
							        	int status;		
										if (ar.succeeded()) {
								        	status = 200;	
										} else {	
											System.out.println("unable to update");
											if (ar.cause()!=null) {
												ar.cause().printStackTrace();
											}			
											status = 500;
										}
										toUpdate.forEach(w->{
											w.setStatus(status);
										});
										System.out.println("finished update for "+toUpdate.size()+" status "+status);
										
							        });
								}
							});	
									
						DBUpdateInFlight.moveHeadForward(); //always move to ensure this can be read.
				
				}
				
			return true;
		} else {
			requestsInFlight.decrementAndGet();
			return false;
		}
	}

	private void consumeResultObjectDBUpdate(final ResultObject t) {

		//collect all the objects
		collectorDBUpdate.add(t);
		DBUpdateInFlight.moveTailForward();//only move forward when it is consumed.
		if (collectorDBUpdate.size() == t.getGroupSize()) {
			//now ready to send, we have all the data						
			publishMultiResponseDBUpdate(t.getConnectionId(), t.getSequenceId());
		}
	}

	private void publishMultiResponseDBUpdate(long conId, long seqCode) {
		boolean result =  service.publishHTTPResponse(conId, seqCode, 200,
					    				   HTTPContentTypeDefaults.JSON,
					    				   w-> {
					    					   Templates.multiTemplate.render(w, collectorDBUpdate);
					    					   int c = collectorDBUpdate.size();
					    					   while (--c>=0) {
					    						   assert(collectorDBUpdate.get(c).getConnectionId() == conId);
					    						   assert(collectorDBUpdate.get(c).getSequenceId() == seqCode);					    						   
					    						   collectorDBUpdate.get(c).setStatus(-1);
					    					   }
					    					   collectorDBUpdate.clear();
					    					   DBUpdateInFlight.publishTailPosition();
					    				   });
		assert(result) : "internal error, we should not pick up more work than we can send";
		requestsInFlight.decrementAndGet();
	}
	
	
	
	
	
}
