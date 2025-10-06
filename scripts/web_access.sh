#!/usr/bin/env bash
set -e

# ===== 按需修改 =====
AWS_PROFILE="nianien"
AWS_REGION="us-east-1"
CLUSTER="demo-cluster"
SERVICE="demo-web-service-test"
PORT=8080
MY_IP="${MY_IP:-}"   # 留空则自动探测

echo ">>> 查 RUNNING 任务 ARN ..."
TASK_ARN=$(aws ecs list-tasks \
  --cluster "$CLUSTER" \
  --service-name "$SERVICE" \
  --desired-status RUNNING \
  --region "$AWS_REGION" --profile "$AWS_PROFILE" \
  --query 'taskArns[0]' --output text)

if [[ -z "$TASK_ARN" || "$TASK_ARN" == "None" ]]; then
  echo "❌ 没有运行中的任务"; exit 1
fi
echo "TASK_ARN=$TASK_ARN"

echo ">>> 从 attachments 拿 ENI ..."
ENI_ID=$(aws ecs describe-tasks \
  --cluster "$CLUSTER" \
  --tasks "$TASK_ARN" \
  --region "$AWS_REGION" --profile "$AWS_PROFILE" \
  --query "tasks[0].attachments[?type=='ElasticNetworkInterface'] | [0].details[?name=='networkInterfaceId'] | [0].value" \
  --output text)

# 兜底：有些镜像/老任务结构里可以从 containers[].networkInterfaces 拿
if [[ -z "$ENI_ID" || "$ENI_ID" == "None" ]]; then
  ENI_ID=$(aws ecs describe-tasks \
    --cluster "$CLUSTER" \
    --tasks "$TASK_ARN" \
    --region "$AWS_REGION" --profile "$AWS_PROFILE" \
    --query "tasks[0].containers[0].networkInterfaces[0].networkInterfaceId" \
    --output text)
fi

if [[ -z "$ENI_ID" || "$ENI_ID" == "None" ]]; then
  echo "❌ 取不到 ENI（networkInterfaceId）"; exit 2
fi
echo "ENI_ID=$ENI_ID"

echo ">>> 查 ENI 的 公网IP/DNS/安全组/私网IP ..."
read -r PUBLIC_IP PUBLIC_DNS SG_ID PRIVATE_IP <<<"$(
  aws ec2 describe-network-interfaces \
    --network-interface-ids "$ENI_ID" \
    --region "$AWS_REGION" --profile "$AWS_PROFILE" \
    --query "[
      NetworkInterfaces[0].Association.PublicIp,
      NetworkInterfaces[0].Association.PublicDnsName,
      NetworkInterfaces[0].Groups[0].GroupId,
      NetworkInterfaces[0].PrivateIpAddress
    ]" --output text
)"

echo "PUBLIC_IP=$PUBLIC_IP"
echo "PUBLIC_DNS=$PUBLIC_DNS"
echo "SG_ID=$SG_ID"
echo "PRIVATE_IP=$PRIVATE_IP"

if [[ -z "$PUBLIC_IP" || "$PUBLIC_IP" == "None" ]]; then
  echo "❌ 该任务无公网 IP（可能未开启 assignPublicIp 或在私网）。"; exit 3
fi
if [[ -z "$SG_ID" || "$SG_ID" == "None" ]]; then
  echo "❌ 取不到安全组 ID"; exit 4
fi

# 来源 IP
if [[ -z "$MY_IP" ]]; then
  echo ">>> 自动探测你的公网 IP ..."
  MY_IP=$(curl -s https://checkip.amazonaws.com || true)
  MY_IP=${MY_IP//$'\r'/}
  MY_IP=${MY_IP//$'\n'/}
fi
if [[ -z "$MY_IP" ]]; then
  echo "❌ 无法确定你的公网 IP；可先 export MY_IP=1.2.3.4 再跑"; exit 5
fi
CIDR="$MY_IP/32"
echo "MY_IP=$MY_IP  => CIDR=$CIDR"

echo ">>> 检查是否已存在放行规则 ($CIDR -> $PORT/tcp) ..."
# 关键修正：把二维数组拍平再过滤，确保能正确命中已有规则
RULE_EXISTS=$(aws ec2 describe-security-groups \
  --group-ids "$SG_ID" \
  --region "$AWS_REGION" --profile "$AWS_PROFILE" \
  --query "length(
    SecurityGroups[0].IpPermissions[?IpProtocol=='tcp' && FromPort==\`$PORT\` && ToPort==\`$PORT\`]
    | [].IpRanges[] | [?CidrIp=='$CIDR']
  )" \
  --output text 2>/dev/null || echo 0)

# RULE_EXISTS 可能输出 "0" 或数字；防止奇怪输出
if [[ "$RULE_EXISTS" =~ ^[0-9]+$ ]] && [[ "$RULE_EXISTS" -gt 0 ]]; then
  echo "=== 已存在放行规则，跳过添加。"
else
  echo ">>> 添加临时放行 $CIDR 到 $PORT/tcp ..."
  # 即使并发下偶尔重复，这里也静默忽略重复错误，不干扰体验
  aws ec2 authorize-security-group-ingress \
    --group-id "$SG_ID" \
    --ip-permissions "IpProtocol=tcp,FromPort=$PORT,ToPort=$PORT,IpRanges=[{CidrIp=$CIDR,Description='temp open by script'}]" \
    --region "$AWS_REGION" --profile "$AWS_PROFILE" \
    >/dev/null 2>&1 || true
fi

echo
echo "URL:  http://$PUBLIC_IP:$PORT/   （或 http://$PUBLIC_DNS:$PORT/ ）"