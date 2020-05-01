import java.util.*;

import com.amazonaws.services.ecs.*;
import com.amazonaws.services.ecs.model.*;
import com.amazonaws.services.ecs.model.Task;
import com.amazonaws.services.route53.AmazonRoute53;
import com.amazonaws.services.route53.AmazonRoute53ClientBuilder;
import com.amazonaws.services.route53.model.*;

public class HostnameMaintainer
{
    /**
     * Assigns orphan task IPs with invalid records.
     * Tasks that don't have their private IP associated with a record set
     * on the provided hosted zone are called orphan tasks.
     * A type record sets that have IPs which don't belong to tasks in the specified
     * cluster are called invalid records.
     * @param clusterARN ARN of the cluster
     * @param hostedZoneId id of the hosted zone to manage
     * @param serviceName id of the service that owns the tasks
     */
    public static void fixInvalidRecordsAndOrphanTasks(String clusterARN, String serviceName, String hostedZoneId)
    {
        List<String> orphanTaskIPs = getTaskIPs(clusterARN, serviceName);
        Map<String, String> recordsMap = getRecordNameIpMap(hostedZoneId);

        //Records whose IPs are not present in taskIps
        List<String> invalidRecords = new ArrayList<>();

        //Changes to be applied to Route53
        List<Change> changes = new ArrayList<>();

        for (Map.Entry<String, String> entry : recordsMap.entrySet())
        {
            String recordName = entry.getKey();
            String recordIP = entry.getValue();

            //Remove recordIP from orphanTaskIPs and
            //if it failed to remove (the recordIP was not present
            //in orphanTaskIPs) then we add the recordName
            //to invalidRecords
            if(!orphanTaskIPs.remove(recordIP))
            {
                invalidRecords.add(recordName);
            }
        }

        //Assign orphan IPs to invalid records
        for (String ip : orphanTaskIPs)
        {
            if(invalidRecords.size() > 0)
            {
                //Make a change to update an invalid record with
                //an orphan IP

                Change change = new Change();
                ResourceRecordSet resourceRecordSet = new ResourceRecordSet();

                resourceRecordSet.setName(invalidRecords.get(0));
                resourceRecordSet.setType("A");
                resourceRecordSet.setTTL((long) 300);

                ResourceRecord resourceRecord = new ResourceRecord();
                resourceRecord.setValue(ip);
                resourceRecordSet.setResourceRecords(Collections.singletonList(resourceRecord));

                change.setAction("UPSERT");
                change.setResourceRecordSet(resourceRecordSet);

                changes.add(change);

                //Remove the first element of invalidRecords since we just made a change that
                //assigns an IP to it and it's therefore not an invalid record anymore.
                invalidRecords.remove(0);
            }
            else
            {
                //Ran out of records to assign IPs
                //Issue an error
                break;
            }
        }

        //Only make a route 53 change request if there are any changes to be made
        if(changes.size() > 0)
        {
            AmazonRoute53 r53 = AmazonRoute53ClientBuilder.defaultClient();

            ChangeBatch changeBatch = new ChangeBatch();
            changeBatch.setChanges(changes);

            ChangeResourceRecordSetsRequest request = new ChangeResourceRecordSetsRequest();
            request.setChangeBatch(changeBatch);
            request.setHostedZoneId(hostedZoneId);

            r53.changeResourceRecordSets(request);
        }
    }

    /**
     * Returns a List containing private IPs from all Fargate tasks that belong to a service.
     * @param clusterARN the ARN of the cluster of the tasks
     * @return an array of private IPs
     */
    public static List<String> getTaskIPs(String clusterARN, String serviceName)
    {
        AmazonECS ecs = AmazonECSClientBuilder.defaultClient();

        ListTasksRequest listRequest = new ListTasksRequest();
        listRequest.setCluster(clusterARN);
        listRequest.setDesiredStatus(DesiredStatus.RUNNING);
        listRequest.setLaunchType("FARGATE");
        listRequest.setServiceName(serviceName);

        List<String> taskARNs = ecs.listTasks(listRequest).getTaskArns();

        DescribeTasksRequest describeTasksRequest = new DescribeTasksRequest();
        describeTasksRequest.setCluster(clusterARN);
        describeTasksRequest.setTasks(taskARNs);

        DescribeTasksResult describeTasksResult = ecs.describeTasks(describeTasksRequest);

        List<String> privateIPs = new ArrayList<>();

        for(Task task : describeTasksResult.getTasks())
        {
            List<Attachment> attachments = task.getAttachments();

            Optional<Attachment> eni = attachments.stream()
                    .filter(x -> x.getType().equals("ElasticNetworkInterface"))
                    .findFirst();

            if(eni.isPresent())
            {
                String ip = eni.get().getDetails().stream()
                            .filter(x -> x.getName().equals("privateIPv4Address"))
                            .findFirst()
                            .get()
                            .getValue();

                privateIPs.add(ip);
            }
            else
            {
                //Issue warning and ignore
            }
        }

        return privateIPs;
    }

    /**
     * Returns a map of recordName => IP from Route 53
     * Only searches for 'A' type records.
     * If the record contains more than one IP assigned to it the entire record will be ignored
     * (i.e. it won't be present in the returned map).
     * @param hostedZoneId the ID of the hosted zone from which to get the data
     * @return a recordName => IP map
     */
    public static Map<String, String> getRecordNameIpMap(String hostedZoneId)
    {
        Map<String, String> retVal = new HashMap<>();

        AmazonRoute53 r53 = AmazonRoute53ClientBuilder.defaultClient();

        ListResourceRecordSetsRequest request = new ListResourceRecordSetsRequest();
        request.setHostedZoneId(hostedZoneId);

        ListResourceRecordSetsResult result = r53.listResourceRecordSets(request);

        for(ResourceRecordSet recordSet : result.getResourceRecordSets())
        {
            if(!recordSet.getType().equals("A"))
            {
                continue;
            }

            if(recordSet.getResourceRecords().size() != 1)
            {
                //Issue warning and ignore
                continue;
            }

            retVal.put(recordSet.getName(), recordSet.getResourceRecords().get(0).getValue());
        }

        return retVal;
    }

}
