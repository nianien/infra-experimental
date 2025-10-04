#!/usr/bin/env bash
set -euo pipefail

# ===== 可按需修改或用环境变量覆盖 =====
PROJECT_NAME="${PROJECT_NAME:-infra-experimental}"
AWS_PROFILE="${AWS_PROFILE:-nianien}"
AWS_REGION="${AWS_REGION:-us-east-1}"
# 可选：覆盖镜像版本（不设则由 buildspec 用 commit hash 生成）
BUILD_VERSION="${BUILD_VERSION:-}"

# ===== 触发构建 =====
echo ">> Start CodeBuild: $PROJECT_NAME (profile=$AWS_PROFILE, region=$AWS_REGION)"
START_ARGS=( --project-name "$PROJECT_NAME" --profile "$AWS_PROFILE" --region "$AWS_REGION" )
if [[ -n "$BUILD_VERSION" ]]; then
  START_ARGS+=( --environment-variables-override "name=BUILD_VERSION,value=$BUILD_VERSION" )
fi

BUILD_ID=$(aws codebuild start-build "${START_ARGS[@]}" --query 'build.id' --output text)
echo ">> Build started: $BUILD_ID"

# ===== 轮询直到完成 =====
STATUS="IN_PROGRESS"
while [[ "$STATUS" == "IN_PROGRESS" || "$STATUS" == "QUEUED" ]]; do
  sleep 5
  STATUS=$(aws codebuild batch-get-builds \
    --ids "$BUILD_ID" \
    --profile "$AWS_PROFILE" \
    --region "$AWS_REGION" \
    --query 'builds[0].buildStatus' \
    --output text 2>/dev/null || echo "UNKNOWN")
  echo ">> Status: $STATUS"
done

# ===== 最终结果 & 退出码 =====
echo ">> Final: $STATUS"
case "$STATUS" in
  SUCCEEDED) exit 0 ;;
  FAILED|FAULT|TIMED_OUT|CANCELED|STOPPED) exit 1 ;;
  *) echo "Unknown status: $STATUS" ; exit 2 ;;
esac