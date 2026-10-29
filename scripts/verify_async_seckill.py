"""Verify the asynchronous HTTP -> Redis -> queue -> MySQL flow in isolation.

Reuses process/authentication fixtures from verify_seckill.py. Creates a new test
database; never applies migrations or purchase requests to the development DB.
Creates coupons through the actual HTTP API so afterCommit initialization runs.
Retains the test DB and a JSON report, and stops only processes started here.
"""
import argparse
import datetime
import json
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.request
import uuid

# Running the verification helper should not leave bytecode files in the source tree.
sys.dont_write_bytecode = True
import verify_seckill as base


class AsyncAcceptance(base.Acceptance):
    def __init__(self, args):
        super().__init__(args)
        self.db = "hmdp_async_verify_" + datetime.datetime.now().strftime("%Y%m%d_%H%M%S_") + uuid.uuid4().hex[:6]
        constants = (base.ROOT / "src/main/java/com/hmdp/utils/RedisConstants.java").read_text(encoding="utf-8")
        self.stock_prefix = re.search(r'SECKILL_STOCK_KEY\s*=\s*"([^"]+)"', constants).group(1)
        self.users_prefix = re.search(r'SECKILL_ORDER_RECORD\s*=\s*"([^"]+)"', constants).group(1)

    def post_json(self, server, path, payload):
        request = urllib.request.Request(
            "http://127.0.0.1:%d%s" % (self.ports[server], path),
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"}, method="POST")
        try:
            with base.LOCAL_HTTP.open(request, timeout=15) as response:
                return response.status, json.loads(response.read())
        except urllib.error.HTTPError as error:
            body = error.read()
            return error.code, json.loads(body) if body else {}

    def create_coupon(self, stock, server=0):
        now = datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None, microsecond=0)
        status, result = self.post_json(server, "/voucher/seckill", {
            "shopId": self.shop_id, "title": "isolated asynchronous seckill verification",
            "subTitle": "test fixture", "rules": "test fixture", "payValue": 4500,
            "actualValue": 5000, "type": 1, "status": 1, "stock": stock,
            "beginTime": (now - datetime.timedelta(hours=1)).isoformat(),
            "endTime": (now + datetime.timedelta(hours=2)).isoformat()})
        assert status == 200 and result.get("success") is True, (status, result)
        voucher = int(result["data"])
        self.assert_state("HTTP creation initializes Redis after database save", voucher, stock, 0, stock,
                          server=server + 1)
        return voucher

    def wait_order(self, voucher, user, response, timeout=20):
        assert response.get("success") is True, response
        assert isinstance(response.get("data"), str), "Order ID must be returned as a string"
        order_id = response["data"]
        assert order_id.isdigit(), response
        deadline = time.monotonic() + timeout
        while True:
            row = self.sql("SELECT id,user_id,voucher_id FROM tb_voucher_order "
                           "WHERE voucher_id=%d AND user_id=%d;" % (voucher, user)).stdout.strip()
            if row:
                values = row.split("\t")
                assert values == [order_id, str(user), str(voucher)], (response, row)
                return order_id
            if time.monotonic() >= deadline:
                raise AssertionError("Accepted order did not reach MySQL: voucher=%d user=%d order=%s"
                                     % (voucher, user, order_id))
            time.sleep(0.2)

    def assert_state(self, name, voucher, initial, orders, stock, **details):
        redis_stock = self.redis.command("GET", self.stock_prefix + str(voucher))
        recorded_users = self.redis.command("SCARD", self.users_prefix + str(voucher))
        assert redis_stock == str(stock), (name, "Redis stock mismatch", redis_stock, stock)
        assert recorded_users == orders, (name, "Redis purchase record mismatch", recorded_users, orders)
        self.record(name, voucher, initial, orders, stock, redis_stock=int(redis_stock),
                    redis_recorded_users=recorded_users, **details)

    def development_snapshot(self):
        row = self.sql("SELECT (SELECT COUNT(*) FROM tb_voucher),"
                       "(SELECT COUNT(*) FROM tb_seckill_voucher),"
                       "(SELECT COALESCE(SUM(stock),0) FROM tb_seckill_voucher),"
                       "(SELECT COUNT(*) FROM tb_voucher_order),"
                       "(SELECT COUNT(*) FROM tb_user);", self.source_db).stdout.strip()
        return dict(zip(("vouchers", "seckill_vouchers", "total_stock", "orders", "users"),
                        map(int, row.split("\t"))))

    def run(self):
        self.prepare()
        before = self.development_snapshot()
        # Keep a real shop fixture in the private DB without invoking shop caches.
        self.sql("CREATE TABLE tb_shop LIKE `" + self.source_db + "`.tb_shop;")
        self.sql("INSERT INTO tb_shop SELECT * FROM `" + self.source_db + "`.tb_shop ORDER BY id LIMIT 1;")
        self.shop_id = int(self.sql("SELECT id FROM tb_shop ORDER BY id LIMIT 1;").stdout.strip())

        voucher = self.create_coupon(3)
        first = self.buy(0, voucher, 1)
        first_id = self.wait_order(voucher, 1, first)
        assert self.redis.command("SISMEMBER", self.users_prefix + str(voucher), 1) == 1
        self.assert_state("first purchase persists the exact returned order ID", voucher, 3, 1, 2,
                          user_id=1, order_id=first_id)

        duplicate = self.buy(1, voucher, 1)
        assert duplicate.get("success") is False and duplicate.get("errorMsg") == "买过了", duplicate
        self.assert_state("same user on another JVM cannot buy again", voucher, 3, 1, 2,
                          response=duplicate)

        for user in (2, 3):
            self.wait_order(voucher, user, self.buy(user % 2, voucher, user))
        self.assert_state("other users consume the remaining two units", voucher, 3, 3, 0)
        sold_out = self.buy(0, voucher, 4)
        assert sold_out.get("success") is False and sold_out.get("errorMsg") == "库存不足", sold_out
        self.assert_state("sold-out request does not create an order", voucher, 3, 3, 0,
                          response=sold_out)

        voucher = self.create_coupon(5, server=1)
        results = self.concurrent(voucher, [5] * 50)
        accepted = [result for result in results if result.get("success") is True]
        assert len(accepted) == 1, results
        self.wait_order(voucher, 5, accepted[0])
        self.assert_state("50 same-user requests across two JVMs create one order", voucher, 5, 1, 4,
                          requests=50, accepted=1)

        voucher = self.create_coupon(10)
        users = list(range(1, 51))
        results = self.concurrent(voucher, users)
        accepted = [(user, result) for user, result in zip(users, results)
                    if result.get("success") is True]
        assert len(accepted) == 10, results
        for user, response in accepted:
            self.wait_order(voucher, user, response)
        self.assert_state("50 users across two JVMs cannot oversell ten units", voucher, 10, 10, 0,
                          requests=50, accepted=10)

        after = self.development_snapshot()
        assert before == after, ("Development database changed during verification", before, after)
        totals = self.sql("SELECT COUNT(*),COUNT(DISTINCT id) FROM tb_voucher_order;").stdout.strip().split("\t")
        assert totals[0] == totals[1]
        self.report = dict(status="PASS", database=self.db, cases=self.cases,
                           mysql_version=self.sql("SELECT VERSION();").stdout.strip(),
                           redis_version="3.2.100", app_pids=self.app_pids, app_ports=self.ports,
                           redis_port=self.redis_port, development_before=before, development_after=after,
                           total_orders=int(totals[0]), unique_order_ids=int(totals[1]),
                           scope="Real coupon-creation HTTP, authentication, Redis Lua, two JVM queues, MySQL orders")
        (base.REPORT_DIR / "results.json").write_text(
            json.dumps(self.report, ensure_ascii=False, indent=2), encoding="utf-8")
        print("ALL PASS; report: " + str(base.REPORT_DIR / "results.json"), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True)
    parser.add_argument("--mysql", required=True)
    parser.add_argument("--redis", required=True)
    parser.add_argument("--jar", type=Path, default=base.ROOT / "target/hm-dianping-0.0.1-SNAPSHOT.jar")
    parser.add_argument("--classpath-report", type=Path,
                        default=base.ROOT / "target/surefire-reports/TEST-com.hmdp.service.impl.VoucherOrderServiceTest.xml")
    args = parser.parse_args()
    args.apply_dev_migration = False
    base.REPORT_DIR = base.ROOT / "target" / "async-seckill-acceptance" / (
        datetime.datetime.now().strftime("%Y%m%d_%H%M%S_") + uuid.uuid4().hex[:6])
    acceptance = AsyncAcceptance(args)
    try:
        acceptance.run()
    except BaseException as error:
        (base.REPORT_DIR / "failure.json").write_text(json.dumps(dict(database=acceptance.db,
            error=str(error), completed_cases=acceptance.cases), ensure_ascii=False, indent=2), encoding="utf-8")
        raise
    finally:
        acceptance.close()
        (base.REPORT_DIR / "cleanup.json").write_text(json.dumps(dict(
            all_started_processes_stopped=all(process.poll() is not None for process in acceptance.processes),
            temporary_credentials_removed=not acceptance.options_path.exists()), indent=2), encoding="utf-8")
