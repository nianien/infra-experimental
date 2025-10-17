# =========== 参数解析完毕 ===========

# 自动补齐逻辑
case "$1" in
demo-user-rpc)
#  SD_ID="srv-g46enu54fe2bhajk"
  MODULE_PATH="test/demo-user-rpc"
  ;;
demo-order-rpc)
#  SD_ID="srv-ws7zs275lhin423j"
  MODULE_PATH="test/demo-order-rpc"
  ;;
demo-web-api)
#  SD_ID="srv-no4yq2jsnaitk7x2"
  MODULE_PATH="test/demo-web-api"
  ;;
esac

sh $(dirname "$0")/pipeline.sh \
  --repo nianien/infra-experimental \
  --branch master \
  --module "$MODULE_PATH" \
  --service "$1" \
  --sd-id "$SD_ID"
