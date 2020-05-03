import com.amazonaws.services.lambda.runtime.*;

import java.util.Map;

public class Handler implements RequestHandler<Map<String,String>,String>
{
    public String handleRequest(Map<String, String> stringStringMap, Context context)
    {
        String clusterARN = System.getenv("CLUSTER_ARN");
        String hostedZoneId = System.getenv("HOSTED_ZONE_ID");
        String serviceName = System.getenv("SERVICE_NAME");

        HostnameMaintainer.fixInvalidRecordsAndOrphanTasks(clusterARN, serviceName, hostedZoneId);

        return "200 OK";
    }
}
