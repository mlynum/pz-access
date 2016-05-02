/**
 * Copyright 2016, RadiantBlue Technologies, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/
package access.messaging;

import java.util.concurrent.Future;

import messaging.job.JobMessageFactory;
import messaging.job.WorkerCallback;
import model.data.DataResource;
import model.data.deployment.Deployment;
import model.job.Job;
import model.job.result.type.DeploymentResult;
import model.job.result.type.ErrorResult;
import model.job.result.type.FileResult;
import model.job.type.AccessJob;
import model.status.StatusUpdate;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Producer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.stereotype.Component;

import util.PiazzaLogger;
import access.database.MongoAccessor;
import access.deploy.Deployer;
import access.deploy.Leaser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Worker class that handles Access Jobs being passed in through the Dispatcher.
 * Handles the Access Jobs by standing up services, retrieving files, or other
 * various forms of Access for data.
 * 
 * This component assumes that the data intended to be accessed is already
 * ingested into the Piazza system; either by the Ingest component or other
 * components that are capable of inserting data into Piazza.
 * 
 * @author Patrick.Doody & Sonny.Saniev
 * 
 */
@Component
public class AccessWorker {
	@Autowired
	private Deployer deployer;
	@Autowired
	private MongoAccessor mongoAccessor;
	@Autowired
	private Leaser leaser;
	@Autowired
	private PiazzaLogger logger;
	@Value("${space}")
	private String space;

	/**
	 * Listens for Kafka Access messages for creating Deployments for Access of
	 * Resources
	 */
	@Async
	public Future<AccessJob> run(ConsumerRecord<String, String> consumerRecord, Producer<String, String> producer,
			WorkerCallback callback) {
		AccessJob accessJob = null;
		try {
			// Parse Job information from Kafka
			ObjectMapper mapper = new ObjectMapper();
			Job job = mapper.readValue(consumerRecord.value(), Job.class);
			accessJob = (AccessJob) job.jobType;

			// Logging
			logger.log(String.format("Received Request to Access Data %s of Type %s under Job ID",
					accessJob.getDataId(), accessJob.getDeploymentType(), job.getJobId()), PiazzaLogger.INFO);

			// Update Status that this Job is being processed
			StatusUpdate statusUpdate = new StatusUpdate(StatusUpdate.STATUS_RUNNING);
			producer.send(JobMessageFactory.getUpdateStatusMessage(consumerRecord.key(), statusUpdate, space));

			// Depending on how the user wants to Access the Resource
			switch (accessJob.getDeploymentType()) {

			case AccessJob.ACCESS_TYPE_FILE:
				// Return the URL that the user can use to acquire the file
				statusUpdate = new StatusUpdate(StatusUpdate.STATUS_SUCCESS);
				statusUpdate.setResult(new FileResult(accessJob.getDataId()));
				producer.send(JobMessageFactory.getUpdateStatusMessage(consumerRecord.key(), statusUpdate, space));

				// Console Logging
				logger.log(String.format("GeoServer File Request successul for Resource %s", accessJob.getDataId()),
						PiazzaLogger.INFO);
				System.out.println("Deployment File Request Returned for Resource " + accessJob.getDataId());
				break;

			case AccessJob.ACCESS_TYPE_GEOSERVER:
				Deployment deployment = null;

				// Check if a Deployment already exists
				boolean exists = deployer.doesDeploymentExist(accessJob.getDataId());
				if (exists) {
					System.out.println("Renewing Deployment Lease for " + accessJob.getDataId());
					// If it does, then renew the Lease on the
					// existing deployment.
					deployment = mongoAccessor.getDeploymentByDataId(accessJob.getDataId());
					leaser.renewDeploymentLease(deployment);
				} else {
					System.out.println("Creating a new Deployment and lease for " + accessJob.getDataId());
					// Obtain the Data to be deployed
					DataResource dataToDeploy = mongoAccessor.getData(accessJob.getDataId());
					// Create the Deployment
					deployment = deployer.createDeployment(dataToDeploy);
					// Create a new Lease for this Deployment
					leaser.createDeploymentLease(deployment);
				}

				// Update Job Status to complete for this Job.
				statusUpdate = new StatusUpdate(StatusUpdate.STATUS_SUCCESS);
				statusUpdate.setResult(new DeploymentResult(deployment));
				producer.send(JobMessageFactory.getUpdateStatusMessage(consumerRecord.key(), statusUpdate, space));

				// Console Logging
				logger.log(String.format("GeoServer Deployment successul for Resource %s", accessJob.getDataId()),
						PiazzaLogger.INFO);
				System.out.println("Deployment Successfully Returned for Resource " + accessJob.getDataId());
				break;

			default:
				throw new Exception("Unknown Deployment Type: " + accessJob.getDeploymentType());
			}
		} catch (Exception exception) {
			logger.log(
					String.format("Error Accessing Data under Job %s with Error: %s", consumerRecord.key(),
							exception.getMessage()), PiazzaLogger.ERROR);
			exception.printStackTrace();
			try {
				// Send the failure message to the Job Manager.
				StatusUpdate statusUpdate = new StatusUpdate(StatusUpdate.STATUS_ERROR);
				statusUpdate.setResult(new ErrorResult("Could not Deploy Data", exception.getMessage()));
				producer.send(JobMessageFactory.getUpdateStatusMessage(consumerRecord.key(), statusUpdate, space));
			} catch (JsonProcessingException jsonException) {
				// If the Kafka message fails to send, at least log
				// something in the console.
				System.out
						.println("Could not update Job Manager with failure event in Ingest Worker. Error creating message: "
								+ jsonException.getMessage());
				jsonException.printStackTrace();
			}
		} finally {
			callback.onComplete(consumerRecord.key());
		}

		return new AsyncResult<AccessJob>(accessJob);
	}
}
