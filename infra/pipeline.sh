#!/usr/bin/env bash
set -euo pipefail

# ===========================================
# pipeline.sh
# - 必选/可选参数都解析；可选为空时不覆盖模板默认值
# - 自动派生 PipelineName（未传时 = deploy-<service>）
# - 处理 ROLLBACK_COMPLETE / UPDATE_ROLLBACK_FAILED
# - 可选 validate 仅做模板校验
# ===========================================

. "$(dirname "$0")/env.sh"  # 保留你原有的环境变量加载

# 全局环境（可被外部覆盖）
AUTO_DELETE="${AUTO_DELETE:-1}"     # 1=回滚栈自动删除；0=保留
PIPELINE_TEMPLATE="${PIPELINE_TEMPLATE:-$(dirname "$0")/pipeline.yaml}"
DEBUG="${DEBUG:-0}"
[[ "$DEBUG" == "1" ]] && set -x

# =========== 参数定义 ===========
# 必选
REPO_NAME=""
SERVICE_NAME=""
SD_ID=""
PIPELINE_NAME="${PIPELINE_NAME:-}"

# 可选（仅当非空时传给 CFN，从而不覆盖模板默认值）
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

# Pipeline 环境变量
LANE_NAME=""
CONTAINER_PORT=""

DO_VALIDATE=0

# =========== 参数解析 ===========
while [[ $# -gt 0 ]]; do
  case "$1" in
    # 必选
    --repo|--repo-name)         REPO_NAME="$2"; shift 2 ;;
    --service|--service-name)   SERVICE_NAME="$2"; shift 2 ;;
    --sd-id|--sd-service-id)    SD_ID="$2"; shift 2 ;;
    --pipeline|--pipeline-name) PIPELINE_NAME="$2"; shift 2 ;;

    # 可选（与模板参数名一一对应）
    --connection-arn)                   CONNECTION_ARN="$2"; shift 2 ;;
    --artifact-bucket)                  ARTIFACT_BUCKET_NAME="$2"; shift 2 ;;
    --cloudformation-deploy-role-arn)   CLOUDFORMATION_DEPLOY_ROLE_ARN="$2"; shift 2 ;;
    --codepipeline-role-arn)            CODEPIPELINE_ROLE_ARN="$2"; shift 2 ;;
    --codebuild-role-arn)               CODEBUILD_ROLE_ARN="$2"; shift 2 ;;
    --branch|--branch-name)             BRANCH_NAME="$2"; shift 2 ;;
    --template-path)                    TEMPLATE_PATH="$2"; shift 2 ;;
    --image-tag-format)                 IMAGE_TAG_FORMAT="$2"; shift 2 ;;
    --ecr-repo-uri)                     ECR_REPOSITORY_URI="$2"; shift 2 ;;
    --cluster-name)                     CLUSTER_NAME="$2"; shift 2 ;;
    --execution-role-arn)               EXECUTION_ROLE_ARN="$2"; shift 2 ;;
    --task-role-arn)                    TASK_ROLE_ARN="$2"; shift 2 ;;
    --subnets)                          SUBNETS="$2"; shift 2 ;;
    --security-groups)                  SECURITY_GROUPS="$2"; shift 2 ;;
    --assign-public-ip)                 ASSIGN_PUBLIC_IP="$2"; shift 2 ;;
    --lane)                             LANE_NAME="$2"; shift 2 ;;
    --port)                             CONTAINER_PORT="$2"; shift 2 ;;
    --validate) DO_VALIDATE=1; shift ;;
    -h|--help)
      echo "Usage: $0 --repo <org/repo> --service <name> --sd-id <srv-xxx> [options]"
      exit 0 ;;
    *) echo "Unknown arg: $1"; exit 1 ;;
  esac
done

# ---------------- 必填校验 & 默认派生 ----------------
[[ -z "$REPO_NAME" ]]    && { echo "❌ 缺少 --repo"; exit 1; }
[[ -z "$SERVICE_NAME" ]] && { echo "❌ 缺少 --service"; exit 1; }
[[ -z "$SD_ID" ]]        && { echo "❌ 缺少 --sd-id"; exit 1; }

if [[ -z "$PIPELINE_NAME" ]]; then
  PIPELINE_NAME="deploy-${SERVICE_NAME}"
fi
STACK_NAME="${PIPELINE_NAME}-pipeline"

# 自动生成 ECS 日志组名
ECS_LOG_GROUP_NAME="/ecs/${SERVICE_NAME}"
LG_RETENTION_DAYS=30

echo "==> profile=$AWS_PROFILE region=$AWS_REGION"
echo "==> pipeline=$PIPELINE_NAME stack=$STACK_NAME"
echo "==> repo=$REPO_NAME branch=${BRANCH_NAME:-<template-default>} service=$SERVICE_NAME"
echo "==> template=$PIPELINE_TEMPLATE lane=${LANE_NAME:-<template-default>} container_port=${CONTAINER_PORT:-<template-default>}"
echo "==> ecs_log_group_name=$ECS_LOG_GROUP_NAME retention_days=$LG_RETENTION_DAYS"

# ---------------- 仅模板校验 ----------------
if [[ $DO_VALIDATE -eq 1 ]]; then
  aws cloudformation validate-template \
    --template-body "file://$PIPELINE_TEMPLATE" \
    --region "$AWS_REGION" --profile "$AWS_PROFILE" >/dev/null
  echo "✅ Template valid"
  exit 0
fi

# ---------------- 工具函数：仅当不存在时创建日志组 ----------------
ensure_log_group() {
  local name="$1"
  local retention="${2:-30}"
  local q="logGroups[?logGroupName==\`$name\`]|length(@)"
  local exists
  exists="$(aws logs describe-log-groups \
              --log-group-name-prefix "$name" \
              --query "$q" --output text \
              --region "$AWS_REGION" --profile "$AWS_PROFILE" 2>/dev/null || echo 0)"
  if [[ "$exists" != "0" ]]; then
    echo "==> Log group exists: $name"
    return 0
  fi
  echo "==> Creating log group: $name (retention=$retention)"
  aws logs create-log-group \
    --log-group-name "$name" \
    --region "$AWS_REGION" --profile "$AWS_PROFILE" || true
  aws logs put-retention-policy \
    --log-group-name "$name" \
    --retention-in-days "$retention" \
    --region "$AWS_REGION" --profile "$AWS_PROFILE" || true
}

# ---------------- 在部署前确保日志组存在（自动判断，无需参数） ----------------
ensure_log_group "$ECS_LOG_GROUP_NAME" "$LG_RETENTION_DAYS"

# ---------------- 栈状态预处理 ----------------
STACK_STATUS=$(aws cloudformation describe-stacks \
  --stack-name "$STACK_NAME" \
  --region "$AWS_REGION" --profile "$AWS_PROFILE" \
  --query 'Stacks[0].StackStatus' --output text 2>/dev/null || echo NOT_FOUND)
echo "STACK_STATUS=$STACK_STATUS"
if [[ "$STACK_STATUS" =~ ^[A-Z_]*(COMPLETE|FAILED)$ ]]; then
  if [[ "$AUTO_DELETE" == "1" ]]; then
    echo "==> $STACK_NAME in final state ($STACK_STATUS). Deleting..."
    aws cloudformation delete-stack --stack-name "$STACK_NAME" \
      --region "$AWS_REGION" --profile "$AWS_PROFILE"
    aws cloudformation wait stack-delete-complete --stack-name "$STACK_NAME" \
      --region "$AWS_REGION" --profile "$AWS_PROFILE"
    STACK_STATUS="NOT_FOUND"
  else
    echo "❌ Stack is ROLLBACK_COMPLETE and AUTO_DELETE=0. Abort to preserve resources." >&2
    exit 2
  fi
fi

# ---------------- 组装参数（可选为空则不传） ----------------
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

append_param LaneName                     "$LANE_NAME"
append_param ContainerPort                "$CONTAINER_PORT"

# ---------------- 部署 ----------------
set -x
aws cloudformation deploy \
  --stack-name "$STACK_NAME" \
  --template-file "$PIPELINE_TEMPLATE" \
  --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM \
  --region "$AWS_REGION" --profile "$AWS_PROFILE" \
  --parameter-overrides "${PARAMS[@]}"
set +x

echo "✅ Pipeline 就绪：$PIPELINE_NAME"
echo "👉 触发示例（镜像由 CodeBuild 产出；只需传 lane/desired_count/port）："
echo "aws codepipeline start-pipeline-execution --name $PIPELINE_NAME --region $AWS_REGION --profile $AWS_PROFILE \\"
echo "  --variables name=LANE,value=default name=DESIRED_COUNT,value=1 name=CONTAINER_PORT,value=8081"