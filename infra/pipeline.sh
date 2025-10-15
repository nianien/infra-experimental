#!/usr/bin/env bash
set -euo pipefail

# 示例：
# bash ./pipeline.sh \
#   --repo nianien/infra-experimental \
#   --branch master \
#   --service demo-user-rpc \
#   --sd-id srv-g46enu54fe2bhajk \
#   --validate

. "$(dirname "$0")/env.sh"

AUTO_DELETE="${AUTO_DELETE:-1}"   # 默认自动清理 ROLLBACK_COMPLETE
[[ "${DEBUG:-}" == "1" ]] && set -x

# =========== 共享资源（必须已存在） ===========
SHARED_BUCKET="${SHARED_BUCKET:-}"
SHARED_CFN_ROLE="${SHARED_CFN_ROLE:-}"
CONNECTION_ARN="${CONNECTION_ARN:-}"

# 模板与路径
PIPELINE_TEMPLATE="${PIPELINE_TEMPLATE:-../infra/pipeline.yaml}"
ECS_TEMPLATE_PATH="${ECS_TEMPLATE_PATH:-ci/ecs-service-deploy.yaml}"

usage() {
  cat <<USAGE
Usage:
  AWS_PROFILE=.. AWS_REGION=.. SHARED_BUCKET=.. SHARED_CFN_ROLE=.. \\
  [AUTO_DELETE=0|1] [DEBUG=0|1] \\
  $0 --repo <owner/repo> --branch <branch> --service <service> --sd-id <srv-xxxxx> [--connection-arn <arn>] [--validate]

说明：
- 根据 --service 自动命名：
    PIPELINE_NAME = deploy-<service>
    STACK_NAME    = deploy-<service>-pipeline
- 模板中的全局默认值（Cluster/Subnets/SecurityGroups/Roles/AssignPublicIp/LogStreamPrefix）不覆盖。
- 运行时触发 Pipeline 仅需传：LANE / DESIRED / CONT_PORT。
USAGE
  exit 1
}

trap 'echo "❌ Error at line $LINENO: $BASH_COMMAND" >&2' ERR

# 解析参数
FULL_REPO=""; BRANCH=""; SERVICE_NAME=""; SD_ID=""; DO_VALIDATE=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --connection-arn) CONNECTION_ARN="$2"; shift 2 ;;
    --repo)           FULL_REPO="$2";      shift 2 ;;
    --branch)         BRANCH="$2";         shift 2 ;;
    --service)        SERVICE_NAME="$2";   shift 2 ;;
    --sd-id)          SD_ID="$2";          shift 2 ;;
    --validate)       DO_VALIDATE=1;       shift 1 ;;
    -h|--help)        usage ;;
    *) echo "Unknown arg: $1"; usage ;;
  esac
done

# 必填校验（给出更清晰报错）
[[ -z "${AWS_PROFILE:-}" ]]   && { echo "❌ 缺少 AWS_PROFILE（可在 env.sh 里或环境变量中设置）"; exit 1; }
[[ -z "${AWS_REGION:-}"  ]]   && { echo "❌ 缺少 AWS_REGION（可在 env.sh 里或环境变量中设置）"; exit 1; }
[[ -z "$SHARED_BUCKET"   ]]   && { echo "❌ 缺少 SHARED_BUCKET"; exit 1; }
[[ -z "$SHARED_CFN_ROLE" ]]   && { echo "❌ 缺少 SHARED_CFN_ROLE"; exit 1; }
[[ -z "$CONNECTION_ARN"  ]]   && { echo "❌ 缺少 CONNECTION_ARN"; exit 1; }

[[ -z "$FULL_REPO"    ]] && { echo "❌ 缺少 --repo（owner/repo）"; exit 1; }
[[ -z "$BRANCH"       ]] && { echo "❌ 缺少 --branch"; exit 1; }
[[ -z "$SERVICE_NAME" ]] && { echo "❌ 缺少 --service"; exit 1; }
[[ -z "$SD_ID"        ]] && { echo "❌ 缺少 --sd-id（srv-xxxxxx）"; exit 1; }

# 名称
slug() { echo "$1" | tr '[:upper:]' '[:lower:]' | tr -cs '[:alnum:]-' '-' | sed -E 's/^-+|-+$//g'; }
SERVICE_SLUG="$(slug "$SERVICE_NAME")"
PIPELINE_NAME="deploy-${SERVICE_SLUG}"
STACK_NAME="${PIPELINE_NAME}-pipeline"

# 校验共用资源与模板存在
aws s3api head-bucket --bucket "$SHARED_BUCKET" --profile "$AWS_PROFILE" >/dev/null 2>&1 \
  || { echo "❌ 共享桶不存在：$SHARED_BUCKET"; exit 1; }
aws iam get-role --role-name "$(basename "$SHARED_CFN_ROLE")" --profile "$AWS_PROFILE" >/dev/null 2>&1 \
  || { echo "❌ 共享 CFN 角色不存在：$SHARED_CFN_ROLE"; exit 1; }
[[ -f "$PIPELINE_TEMPLATE" ]] || { echo "❌ 找不到模板文件：$PIPELINE_TEMPLATE"; exit 1; }

echo "==> profile=$AWS_PROFILE region=$AWS_REGION"
echo "==> repo=$FULL_REPO branch=$BRANCH service=$SERVICE_NAME"
echo "==> pipeline=$PIPELINE_NAME stack=$STACK_NAME"
echo "==> bucket=$SHARED_BUCKET cfnRole=$SHARED_CFN_ROLE"
echo "==> connection=$CONNECTION_ARN template=$PIPELINE_TEMPLATE ecsTemplate=$ECS_TEMPLATE_PATH"

# 可选：本地校验模板 + 简单 YAML 检查
if [[ $DO_VALIDATE -eq 1 ]]; then
  echo "==> Validating CloudFormation template locally..."
  aws cloudformation validate-template \
    --template-body "file://$PIPELINE_TEMPLATE" \
    --region "$AWS_REGION" --profile "$AWS_PROFILE" >/dev/null \
    || { echo "❌ 模板语法校验失败：$PIPELINE_TEMPLATE"; exit 1; }

  if command -v yamllint >/dev/null 2>&1; then
    echo "==> yamllint (relaxed) pipeline-by-lane.yaml"
    yamllint -d "{extends: default, rules: {line-length: disable, truthy: disable}}" "$PIPELINE_TEMPLATE"
  fi
fi

# 栈是否存在
STACK_STATUS=$(aws cloudformation describe-stacks \
  --stack-name "$STACK_NAME" \
  --region "$AWS_REGION" --profile "$AWS_PROFILE" \
  --query "Stacks[0].StackStatus" --output text 2>/dev/null || echo "NOT_FOUND")

# 栈存在但 CodePipeline 不存在 → 删栈重建（避免 NotFound）
if [[ "$STACK_STATUS" != "NOT_FOUND" ]]; then
  if ! aws codepipeline get-pipeline --name "$PIPELINE_NAME" \
       --region "$AWS_REGION" --profile "$AWS_PROFILE" >/dev/null 2>&1; then
    echo "==> Stack exists but pipeline '$PIPELINE_NAME' not found. Recreating stack..."
    aws cloudformation delete-stack --stack-name "$STACK_NAME" --region "$AWS_REGION" --profile "$AWS_PROFILE"
    aws cloudformation wait stack-delete-complete --stack-name "$STACK_NAME" --region "$AWS_REGION" --profile "$AWS_PROFILE"
    STACK_STATUS="NOT_FOUND"
  fi
fi

# 如已存在且为 ROLLBACK_COMPLETE，自动删除（默认开）
if [[ "$STACK_STATUS" == "ROLLBACK_COMPLETE" && "$AUTO_DELETE" == "1" ]]; then
  echo "==> Deleting ROLLBACK_COMPLETE stack: $STACK_NAME"
  aws cloudformation delete-stack --stack-name "$STACK_NAME" --region "$AWS_REGION" --profile "$AWS_PROFILE"
  aws cloudformation wait stack-delete-complete --stack-name "$STACK_NAME" --region "$AWS_REGION" --profile "$AWS_PROFILE"
elif [[ "$STACK_STATUS" == "ROLLBACK_COMPLETE" && "$AUTO_DELETE" != "1" ]]; then
  echo "❌ 栈处于 ROLLBACK_COMPLETE。设置 AUTO_DELETE=1 可自动删除后重建。" >&2
  exit 1
fi

# 部署 Pipeline（仅传服务相关参数；不覆盖全局默认）
if ! aws cloudformation deploy \
  --stack-name "$STACK_NAME" \
  --template-file "$PIPELINE_TEMPLATE" \
  --capabilities CAPABILITY_NAMED_IAM \
  --region "$AWS_REGION" --profile "$AWS_PROFILE" \
  --parameter-overrides \
    ArtifactBucketName="$SHARED_BUCKET" \
    PipelineName="$PIPELINE_NAME" \
    CFNDeployRoleArn="$SHARED_CFN_ROLE" \
    ConnectionArn="$CONNECTION_ARN" \
    FullRepo="$FULL_REPO" \
    BranchName="$BRANCH" \
    ServiceName="$SERVICE_NAME" \
    SdServiceId="$SD_ID" \
    TemplatePath="$ECS_TEMPLATE_PATH"
then
  echo "❌ CloudFormation deploy 失败" >&2
  echo "== 参数回显 ==" >&2
  cat <<PARAMS >&2
STACK_NAME=$STACK_NAME
PIPELINE_TEMPLATE=$PIPELINE_TEMPLATE
ArtifactBucketName=$SHARED_BUCKET
PipelineName=$PIPELINE_NAME
CFNDeployRoleArn=$SHARED_CFN_ROLE
ConnectionArn=$CONNECTION_ARN
FullRepo=$FULL_REPO
BranchName=$BRANCH
ServiceName=$SERVICE_NAME
SdServiceId=$SD_ID
TemplatePath=$ECS_TEMPLATE_PATH
PARAMS

  echo "== 栈状态（若存在）==" >&2
  aws cloudformation describe-stacks \
    --stack-name "$STACK_NAME" \
    --region "$AWS_REGION" --profile "$AWS_PROFILE" \
    --query "Stacks[0].[StackStatus,StackStatusReason]" \
    --output table 2>/dev/null || echo "(stack not found)"

  echo "== 最近失败事件（若存在）==" >&2
  aws cloudformation describe-stack-events \
    --stack-name "$STACK_NAME" \
    --region "$AWS_REGION" --profile "$AWS_PROFILE" \
    --query "reverse(sort_by(StackEvents,&Timestamp))[?contains(ResourceStatus,'FAILED') || contains(ResourceStatus,'ROLLBACK')][].[Timestamp,LogicalResourceId,ResourceType,ResourceStatus,ResourceStatusReason]" \
    --output table 2>/dev/null || true

  exit 1
fi

echo "✅ Pipeline 就绪：$PIPELINE_NAME"
echo "👉 触发示例（镜像由 CodeBuild 产出；只需传 lane/desired/port）："
echo "aws codepipeline start-pipeline-execution --name $PIPELINE_NAME --region $AWS_REGION --profile $AWS_PROFILE \\"
echo "  --variables name=LANE,value=gray name=DESIRED,value=1 name=CONT_PORT,value=8081"