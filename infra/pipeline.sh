#!/usr/bin/env bash
set -euo pipefail

# ===========================================
# pipeline.sh
# - 必选/可选参数都解析；可选为空时不覆盖模板默认值
# - 自动派生 PipelineName（未传时 = deploy-<service>）
# - 可选 --validate 仅做模板校验
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
# 可选
BRANCH_NAME="master"
MODULE_PATH="."

# =========== 参数解析 ===========
while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo|--repo-name)         REPO_NAME="$2"; shift 2 ;;
    --service)                  SERVICE_NAME="$2"; shift 2 ;;
    --sd-id)                    SD_ID="$2"; shift 2 ;;
    --pipeline)                 PIPELINE_NAME="$2"; shift 2 ;;
    --branch)                   BRANCH_NAME="$2"; shift 2 ;;
    --module)                   MODULE_PATH="$2"; shift 2 ;;
    -h|--help)
      echo "Usage: $0 --repo <org/repo> --service <name> --sd-id <srv-xxx> [--pipeline <name>] [--branch <name>] [--module <path>] [--validate]"
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

# 若传了空字符串，回落到默认值（防止外部传空覆盖）
[[ -z "${MODULE_PATH}" ]] && MODULE_PATH="."
[[ -z "${BRANCH_NAME}" ]] && BRANCH_NAME="master"

# 自动生成 ECS 日志组名
ECS_LOG_GROUP_NAME="/ecs/${SERVICE_NAME}"
LG_RETENTION_DAYS=30

echo "==> profile=$AWS_PROFILE region=$AWS_REGION"
echo "==> service=$SERVICE_NAME pipeline=$PIPELINE_NAME stack=$STACK_NAME"
echo "==> repo=$REPO_NAME branch=$BRANCH_NAME module=$MODULE_PATH"
echo "==> ecs_log_group_name=$ECS_LOG_GROUP_NAME retention_days=$LG_RETENTION_DAYS"

# ---------------- 模板校验（失败则终止，成功继续执行） ----------------
echo "==> Validating template syntax: $PIPELINE_TEMPLATE"
if aws cloudformation validate-template \
    --template-body "file://$PIPELINE_TEMPLATE" \
    --region "$AWS_REGION" --profile "$AWS_PROFILE" >/dev/null; then
  echo "✅ Template valid, continue..."
else
  echo "❌ Template invalid, abort." >&2
  exit 1
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
    echo "❌ Stack in $STACK_STATUS and AUTO_DELETE=0. Abort to preserve resources." >&2
    exit 2
  fi
fi

# ---------------- 组装参数 ----------------
PARAMS=(
  "PipelineName=${PIPELINE_NAME}"
  "ServiceName=${SERVICE_NAME}"
  "SdServiceId=${SD_ID}"
  "RepoName=${REPO_NAME}"
  "BranchName=${BRANCH_NAME}"
  "ModulePath=${MODULE_PATH}"
)

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
echo "  --variables name=LANE,value=default name=DESIRED_COUNT,value=1"