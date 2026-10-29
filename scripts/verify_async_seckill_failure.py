"""Reproduce the old gap, or verify bounded retries with --expect-recovery.

An SQL trigger rejects only the test coupon's order INSERT. The experiment
checks real HTTP responses, MySQL rollback, Redis reservation state and retries.
The default mode observes the old gap; --expect-recovery checks the fixed flow,
including a permanent failure retained after three attempts.
"""
import argparse
import datetime
import json
import re
from pathlib import Path
import sys
import time
import uuid

sys.dont_write_bytecode = True
from verify_async_seckill import AsyncAcceptance
import verify_seckill as base


class FailureExperiment(AsyncAcceptance):
    def __init__(self, args):
        super().__init__(args)
        self.db = "hmdp_async_failure_" + datetime.datetime.now().strftime("%Y%m%d_%H%M%S_") + uuid.uuid4().hex[:6]
        constants = (base.ROOT / "src/main/java/com/hmdp/utils/RedisConstants.java").read_text(encoding="utf-8")
        match = re.search(r'SECKILL_FAILED_ORDERS_KEY\s*=\s*"([^"]+)"', constants)
        self.failed_key = match.group(1) if match else "seckill:failed:orders"

    def snapshot(self, voucher, failed_user):
        row = self.sql("SELECT stock,"
                       "(SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=%d),"
                       "(SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=%d AND user_id=%d) "
                       "FROM tb_seckill_voucher WHERE voucher_id=%d;"
                       % (voucher, voucher, failed_user, voucher)).stdout.strip()
        stock, orders, user_orders = map(int, row.split("\t"))
        return dict(mysql_stock=stock, mysql_orders=orders, failed_user_orders=user_orders,
                    redis_stock=int(self.redis.command("GET", self.stock_prefix + str(voucher))),
                    redis_recorded_users=self.redis.command("SCARD", self.users_prefix + str(voucher)),
                    failed_user_recorded=bool(self.redis.command(
                        "SISMEMBER", self.users_prefix + str(voucher), failed_user)))

    def wait_for_failure(self, marker, timeout=20):
        path = base.REPORT_DIR / "app-1.log"
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            # Java console encoding on Windows is not necessarily UTF-8. Match
            # only ASCII markers and class names, never a Chinese log message.
            contents = path.read_bytes().decode("ascii", errors="replace")
            if marker in contents and "ERROR" in contents and "VoucherOrderAsyncService" in contents:
                return next(index for index, line in enumerate(contents.splitlines(), 1) if marker in line)
            time.sleep(0.2)
        raise AssertionError("The injected consumer failure did not appear in app-1.log")

    def observe(self, name, **details):
        self.cases.append(dict(name=name, status="OBSERVED", **details))
        print("OBSERVED: " + name, flush=True)

    def wait_for_failure_record(self, order_id, status, timeout=20):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            value = self.redis.command("HGET", self.failed_key, order_id)
            if value:
                record = json.loads(value)
                if record.get("status") == status:
                    return record
            time.sleep(0.1)
        raise AssertionError("Expected failure record status was not found: " + status)

    def wait_for_cleared_record(self, order_id, timeout=10):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.redis.command("HGET", self.failed_key, order_id) is None:
                return
            time.sleep(0.1)
        raise AssertionError("Completed order still has an unresolved failure record")

    def permanent_failure(self):
        voucher = self.create_coupon(2, server=1)
        marker = "async_verification_persistent_reject"
        trigger = "reject_async_verification_order"
        self.sql("DELIMITER //\nCREATE TRIGGER " + trigger + " BEFORE INSERT ON tb_voucher_order "
                 "FOR EACH ROW BEGIN IF NEW.voucher_id=%d THEN SIGNAL SQLSTATE '45000' "
                 "SET MESSAGE_TEXT='%s'; END IF; END//\nDELIMITER ;\n" % (voucher, marker))
        try:
            accepted = self.buy(1, voucher, 3)
            assert accepted.get("success") is True, accepted
            record = self.wait_for_failure_record(accepted["data"], "MANUAL_REVIEW")
            assert record["attempts"] == 3 and record["orderId"] == int(accepted["data"]), record
            assert record["userId"] == 3 and record["voucherId"] == voucher and marker in record["reason"], record
            state = self.snapshot(voucher, 3)
            assert state == dict(mysql_stock=2, mysql_orders=0, failed_user_orders=0,
                                 redis_stock=1, redis_recorded_users=1, failed_user_recorded=True), state
            self.observe("persistent failure stops after three attempts and retains the exact task for review",
                         voucher_id=voucher, failure_record=record, state=state)
        finally:
            self.sql("DROP TRIGGER IF EXISTS " + trigger + ";")
        return record

    def run(self):
        self.prepare()
        before = self.development_snapshot()
        self.sql("CREATE TABLE tb_shop LIKE `" + self.source_db + "`.tb_shop;")
        self.sql("INSERT INTO tb_shop SELECT * FROM `" + self.source_db + "`.tb_shop ORDER BY id LIMIT 1;")
        self.shop_id = int(self.sql("SELECT id FROM tb_shop ORDER BY id LIMIT 1;").stdout.strip())
        voucher = self.create_coupon(3)
        marker = "async_verification_insert_rejected"
        trigger = "reject_async_verification_order"
        self.sql("DELIMITER //\nCREATE TRIGGER " + trigger + " BEFORE INSERT ON tb_voucher_order "
                 "FOR EACH ROW BEGIN IF NEW.voucher_id=%d THEN SIGNAL SQLSTATE '45000' "
                 "SET MESSAGE_TEXT='%s'; END IF; END//\nDELIMITER ;\n" % (voucher, marker))
        try:
            accepted = self.buy(0, voucher, 1)
            assert accepted.get("success") is True and isinstance(accepted.get("data"), str), accepted
            log_line = self.wait_for_failure(marker)
            failed = self.snapshot(voucher, 1)
            assert failed == dict(mysql_stock=3, mysql_orders=0, failed_user_orders=0,
                                  redis_stock=2, redis_recorded_users=1, failed_user_recorded=True), failed
            assert self.sql("SELECT COUNT(*) FROM tb_voucher_order WHERE id=%d;"
                            % int(accepted["data"])).stdout.strip() == "0"
            self.observe("accepted task fails; MySQL rolls back but Redis stays reserved",
                         voucher_id=voucher, user_id=1, accepted_response=accepted,
                         injected_failure_marker=marker, consumer_log="app-1.log", consumer_log_line=log_line,
                         state=failed)
            if self.args.expect_recovery:
                task = self.wait_for_failure_record(accepted["data"], "RETRY_PENDING")
                assert task["orderId"] == int(accepted["data"]), task
        finally:
            self.sql("DROP TRIGGER IF EXISTS " + trigger + ";")

        if self.args.expect_recovery:
            self.wait_order(voucher, 1, accepted)
            self.wait_for_cleared_record(accepted["data"])
            recovered = self.snapshot(voucher, 1)
            assert recovered == dict(mysql_stock=2, mysql_orders=1, failed_user_orders=1,
                                     redis_stock=2, redis_recorded_users=1, failed_user_recorded=True), recovered
            self.observe("original accepted order is retried automatically and reaches MySQL exactly once",
                         order_id=accepted["data"], state=recovered)

        retry = self.buy(1, voucher, 1)
        assert retry.get("success") is False and retry.get("errorMsg") == "买过了", retry
        assert self.snapshot(voucher, 1) == (recovered if self.args.expect_recovery else failed)
        self.observe("another JVM rejects duplicate purchase after recovery" if self.args.expect_recovery
                     else "after removing the fault, another JVM still rejects the same user",
                     response=retry, state=self.snapshot(voucher, 1))

        # A healthy task on the SAME worker proves the consumer is still alive.
        healthy = self.buy(0, voucher, 2)
        self.wait_order(voucher, 2, healthy)
        final = self.snapshot(voucher, 1)
        assert final == dict(mysql_stock=1 if self.args.expect_recovery else 2,
                             mysql_orders=2 if self.args.expect_recovery else 1,
                             failed_user_orders=1 if self.args.expect_recovery else 0,
                             redis_stock=1, redis_recorded_users=2, failed_user_recorded=True), final
        self.observe("consumer continues healthy orders without another deduction for the recovered task"
                     if self.args.expect_recovery else "same consumer handles a healthy task but the failed user remains stuck",
                     healthy_user_id=2, healthy_order_id=healthy["data"], state=final)

        manual_review = self.permanent_failure() if self.args.expect_recovery else None

        assert self.sql("SELECT COUNT(*) FROM information_schema.triggers "
                        "WHERE trigger_schema=DATABASE();").stdout.strip() == "0"
        after = self.development_snapshot()
        assert before == after, ("Development database changed during experiment", before, after)
        report = dict(status="RECOVERY_TESTS_PASSED" if self.args.expect_recovery else "RECOVERY_GAP_REPRODUCED",
                      experiment_checks_passed=True, recovery_implemented=self.args.expect_recovery,
                      database=self.db, voucher_id=voucher, manual_review_task=manual_review,
                      cases=self.cases, final_state=final, development_before=before, development_after=after,
                      app_pids=self.app_pids, app_ports=self.ports, redis_port=self.redis_port,
                      injection_trigger_removed=True,
                      conclusion="Transient failure recovers with the original ID; persistent failure is retained for review."
                      if self.args.expect_recovery else "Consumer only logs failures; Redis reservation remains and the failed user cannot retry.")
        (base.REPORT_DIR / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print("EXPERIMENT COMPLETE: " + report["status"] + "; " + str(base.REPORT_DIR / "results.json"), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True)
    parser.add_argument("--mysql", required=True)
    parser.add_argument("--redis", required=True)
    parser.add_argument("--expect-recovery", action="store_true", help="Verify the fixed retry flow and retry limit")
    parser.add_argument("--jar", type=Path, default=base.ROOT / "target/hm-dianping-0.0.1-SNAPSHOT.jar")
    parser.add_argument("--classpath-report", type=Path,
                        default=base.ROOT / "target/surefire-reports/TEST-com.hmdp.service.impl.VoucherOrderServiceTest.xml")
    args = parser.parse_args()
    args.apply_dev_migration = False
    base.REPORT_DIR = base.ROOT / "target" / "async-seckill-failure" / (
        datetime.datetime.now().strftime("%Y%m%d_%H%M%S_") + uuid.uuid4().hex[:6])
    experiment = FailureExperiment(args)
    try:
        experiment.run()
    except BaseException as error:
        (base.REPORT_DIR / "failure.json").write_text(json.dumps(dict(database=experiment.db,
            error=str(error), completed_cases=experiment.cases), ensure_ascii=False, indent=2), encoding="utf-8")
        raise
    finally:
        experiment.close()
        (base.REPORT_DIR / "cleanup.json").write_text(json.dumps(dict(
            all_started_processes_stopped=all(process.poll() is not None for process in experiment.processes),
            temporary_credentials_removed=not experiment.options_path.exists()), indent=2), encoding="utf-8")
