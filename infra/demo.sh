# =========== 参数解析完毕 ===========

# 自动补齐逻辑
case "$1" in
demo-user-rpc)
  SD_ID="srv-g46enu54fe2bhajk"
  CONT_PORT="8081"
  ;;
demo-order-rpc)
  SD_ID="srv-ws7zs275lhin423j"
  CONT_PORT="8081"
  ;;
demo-web-api)
  SD_ID="srv-no4yq2jsnaitk7x2"
  CONT_PORT="8080"
  ;;
esac

sh ./pipeline.sh \
  --repo nianien/infra-experimental \
  --branch master \
  --service "$1" \
  --sd-id "$SD_ID" \
  --port "$CONT_PORT"
