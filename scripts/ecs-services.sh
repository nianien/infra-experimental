#!/usr/bin/env bash
# ecs-services.sh
# 用法:
#   ./ecs-services.sh start [count] [--no-force]
#   ./ecs-services.sh stop
#   ./ecs-services.sh status

# shellcheck source=/dev/null
. "$(dirname "$0")/env.sh"

# 需要管理的服务列表
SERVICES=(
  "demo-user-rpc-test"
  "demo-user-rpc-test1"
  "demo-order-rpc-test"
  demo-web-api-test
)

usage() {
  cat <<EOF
Usage:
  $0 start [count] [--no-force]   # 启动/恢复所有服务（默认滚动强制新部署）
  $0 stop                         # 暂停所有服务（desiredCount=0）
  $0 status                       # 查询所有服务状态

Env:
  AWS_PROFILE (default: $AWS_PROFILE)
  AWS_REGION  (default: $AWS_REGION)
  CLUSTER     (default: $CLUSTER)
  SERVICES    (space-separated list to override services)
EOF
}

update_one_service() {
  # $1=service  $2=desired  $3=force(true/false)
  local svc="$1"
  local desired="$2"
  local force="$3"

  if [[ "$force" == "true" ]]; then
    aws ecs update-service \
      --cluster "$CLUSTER" \
      --service "$svc" \
      --desired-count "$desired" \
      --force-new-deployment \
      --region "$AWS_REGION" \
      --profile "$AWS_PROFILE" \
      >/dev/null
  else
    aws ecs update-service \
      --cluster "$CLUSTER" \
      --service "$svc" \
      --desired-count "$desired" \
      --region "$AWS_REGION" \
      --profile "$AWS_PROFILE" \
      >/dev/null
  fi
}

print_deployments_table() {
  # $1=service
  local svc="$1"
  aws ecs describe-services \
    --cluster "$CLUSTER" \
    --services "$svc" \
    --region "$AWS_REGION" \
    --profile "$AWS_PROFILE" \
    --query 'services[0].deployments[].{id:id,status:status,rollout:rolloutState,createdAt:createdAt,desired:desiredCount,running:runningCount}' \
    --output table
}

ACTION="${1:-}"
case "$ACTION" in
  start)
    COUNT="${2:-1}"
    FORCE_NEW="true"
    # 兼容参数顺序: start --no-force   或   start 3 --no-force
    if [[ "${2:-}" == "--no-force" || "${3:-}" == "--no-force" ]]; then
      FORCE_NEW="false"
      # 若用户写了: start --no-force（未给 count），则仍使用默认 1
      if [[ "${2:-}" == "--no-force" ]]; then COUNT="1"; fi
    fi

    echo "=== START (count=$COUNT, forceNewDeployment=$FORCE_NEW) ==="
    # --- 第一阶段：对所有服务发送启动/更新命令 ---
    declare -a FAILED_START=()
    for svc in "${SERVICES[@]}"; do
      echo "----------------------------------------"
      echo "Sending update to: $svc"
      if update_one_service "$svc" "$COUNT" "$FORCE_NEW"; then
        echo "OK  : $svc (update sent)"
      else
        echo "FAIL: $svc (update failed)"
        FAILED_START+=("$svc")
      fi
    done

    # --- 第二阶段：统一查询部署进度 ---
    echo "========================================"
    echo "Deployment progress:"
    for svc in "${SERVICES[@]}"; do
      echo "----------------------------------------"
      echo "Service: $svc"
      print_deployments_table "$svc"
    done

    # 如果有失败的服务，最后给出列表并返回非零
    if [[ "${#FAILED_START[@]}" -gt 0 ]]; then
      echo "========================================"
      echo "Some services failed to start:"
      for x in "${FAILED_START[@]}"; do echo " - $x"; done
      exit 1
    fi
    ;;

  stop)
    echo "=== STOP (desiredCount=0) ==="
    declare -a FAILED_STOP=()
    for svc in "${SERVICES[@]}"; do
      echo "----------------------------------------"
      echo "Stopping: $svc"
      if update_one_service "$svc" "0" "false"; then
        echo "OK  : $svc stopped (desired=0)"
      else
        echo "FAIL: $svc stop failed"
        FAILED_STOP+=("$svc")
      fi
    done

    echo "========================================"
    echo "Current deployments after stop:"
    for svc in "${SERVICES[@]}"; do
      echo "----------------------------------------"
      echo "Service: $svc"
      print_deployments_table "$svc"
    done

    if [[ "${#FAILED_STOP[@]}" -gt 0 ]]; then
      echo "========================================"
      echo "Some services failed to stop:"
      for x in "${FAILED_STOP[@]}"; do echo " - $x"; done
      exit 1
    fi
    ;;

  status)
    echo "=== STATUS ==="
    for svc in "${SERVICES[@]}"; do
      echo "----------------------------------------"
      echo "Service: $svc"
      print_deployments_table "$svc"
    done
    ;;

  *)
    usage
    exit 1
    ;;
esac