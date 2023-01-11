package io.ulbrich;

import software.amazon.awscdk.BundlingOptions;
import software.amazon.awscdk.DockerVolume;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.services.apigateway.*;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.FunctionProps;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.assets.AssetOptions;
import software.amazon.awscdk.services.stepfunctions.StateMachine;
import software.amazon.awscdk.services.stepfunctions.StateMachineType;
import software.amazon.awscdk.services.stepfunctions.tasks.LambdaInvoke;
import software.constructs.Construct;

import java.util.Arrays;
import java.util.List;

import static java.util.Collections.singletonList;
import static software.amazon.awscdk.BundlingOutput.ARCHIVED;

public class MyTestApi extends Construct {

    public MyTestApi(Construct scope, String id) {
        super(scope, id);


        List<String> functionOnePackagingInstructions = Arrays.asList(
                "/bin/sh",
                "-c",
                "cd FunctionOne " +
                        "&& mvn clean install " +
                        "&& cp /asset-input/FunctionOne/target/functionone.jar /asset-output/"
        );

        BundlingOptions.Builder builderOptions = BundlingOptions.builder()
                .command(functionOnePackagingInstructions)
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

        Bucket someBucket = new Bucket(this, "SomeBucket");

        Function functionOne = new Function(this, "FunctionOne", FunctionProps.builder()
                .runtime(Runtime.JAVA_11)
                .code(Code.fromAsset("../software/", AssetOptions.builder()
                        .bundling(builderOptions
                                .command(functionOnePackagingInstructions)
                                .build())
                        .build()))
                .handler("io.ulbrich.App")
                .environment(java.util.Map.of(   // Java 9 or later
                        "BUCKET", someBucket.getBucketName()))
                .memorySize(1024)
                .timeout(Duration.seconds(10))
                .logRetention(RetentionDays.ONE_WEEK)
                .build());


        someBucket.grantReadWrite(functionOne);

        RestApi api = RestApi.Builder.create(this, "My-API")
                .defaultCorsPreflightOptions(CorsOptions.builder().allowOrigins(Cors.ALL_ORIGINS).build())
                .restApiName("My Api").description("Playground API.")
                .build();

        Resource lambdaResource = api.getRoot().addResource("lambda");
        Resource lambdaIdResource = lambdaResource.addResource("{id}");
        LambdaIntegration functionOneIntegration = LambdaIntegration.Builder.create(functionOne).build();
        lambdaIdResource.addMethod("POST", functionOneIntegration);
        lambdaIdResource.addMethod("GET", functionOneIntegration);
        lambdaIdResource.addMethod("DELETE", functionOneIntegration);

        Resource sfnResource = api.getRoot().addResource("sfn");
        Resource sfnIdResource = sfnResource.addResource("{id}");

        // StateMachine Testing
        StateMachine stateMachine = StateMachine.Builder.create(this, "MyStateMachine")
                .stateMachineType(StateMachineType.EXPRESS)
                .definition(LambdaInvoke.Builder.create(this, "MyLambdaTask")
                        .lambdaFunction(functionOne)
                        .build())
                .build();
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
}