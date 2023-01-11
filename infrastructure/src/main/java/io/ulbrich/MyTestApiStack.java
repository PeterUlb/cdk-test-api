package io.ulbrich;

import software.constructs.Construct;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;

public class MyTestApiStack extends Stack {
    public MyTestApiStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public MyTestApiStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        // The code that defines your stack goes here

        new MyTestApi(this, "MyTestApi");
    }
}
