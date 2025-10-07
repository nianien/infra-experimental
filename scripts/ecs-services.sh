#!/usr/bin/env bash
set -euo pipefail

# shellcheck source=/dev/null
. "$(dirname "$0")/env.sh"

# 需要管理的服务列表
SERVICES=(
  "${SERVICE_DEMO_USER}"
  "${SERVICE_DEMO_ORDER}"
  "${SERVICE_DEMO_WEB}"
)

# ================== 帮助 ==================
usage() {
  cat <<EOF
Usage:
  $0 start [count] [--no-force]     # 启动/恢复，并默认强制触发新部署（拉取 :latest）
  $0 stop                           # 暂停（desiredCount=0）
  $0 status                         # 查看各服务状态/部署信息

Env:
  AWS_PROFILE (default: $AWS_PROFILE)
  AWS_REGION  (default: $AWS_REGION)
  CLUSTER     (default: $CLUSTER)

Examples:
  $0 start                # 每个服务1个副本，强制新部署
  $0 start 3              # 每个服务3个副本，强制新部署
  $0 start 2 --no-force   # 每个服务2个副本，不强制新部署
  $0 stop                 # 所有服务 desiredCount=0
  $0 status               # 查看状态
EOF
}

# ================== 子程序 ==================
aws_ecs_update() {
  local service="$1"
  local desired="$2"
  local force="$3" # "true" / "false"

  echo "→ Updating $service to desiredCount=$desired (forceNewDeployment=$force)"
  if [[ "$force" == "true" ]]; then
    aws ecs update-service \
      --cluster "$CLUSTER" \
      --service "$service" \
      --desired-count "$desired" \
      --force-new-deployment \
      --region "$AWS_REGION" \
      --profile "$AWS_PROFILE" \
      >/dev/null
  else
    aws ecs update-service \
      --cluster "$CLUSTER" \
      --service "$service" \
      --desired-count "$desired" \
      --region "$AWS_REGION" \
      --profile "$AWS_PROFILE" \
      >/dev/null
  fi
}

print_deployments_brief() {
  local service="$1"
  echo "   Deployments for $service:"
  aws ecs describe-services \
    --cluster "$CLUSTER" \
    --services "$service" \
    --region "$AWS_REGION" \
    --profile "$AWS_PROFILE" \
    --query 'services[0].deployments[].{id:id,status:status,rollout:rolloutState,taskDef:taskDefinition,createdAt:createdAt,desired:desiredCount,running:runningCount}' \
    --output table
}

status_one() {
  local service="$1"
  echo "----------------------------------------"
  echo "🔎 Service: $service"
  aws ecs describe-services \
    --cluster "$CLUSTER" \
    --services "$service" \
    --region "$AWS_REGION" \
    --profile "$AWS_PROFILE" \
    --query 'services[0].{service:serviceName,desired:desiredCount,running:runningCount,pending:pendingCount,primary:taskDefinition,lb:loadBalancers,deployments:deployments[*].{id:id,status:status,rollout:rolloutState,taskDef:taskDefinition,createdAt:createdAt}}' \
    --output json
}

# ================== 入口参数解析 ==================
ACTION="${1:-}"
shift || true

case "$ACTION" in
start)
  COUNT="${1:-1}"
  NO_FORCE="false"
  if [[ "${2:-}" == "--no-force" || "${1:-}" == "--no-force" ]]; then
    NO_FORCE="true"
    # 如果第一个参数就是 --no-force，则把副本数退回默认1
    [[ "${1:-}" == "--no-force" ]] && COUNT="1"
  fi

  echo "=== START all services (count=$COUNT, forceNewDeployment=$([[ "$NO_FORCE" == "true" ]] && echo false || echo true)) ==="
  for SVC in "${SERVICES[@]}"; do
    echo "----------------------------------------"
    echo "🔧 Processing: $SVC"
    if [[ "$NO_FORCE" == "true" ]]; then
      aws_ecs_update "$SVC" "$COUNT" "false"
    else
      aws_ecs_update "$SVC" "$COUNT" "true"
    fi
    echo "✅ Update sent. Showing deployments (should see a new deployment with rolloutState IN_PROGRESS or COMPLETED):"
    print_deployments_brief "$SVC"
  done
  echo "🎯 Done."
  ;;

stop)
  echo "=== STOP all services (desiredCount=0) ==="
  for SVC in "${SERVICES[@]}"; do
    echo "----------------------------------------"
    echo "🔧 Processing: $SVC"
    aws_ecs_update "$SVC" "0" "false"
    echo "✅ Stopped. Current deployments:"
    print_deployments_brief "$SVC"
  done
  echo "🎯 Done."
  ;;

status)
  echo "=== STATUS ==="
  for SVC in "${SERVICES[@]}"; do
    status_one "$SVC"
  done
  ;;

*)
  usage
  exit 1
  ;;
esac
