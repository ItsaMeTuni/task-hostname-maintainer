import com.amazonaws.services.lambda.runtime.*;

import java.util.List;
import java.util.Map;

public class Handler implements RequestHandler<Map<String,String>,String>
{
    public String handleRequest(Map<String, String> stringStringMap, Context context)
    {
        String clusterARN = System.getenv("CLUSTER_ARN");
        String hostedZoneId = System.getenv("HOSTED_ZONE_ID");

        HostnameMaintainer.fixInvalidRecordsAndOrphanTasks(clusterARN, hostedZoneId);

        return "200 OK";
    }
}
