run pulsar-function example
https://pulsar.apache.org/docs/4.0.x/functions-quickstart/

```shell
bin/pulsar-admin tenants create test
bin/pulsar-admin namespaces create test/test-namespace

bin/pulsar-admin functions create \
   --function-config-file $PWD/pulsar-functions/java-examples/target/classes/example-function-config.yaml \
   --jar $PWD/pulsar-functions/java-examples/target/pulsar-functions-api-examples.jar
```