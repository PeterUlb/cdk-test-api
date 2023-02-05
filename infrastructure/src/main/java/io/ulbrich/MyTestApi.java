package io.ulbrich;

import software.amazon.awscdk.BundlingOptions;
import software.amazon.awscdk.DockerVolume;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.services.apigateway.*;
import software.amazon.awscdk.services.lambda.*;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.assets.AssetOptions;
import software.amazon.awscdk.services.ssm.StringParameter;
import software.amazon.awscdk.services.stepfunctions.Parallel;
import software.amazon.awscdk.services.stepfunctions.StateMachine;
import software.amazon.awscdk.services.stepfunctions.StateMachineType;
import software.amazon.awscdk.services.stepfunctions.Succeed;
import software.amazon.awscdk.services.stepfunctions.tasks.LambdaInvoke;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

import static java.util.Collections.singletonList;
import static software.amazon.awscdk.BundlingOutput.ARCHIVED;

public class MyTestApi extends Construct {
    public MyTestApi(Construct scope, String id) {
        super(scope, id);

        Bucket someBucket = new Bucket(this, "SomeBucket");
        StringParameter someBucketParam = StringParameter.Builder.create(this, "SomeBucketParameter")
                .parameterName("/test/bucket/arn")
                .description("ARN of Bucket")
                .stringValue(someBucket.getBucketArn())
                .build();

        Version fooList = createJavaFunctionVersion("FooListFunction", "FooList", "foo-list.jar", "io.ulbrich.App", Map.of(
                "BUCKET", someBucket.getBucketName(),
                "BUCKET_PARAM", someBucketParam.getParameterName()), false);
        Version barList = createJavaFunctionVersion("BarListFunction", "BarList", "bar-list.jar", "io.ulbrich.App", Map.of(
                "BUCKET", someBucket.getBucketName(),
                "BUCKET_PARAM", someBucketParam.getParameterName()), true);

        StateMachine stateMachine = StateMachine.Builder.create(this, "MyStateMachine")
                .stateMachineType(StateMachineType.EXPRESS)
                .definition(Parallel.Builder.create(this, "Fetch All")
                        .build()
                        .branch(LambdaInvoke.Builder.create(this, "FetchFoo")
                                .lambdaFunction(fooList)
                                .build())
                        .branch(LambdaInvoke.Builder.create(this, "FetchBar")
                                .lambdaFunction(barList)
                                .build())
                        .branch(LambdaInvoke.Builder.create(this, "FetchFoo2")
                                .lambdaFunction(fooList)
                                .build())
                        .next(Succeed.Builder.create(this, "Finished").build()))
                .build();


        Version listCoordinator = createJavaFunctionVersion("ListCoordinatorFunction", "ListCoordinator", "list-coordinator.jar", "io.ulbrich.App", Map.of(
                "SM_LIST_ARN", stateMachine.getStateMachineArn()), true);
        stateMachine.grantStartSyncExecution(listCoordinator);

        someBucket.grantReadWrite(fooList);
        someBucket.grantRead(barList);

        RestApi api = RestApi.Builder.create(this, "My-API")
                .defaultCorsPreflightOptions(CorsOptions.builder().allowOrigins(Cors.ALL_ORIGINS).build())
                .restApiName("My Api").description("Playground API.")
                .defaultMethodOptions(MethodOptions.builder().authorizationType(AuthorizationType.IAM).build())
                .build();

        api.getRoot()
                .addResource("lambda")
                .addResource("{id}")
                .addMethod("GET", LambdaIntegration.Builder.create(listCoordinator).build());

        Resource sfnResource = api.getRoot().addResource("sfn");
        Resource sfnIdResource = sfnResource.addResource("{id}");

        sfnIdResource.addMethod("GET",
                StepFunctionsIntegration.startExecution(stateMachine, StepFunctionsExecutionIntegrationOptions.builder()
                        .authorizer(true)
                        .headers(true)
                        .path(true)
                        .querystring(true)
                        .requestContext(RequestContext.builder()
                                // NOTE: This corresponds to requestContext>identity>accountID, not requestContext>accountId in LAMBDA_PROXY
                                // So it is the account id calling the API, not the api owner
                                .accountId(true)
                                .apiId(true)
                                .apiKey(true)
                                .authorizerPrincipalId(true)
                                .caller(true)
                                .cognitoAuthenticationProvider(true)
                                .cognitoAuthenticationType(true)
                                .cognitoIdentityId(true)
                                .cognitoIdentityPoolId(true)
                                .httpMethod(true)
                                .requestId(true)
                                .resourceId(true)
                                .resourcePath(true)
                                .sourceIp(true)
                                .stage(true)
                                .userArn(true)
                                .user(true)
                                .userAgent(true)
                                .build())
                        .build())
        );
    }

    private Version createJavaFunctionVersion(String functionId, String directory, String jarName, String handlerMethod, Map<String, String> environment, boolean snapStart) { // TODO: Builder
        if (!jarName.endsWith(".jar")) {
            jarName += ".jar";
        }
        List<String> commands = List.of(
                "/bin/sh",
                "-c",
                String.format("cd %1$s && mvn clean install && cp /asset-input/%1$s/target/%2$s /asset-output/", directory, jarName)
        );

        BundlingOptions.Builder bundlingOptions = BundlingOptions.builder()
                .command(commands)
                .image(Runtime.JAVA_11.getBundlingImage())
                .volumes(singletonList(
                        // Mount local .m2 repo to avoid download all the dependencies again inside the container
                        DockerVolume.builder()
                                .hostPath(System.getProperty("user.home") + "/.m2/")
                                .containerPath("/root/.m2/")
                                .build()
                ))
                .user("root")
                .outputType(ARCHIVED);

        Function function = Function.Builder.create(this, functionId)
                .runtime(Runtime.JAVA_11)
                .code(Code.fromAsset("../software/", AssetOptions.builder()
                        .bundling(bundlingOptions
                                .command(commands)
                                .build())
                        .build()))
                .handler(handlerMethod)
                .environment(environment)
                .memorySize(1024)
                .timeout(Duration.seconds(10))
                .logRetention(RetentionDays.ONE_WEEK)
                .build();

        if (snapStart) {
            ((CfnFunction) function.getNode().getDefaultChild()).addPropertyOverride("SnapStart", Map.of("ApplyOn", "PublishedVersions"));
            // Publish a version
            return Version.Builder.create(this, functionId + "Version").lambda(function).build();
        }
        return function.getCurrentVersion();
    }
}