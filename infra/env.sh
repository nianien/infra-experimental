#!/usr/bin/env bash
set -euo pipefail

export AWS_PAGER=""  # 防止 CLI 卡分页

# AWS basics
export AWS_PROFILE="${AWS_PROFILE:-nianien}"
export AWS_REGION="${AWS_REGION:-us-east-1}"

# ECS basics
export CLUSTER="${CLUSTER:-demo-cluster}"

# Optional namespace for Cloud Map inspection
export NAMESPACE_NAME="${NAMESPACE_NAME:-test.local}"


# =========== 共享资源（for pipeline） ===========
SHARED_BUCKET="${SHARED_BUCKET:-codepipeline-us-east-1-3622cd43956e-4d23-9284-a18746ff6c07}"
SHARED_CFN_ROLE="${SHARED_CFN_ROLE:-arn:aws:iam::297997107448:role/CFN-Deploy-Role}"
CONNECTION_ARN="${CONNECTION_ARN:-arn:aws:codeconnections:us-east-1:297997107448:connection/9adfd918-2843-4147-9bc9-c4ee1a99bc39}"