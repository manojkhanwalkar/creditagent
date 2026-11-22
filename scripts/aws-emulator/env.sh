# Source this (`source scripts/aws-emulator/env.sh`) in a shell to point CreditAgent's services at the
# AWS Emulator that scripts/aws-emulator/start.sh started: every DynamoDB table goes to it, signed with
# the keys of this emulator run, over HTTPS trusted through a combined trust store.
#
# Only private _ae_* names are used for its own work, and they are unset again: the services read PORT
# (and other common names) from the environment, so this file must not change them.

if [[ "${BASH_SOURCE[0]:-}" == "$0" && -z "${ZSH_EVAL_CONTEXT:-}" ]]; then
  echo "Source this file, do not run it: source scripts/aws-emulator/env.sh" >&2
  exit 1
fi

_ae_port="${AWS_EMULATOR_PORT:-9297}"
_ae_dir="$HOME/.aws/emulator/$_ae_port"
if [[ ! -f "$_ae_dir/emulator_credentials" || ! -f "$_ae_dir/creditagent-truststore.p12" ]]; then
  echo "AWS Emulator is not ready on port $_ae_port: run scripts/aws-emulator/start.sh first" >&2
  unset _ae_port _ae_dir
  return 1
fi

# Never real AWS credentials in this shell: the SDK would prefer them and the emulator would refuse them.
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_PROFILE

export AWS_ENDPOINT_URL="https://127.0.0.1:$_ae_port"
export AWS_SHARED_CREDENTIALS_FILE="$_ae_dir/emulator_credentials"
export AWS_CONFIG_FILE="$_ae_dir/config"
export AWS_CA_BUNDLE="$_ae_dir/ca.pem"
export AWS_EC2_METADATA_DISABLED=true
export AWS_REGION=us-east-1 AWS_DEFAULT_REGION=us-east-1
# The JVM trusts the JDK's certificates and the emulator's (built by start.sh). Appended, so options
# already set are kept; every JVM then prints "Picked up JAVA_TOOL_OPTIONS" on stderr.
_ae_trust="-Djavax.net.ssl.trustStore=$_ae_dir/creditagent-truststore.p12 -Djavax.net.ssl.trustStoreType=PKCS12 -Djavax.net.ssl.trustStorePassword=changeit"
case " ${JAVA_TOOL_OPTIONS:-} " in
  *" -Djavax.net.ssl.trustStore=$_ae_dir/creditagent-truststore.p12 "*) ;;
  *) export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }$_ae_trust" ;;
esac

# CreditAgent settings: every DynamoDB table goes to the emulator.
export DYNAMODB_ENDPOINT="https://127.0.0.1:$_ae_port"
export DYNAMODB_CREATE_TABLES=true
# Sign-in codes are written to the web server's log, not sent: the emulator (1.0.531) keeps SES in its
# sandbox (a sender can never be verified locally, so every email is refused) and does not implement SNS
# Publish to a phone number yet (aws/aws-emulator#572). The real SES/SNS calls are covered by unit tests.
export OTP_DELIVERY=log
# Subscriptions "paid" with the test gateway: every payment succeeds and nothing is charged.
export PAYMENTS_PROVIDER=test
echo "Pointed at AWS Emulator on https://127.0.0.1:$_ae_port"
unset _ae_port _ae_dir _ae_trust
