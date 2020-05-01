import java.util.*;

import com.amazonaws.services.ecs.*;
import com.amazonaws.services.ecs.model.*;
import com.amazonaws.services.ecs.model.Task;
import com.amazonaws.services.route53.AmazonRoute53;
import com.amazonaws.services.route53.AmazonRoute53ClientBuilder;
import com.amazonaws.services.route53.model.*;

public class HostnameMaintainer
{
    public static List<String> getTaskIPs(String clusterARN)
    {
        AmazonECS ecs = AmazonECSClientBuilder.defaultClient();

        ListTasksRequest listRequest = new ListTasksRequest();
        listRequest.setCluster(clusterARN);
        listRequest.setDesiredStatus(DesiredStatus.RUNNING);

        List<String> taskARNs = ecs.listTasks(listRequest).getTaskArns();

        System.out.println(taskARNs);

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

    public static void fixInvalidRecordsAndOrphanTasks(String clusterARN, String hostedZoneId)
    {
        List<String> orphanTaskIPs = getTaskIPs(clusterARN);
        Map<String, String> recordsMap = getRecordNameIpMap(hostedZoneId);

        List<String> invalidRecords = new ArrayList<>();

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
            }
            else
            {
                //Ran out of records to assign IPs
                //Issue an error
                break;
            }
        }

        if(changes.size() > 0)
        {
            AmazonRoute53 r53 = AmazonRoute53ClientBuilder.defaultClient();

            ChangeBatch changeBatch = new ChangeBatch();
            changeBatch.setChanges(changes);

            ChangeResourceRecordSetsRequest request = new ChangeResourceRecordSetsRequest();
            request.setChangeBatch(changeBatch);
            request.setHostedZoneId(hostedZoneId);

            System.out.println(request);

            r53.changeResourceRecordSets(request);
        }
    }

}
