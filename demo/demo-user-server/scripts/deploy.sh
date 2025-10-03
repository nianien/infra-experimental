#!/bin/bash
set -euo pipefail

# -------------------------
# 参数处理
# -------------------------
AWS_PROFILE="${1:-nianien}"           # 默认 profile=nianien
AWS_REGION="${2:-us-east-1}"          # 默认 region=us-east-1
SERVICE_NAME="${3:-demo/user-service}" # 默认服务名
AWS_ACCOUNT_ID="${4:-$(aws sts get-caller-identity --query Account --output text --profile $AWS_PROFILE)}"

ECR_URI="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${SERVICE_NAME}"

echo "=== 部署参数 ==="
echo "AWS_PROFILE   : $AWS_PROFILE"
echo "AWS_REGION    : $AWS_REGION"
echo "SERVICE_NAME  : $SERVICE_NAME"
echo "AWS_ACCOUNT_ID: $AWS_ACCOUNT_ID"
echo "ECR_URI       : $ECR_URI"
echo "================"

# -------------------------
# 登录 ECR
# -------------------------
echo ">>> 登录 ECR ..."
aws ecr get-login-password --region "$AWS_REGION" --profile "$AWS_PROFILE" |
  docker login --username AWS --password-stdin "$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com"

# -------------------------
# 构建镜像
# -------------------------
echo ">>> 构建 Docker 镜像 ..."
docker build -t "$SERVICE_NAME:latest" -f docker/Dockerfile .

# -------------------------
# 打标签并推送
# -------------------------
echo ">>> 推送到 ECR ..."
docker tag "$SERVICE_NAME:latest" "$ECR_URI:latest"
docker push "$ECR_URI:latest"

echo ">>> 推送完成: $ECR_URI:latest"
