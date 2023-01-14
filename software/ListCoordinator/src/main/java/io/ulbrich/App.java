package io.ulbrich;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartSyncExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartSyncExecutionResponse;
import software.amazon.lambda.powertools.metrics.Metrics;
import software.amazon.lambda.powertools.tracing.Tracing;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static software.amazon.lambda.powertools.tracing.CaptureMode.DISABLED;

/**
 * Handler for requests to Lambda function.
 * The input is either APIGatewayProxyRequestEvent (API GW call)
 * or Map<String,Object> (StepFunctions call)
 * <p>
 * Note regarding IAM Authentication
 * <p>
 * Lambda directly:
 * <ul>
 *   <li>available by default with LAMBDA_PROXY integration</li>
 *   <li>identify caller: requestContext={identity={accountId=some-account, userArn=arn:aws:iam::some-account:user/some-user}}</li>
 *   <ul>
 *       <li>if RequestHandler&lt;APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent&gt; is used: input.getRequestContext().getIdentity().getUserArn();</li>
 *       <li>if RequestHandler&lt;Map&lt;String, Object&gt, APIGatewayProxyResponseEvent&gt; is used: manual lookup</li>
 *   </ul>
 * </ul>
 * StepFunction directly:
 * <ul>
 *   <li>requires CDK integration setting requestContext with userArn + accountId to generate respective mapping template (StepFunctionsExecutionIntegrationOptions)</li>
 *   <li>identify caller: requestContext={accountId=some-account, userArn=arn:aws:iam::some-account:user/some-user}</li>
 *   <ul><li>available manually in RequestHandler&lt;Map&lt;String, Object&gt, APIGatewayProxyResponseEvent&gt; event</li></ul>
 *   <li>Note: it would also be possible to get the attribute in "identity" similar to the lambda integration. But CDK decided to drop the identity envelope</li>
 * </ul>
 * <pre>{@code
 * #if ($includeIdentity)
 *     #set($inputString = "$inputString, @@identity@@:{")
 *     #foreach($paramName in $context.identity.keySet())
 *         #set($inputString = "$inputString @@$paramName@@: @@$util.escapeJavaScript($context.identity.get($paramName))@@")
 *         #if($foreach.hasNext)
 *             #set($inputString = "$inputString,")
 *         #end
 *     #end
 * #set($inputString = "$inputString }")
 * #end
 * }</pre>
 * See <a href="https://docs.aws.amazon.com/apigateway/latest/developerguide/api-gateway-mapping-template-reference.html#context-variable-reference">Mapping template reference</a>
 * <ul>
 *  <li>$context.identity.accountId</li>
 *  <li>$context.identity.userArn</li>
 * </ul>
 */
public class App implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    Gson gson = new GsonBuilder().setPrettyPrinting().create();

    @Tracing(captureMode = DISABLED)
    @Metrics(captureColdStart = true)
    public APIGatewayProxyResponseEvent handleRequest(final APIGatewayProxyRequestEvent input, final Context context) {
        LambdaLogger logger = context.getLogger();

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-Custom-Header", "application/json");


        try (SfnClient sfnClient = SfnClient.create()) {
            StartSyncExecutionRequest executionRequest = StartSyncExecutionRequest.builder()
                    .input("{}")
                    .stateMachineArn(System.getenv("SM_LIST_ARN"))
                    .name(UUID.randomUUID().toString())
                    .build();
            StartSyncExecutionResponse result = sfnClient.startSyncExecution(executionRequest);
            logger.log(result.status().toString());
            APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
                    .withHeaders(headers);
            switch (result.status()) {
                case SUCCEEDED:
                    return response
                            .withStatusCode(200)
                            .withBody(gson.toJson(new Response(input.getRequestContext().getIdentity().getAccountId(), result.output())));
                default:
                    logger.log(result.output());
                    return response
                            .withStatusCode(500)
                            .withBody("{}");
            }
        }
    }

    static class Response {
        private final String accountId;
        private final String output;

        public Response(String accountId, String output) {
            this.accountId = accountId;
            this.output = output;
        }

        public String getAccountId() {
            return accountId;
        }

        public String getOutput() {
            return output;
        }
    }
}