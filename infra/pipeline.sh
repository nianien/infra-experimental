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

# =========== 全局资源（必须已存在） ===========
AWS_PROFILE="${AWS_PROFILE:-default}"
AWS_REGION="${AWS_REGION:-us-east-1}"
PIPELINE_TEMPLATE="${PIPELINE_TEMPLATE:-../infra/pipeline.yaml}"

# =========== 参数定义 ===========
# 必选
PIPELINE_NAME=""
SERVICE_NAME=""
SD_ID=""
REPO_NAME=""

# 可选（允许为空）
CONNECTION_ARN=""
ARTIFACT_BUCKET_NAME=""
CLOUDFORMATION_DEPLOY_ROLE_ARN=""
CODEPIPELINE_ROLE_ARN=""
CODEBUILD_ROLE_ARN=""
BRANCH_NAME=""
TEMPLATE_PATH=""
IMAGE_TAG_FORMAT=""
ECR_REPOSITORY_URI=""
CLUSTER_NAME=""
EXECUTION_ROLE_ARN=""
TASK_ROLE_ARN=""
SUBNETS=""
SECURITY_GROUPS=""
ASSIGN_PUBLIC_IP=""
LOG_STREAM_PREFIX=""
DO_VALIDATE=0

# =========== 参数解析 ===========
while [[ $# -gt 0 ]]; do
  case "$1" in
    --pipeline-name)              PIPELINE_NAME="$2"; shift 2 ;;
    --service)                    SERVICE_NAME="$2"; shift 2 ;;
    --sd-id)                      SD_ID="$2"; shift 2 ;;
    --repo|--repo-name)           REPO_NAME="$2"; shift 2 ;;
    --connection-arn)             CONNECTION_ARN="$2"; shift 2 ;;
    --artifact-bucket-name)       ARTIFACT_BUCKET_NAME="$2"; shift 2 ;;
    --cloudformation-deploy-role-arn) CLOUDFORMATION_DEPLOY_ROLE_ARN="$2"; shift 2 ;;
    --codepipeline-role-arn)      CODEPIPELINE_ROLE_ARN="$2"; shift 2 ;;
    --codebuild-role-arn)         CODEBUILD_ROLE_ARN="$2"; shift 2 ;;
    --branch|--branch-name)       BRANCH_NAME="$2"; shift 2 ;;
    --template-path)              TEMPLATE_PATH="$2"; shift 2 ;;
    --image-tag-format)           IMAGE_TAG_FORMAT="$2"; shift 2 ;;
    --ecr-repository-uri)         ECR_REPOSITORY_URI="$2"; shift 2 ;;
    --cluster-name)               CLUSTER_NAME="$2"; shift 2 ;;
    --execution-role-arn)         EXECUTION_ROLE_ARN="$2"; shift 2 ;;
    --task-role-arn)              TASK_ROLE_ARN="$2"; shift 2 ;;
    --subnets)                    SUBNETS="$2"; shift 2 ;;
    --security-groups)            SECURITY_GROUPS="$2"; shift 2 ;;
    --assign-public-ip)           ASSIGN_PUBLIC_IP="$2"; shift 2 ;;
    --log-stream-prefix)          LOG_STREAM_PREFIX="$2"; shift 2 ;;
    --validate)                   DO_VALIDATE=1; shift ;;
    -h|--help)
      echo "Usage: $0 --repo <org/repo> --service <name> --sd-id <srv-xxx> [options]"
      exit 0 ;;
    *) echo "Unknown arg: $1"; exit 1 ;;
  esac
done

# =========== 校验必选参数 ===========
[[ -z "$REPO_NAME" ]]    && { echo "❌ 缺少必选参数: --repo"; exit 1; }
[[ -z "$SERVICE_NAME" ]] && { echo "❌ 缺少必选参数: --service"; exit 1; }
[[ -z "$SD_ID" ]]        && { echo "❌ 缺少必选参数: --sd-id"; exit 1; }

# 自动推导 PipelineName
if [[ -z "$PIPELINE_NAME" ]]; then
  PIPELINE_NAME="deploy-${SERVICE_NAME}"
fi

STACK_NAME="${PIPELINE_NAME}-pipeline"

echo "==> profile=$AWS_PROFILE region=$AWS_REGION"
echo "==> pipeline=$PIPELINE_NAME stack=$STACK_NAME"
echo "==> repo=$REPO_NAME branch=$BRANCH_NAME service=$SERVICE_NAME"

# =========== 可选模板验证 ===========
if [[ $DO_VALIDATE -eq 1 ]]; then
  aws cloudformation validate-template \
    --template-body "file://$PIPELINE_TEMPLATE" \
    --region "$AWS_REGION" --profile "$AWS_PROFILE"
  echo "✅ 模板语法校验通过"
  exit 0
fi

# =========== 栈检查 ===========
STACK_STATUS=$(aws cloudformation describe-stacks \
  --stack-name "$STACK_NAME" \
  --region "$AWS_REGION" --profile "$AWS_PROFILE" \
  --query "Stacks[0].StackStatus" --output text 2>/dev/null || echo "NOT_FOUND")

if [[ "$STACK_STATUS" == "ROLLBACK_COMPLETE" && "$AUTO_DELETE" == "1" ]]; then
  echo "==> 删除 ROLLBACK_COMPLETE 栈: $STACK_NAME"
  aws cloudformation delete-stack --stack-name "$STACK_NAME" --region "$AWS_REGION" --profile "$AWS_PROFILE"
  aws cloudformation wait stack-delete-complete --stack-name "$STACK_NAME" --region "$AWS_REGION" --profile "$AWS_PROFILE"
fi

# =========== 构建参数数组 ===========
PARAMS=(
  "PipelineName=${PIPELINE_NAME}"
  "ServiceName=${SERVICE_NAME}"
  "SdServiceId=${SD_ID}"
  "RepoName=${REPO_NAME}"
)

append_param() {
  local key="$1"
  local val="$2"
  if [[ -n "${val:-}" ]]; then
    PARAMS+=("${key}=${val}")
  fi
}

append_param ConnectionArn                "$CONNECTION_ARN"
append_param ArtifactBucketName           "$ARTIFACT_BUCKET_NAME"
append_param CloudFormationDeployRoleArn  "$CLOUDFORMATION_DEPLOY_ROLE_ARN"
append_param CodePipelineRoleArn          "$CODEPIPELINE_ROLE_ARN"
append_param CodeBuildRoleArn             "$CODEBUILD_ROLE_ARN"
append_param BranchName                   "$BRANCH_NAME"
append_param TemplatePath                 "$TEMPLATE_PATH"
append_param ImageTagFormat               "$IMAGE_TAG_FORMAT"
append_param EcrRepositoryUri             "$ECR_REPOSITORY_URI"
append_param ClusterName                  "$CLUSTER_NAME"
append_param ExecutionRoleArn             "$EXECUTION_ROLE_ARN"
append_param TaskRoleArn                  "$TASK_ROLE_ARN"
append_param Subnets                      "$SUBNETS"
append_param SecurityGroups               "$SECURITY_GROUPS"
append_param AssignPublicIp               "$ASSIGN_PUBLIC_IP"
append_param LogStreamPrefix              "$LOG_STREAM_PREFIX"

# =========== 部署 Pipeline ===========
set -x
aws cloudformation deploy \
  --stack-name "$STACK_NAME" \
  --template-file "$PIPELINE_TEMPLATE" \
  --capabilities CAPABILITY_NAMED_IAM \
  --region "$AWS_REGION" --profile "$AWS_PROFILE" \
  --parameter-overrides "${PARAMS[@]}"
set +x

echo "✅ Pipeline 部署完成：$PIPELINE_NAME"
echo "👉 示例触发："
echo "aws codepipeline start-pipeline-execution --name $PIPELINE_NAME --region $AWS_REGION --profile $AWS_PROFILE \\"
echo "  --variables name=LANE,value=gray name=DESIRED,value=1 name=CONT_PORT,value=8081"