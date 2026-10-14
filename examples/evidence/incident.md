# 演示故障：checkout 接口错误率上升

以下均为人工构造的离线演示证据，不代表真实生产环境。

- 时间：2026-10-14 10:05 UTC
- 服务：checkout-api
- 命名空间：demo-shop
- 现象：发布 checkout-api:v2 后，HTTP 500 错误率从 0.1% 上升到 18%。
- 变更：应用开始读取 PAYMENT_API_URL，部署清单仍提供旧变量 PAYMENT_URL。
- 影响：支付请求调用失败；库存查询未受影响。

请结合 application.log 和 deployment.yaml 验证推断，并区分事实、假设和建议。
