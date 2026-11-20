# Source this (`source scripts/aws-emulator/env.sh`) in a shell to point CreditAgent's services at the
# AWS Emulator that scripts/aws-emulator/start.sh started: every DynamoDB table goes to it, signed with
# the keys of this emulator run, over HTTPS trusted through a combined trust store.
PORT="${AWS_EMULATOR_PORT:-9297}"
_dir="$HOME/.aws/emulator/$PORT"
if [[ ! -f "$_dir/emulator_credentials" ]]; then
  echo "AWS Emulator is not running on port $PORT: run scripts/aws-emulator/start.sh first" >&2
  return 1 2>/dev/null || exit 1
fi

# Never real AWS credentials in this shell: the SDK would prefer them and the emulator would refuse them.
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_PROFILE

export AWS_ENDPOINT_URL="https://127.0.0.1:$PORT"
export AWS_SHARED_CREDENTIALS_FILE="$_dir/emulator_credentials"
export AWS_CONFIG_FILE="$_dir/config"
export AWS_CA_BUNDLE="$_dir/ca.pem"
export AWS_EC2_METADATA_DISABLED=true
export AWS_REGION=us-east-1 AWS_DEFAULT_REGION=us-east-1
# The JVM trusts the JDK's certificates and the emulator's (built by start.sh).
export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=$_dir/creditagent-truststore.p12 -Djavax.net.ssl.trustStoreType=PKCS12 -Djavax.net.ssl.trustStorePassword=changeit"

# CreditAgent settings: every DynamoDB table goes to the emulator.
export DYNAMODB_ENDPOINT="https://127.0.0.1:$PORT"
export DYNAMODB_CREATE_TABLES=true
# Sign-in codes are written to the web server's log, not sent: the emulator (1.0.531) keeps SES in its
# sandbox (a sender can never be verified locally, so every email is refused) and does not implement SNS
# Publish to a phone number yet. The real SES/SNS calls are covered by the web server's unit tests.
export OTP_DELIVERY=log
unset _dir
echo "Pointed at AWS Emulator on https://127.0.0.1:$PORT"
