package io.ulbrich;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import software.amazon.lambda.powertools.metrics.Metrics;
import software.amazon.lambda.powertools.tracing.Tracing;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

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
public class App implements RequestHandler<Map<String, Object>, APIGatewayProxyResponseEvent> {

    @Tracing(captureMode = DISABLED)
    @Metrics(captureColdStart = true)
    public APIGatewayProxyResponseEvent handleRequest(final Map<String, Object> input, final Context context) {
        LambdaLogger logger = context.getLogger();
        logger.log("****INPUT***");
        logger.log(input.toString());
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-Custom-Header", "application/json");

        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
                .withHeaders(headers);
        try {
            final String pageContents = this.getPageContents("https://checkip.amazonaws.com");
            String output = String.format("{ \"message\": \"Hello from Foo\", \"location\": \"%s\" }", pageContents);

            return response
                    .withStatusCode(200)
                    .withBody(output);
        } catch (IOException e) {
            return response
                    .withBody("{}")
                    .withStatusCode(500);
        }
    }

    @Tracing(namespace = "getPageContents")
    private String getPageContents(String address) throws IOException {
        URL url = new URL(address);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream()))) {
            return br.lines().collect(Collectors.joining(System.lineSeparator()));
        }
    }
}