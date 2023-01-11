package io.ulbrich;

import software.amazon.awscdk.BundlingOptions;
import software.amazon.awscdk.DockerVolume;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.services.apigateway.*;
import software.amazon.awscdk.services.lambda.FunctionProps;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.s3.assets.AssetOptions;
import software.amazon.awscdk.services.stepfunctions.StateMachine;
import software.amazon.awscdk.services.stepfunctions.StateMachineType;
import software.amazon.awscdk.services.stepfunctions.tasks.LambdaInvoke;
import software.constructs.Construct;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.s3.Bucket;

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

        Resource widget = api.getRoot().addResource("{id}");
        LambdaIntegration functionOneIntegration = new LambdaIntegration(functionOne);
        widget.addMethod("POST", functionOneIntegration);
        widget.addMethod("GET", functionOneIntegration);
        widget.addMethod("DELETE", functionOneIntegration);


        // StateMachine Testing
        StateMachine stateMachine = StateMachine.Builder.create(this, "MyStateMachine")
                .stateMachineType(StateMachineType.EXPRESS)
                .definition(LambdaInvoke.Builder.create(this, "MyLambdaTask")
                        .lambdaFunction(functionOne)
                        .build())
                .build();
        api.getRoot().addMethod("GET", StepFunctionsIntegration.startExecution(stateMachine));
    }
}