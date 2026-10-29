"""Real MySQL/Redis acceptance test using two independent application JVMs.

Run Maven tests first (or build an executable jar). Run with --apply-dev-migration to add the guarded
unique index to the configured development database. All purchase/failure fixtures
are created in a new verification database and a dedicated temporary Redis process.
Only temporary processes started by this script are terminated. Test databases are
retained for inspection; credentials and tokens are never written to the report.
"""
import argparse
import concurrent.futures
import datetime
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
REPORT_DIR = ROOT / "target" / "seckill-acceptance"
# Local test requests must bypass any Windows/system HTTP proxy.
LOCAL_HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def scalar(text, name, default=None):
    match = re.search(r"^\s+" + re.escape(name) + r":\s*(.*?)\s*$", text, re.M)
    return match.group(1).strip("\"'") if match else default


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class Redis:
    def __init__(self, port):
        self.port = port

    def command(self, *args):
        with socket.create_connection(("127.0.0.1", self.port), timeout=5) as sock:
            stream = sock.makefile("rb")
            values = [str(arg).encode() for arg in args]
            sock.sendall(b"*%d\r\n" % len(values) + b"".join(
                b"$%d\r\n" % len(value) + value + b"\r\n" for value in values))
            line = stream.readline()
            kind, value = line[:1], line[1:-2]
            if kind == b"-":
                raise RuntimeError(value.decode())
            if kind == b":":
                return int(value)
            if kind == b"$":
                size = int(value)
                return None if size < 0 else stream.read(size + 2)[:-2].decode()
            return value.decode()


class Acceptance:
    def __init__(self, args):
        self.args = args
        self.cases = []
        self.processes = []
        self.logs = []
        self.tokens = {}
        self.db = "hmdp_seckill_verify_" + datetime.datetime.now().strftime("%Y%m%d_%H%M%S_") + uuid.uuid4().hex[:6]
        REPORT_DIR.mkdir(parents=True, exist_ok=True)
        text = (ROOT / "src/main/resources/application.yaml").read_text(encoding="utf-8")
        match = re.match(r"jdbc:mysql://([^:/]+)(?::(\d+))?/([^?]+)", scalar(text, "url"))
        if not match:
            raise RuntimeError("Unsupported local MySQL JDBC URL")
        self.host, self.port, self.source_db = match.group(1), match.group(2) or "3306", match.group(3)
        if not re.fullmatch(r"[A-Za-z0-9_]+", self.source_db):
            raise RuntimeError("Unexpected database name")
        options = tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", suffix=".cnf", delete=False)
        self.options_path = Path(options.name)
        password = scalar(text, "password", "").replace("\\", "\\\\").replace('"', '\\"')
        options.write('[client]\nhost=' + self.host + '\nport=' + self.port
                      + '\nuser=' + scalar(text, "username") + '\npassword="' + password + '"\n')
        options.close()
        self.mysql_base = [args.mysql, "--defaults-extra-file=" + str(self.options_path),
                           "--default-character-set=utf8mb4", "--batch", "--raw", "--skip-column-names",
                           "--init-command=SET time_zone='+00:00'"]
        constants = (ROOT / "src/main/java/com/hmdp/utils/RedisConstants.java").read_text(encoding="utf-8")
        self.lock_prefix = re.search(r'LOCK_ORDER_KEY\s*=\s*"([^"]+)"', constants).group(1)

    def sql(self, statement, db=None, check=True):
        result = subprocess.run(self.mysql_base + ["--database=" + (db or self.db)],
                                input=statement, capture_output=True, text=True, encoding="utf-8")
        if check and result.returncode:
            raise RuntimeError("MySQL operation failed: " + result.stderr.strip())
        return result

    def spawn(self, args, name):
        log = open(REPORT_DIR / (name + ".log"), "wb")
        self.logs.append(log)
        process = subprocess.Popen(args, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        self.processes.append(process)
        return process

    def prepare(self):
        migration = (ROOT / "src/main/resources/db/migration/20261016_voucher_order_unique.sql").read_text(encoding="utf-8")
        if self.args.apply_dev_migration:
            self.sql(migration, self.source_db)
            self.sql(migration, self.source_db)  # Verify repeat execution is safe.
        self.dev_before = int(self.sql("SELECT COUNT(*) FROM tb_voucher_order;", self.source_db).stdout.strip())
        self.sql("CREATE DATABASE `" + self.db + "` CHARACTER SET utf8mb4;", self.source_db)
        for table in ("tb_voucher", "tb_seckill_voucher", "tb_voucher_order", "tb_user"):
            self.sql("CREATE TABLE " + table + " LIKE `" + self.source_db + "`." + table + ";")
        self.sql(migration)
        self.sql(migration)
        for user in range(1, 62):
            self.sql("INSERT INTO tb_user(id,phone,nick_name) VALUES (%d,'%s','verify%d');"
                     % (user, str(13900000000 + user), user))

        self.redis_port = free_port()
        self.redis = Redis(self.redis_port)
        self.spawn([self.args.redis, "--bind", "127.0.0.1", "--port", str(self.redis_port),
                    "--dir", str(REPORT_DIR), "--save", "", "--appendonly", "no"], "redis")
        deadline = time.monotonic() + 20
        while True:
            try:
                if self.redis.command("PING") == "PONG":
                    break
            except OSError:
                pass
            if time.monotonic() > deadline:
                raise RuntimeError("Dedicated verification Redis did not start")
            time.sleep(0.2)
        for user in range(1, 62):
            token = "seckill-verify-" + uuid.uuid4().hex
            self.tokens[user] = token
            self.redis.command("HSET", "login:token:" + token, "id", user)
            self.redis.command("HSET", "login:token:" + token, "nickName", "verify" + str(user))
            self.redis.command("EXPIRE", "login:token:" + token, 3600)

        self.ports = [free_port(), free_port()]
        jdbc = "jdbc:mysql://%s:%s/%s?useSSL=false&serverTimezone=UTC" % (self.host, self.port, self.db)
        self.app_pids = []
        if self.args.classpath_report.is_file():
            properties = ET.parse(self.args.classpath_report).getroot().find("properties")
            classpath = next(prop.attrib["value"] for prop in properties
                             if prop.attrib["name"] == "java.class.path")
            launcher = ["-cp", classpath, "com.hmdp.HmDianPingApplication"]
        elif self.args.jar.is_file():
            launcher = ["-jar", str(self.args.jar)]
        else:
            raise RuntimeError("Run Maven tests or build an executable jar before acceptance testing")
        for index, port in enumerate(self.ports):
            process = self.spawn([self.args.java, "-Duser.timezone=UTC"] + launcher + [
                                  "--server.port=" + str(port), "--spring.datasource.url=" + jdbc,
                                  "--spring.datasource.hikari.connection-init-sql=SET time_zone = '+00:00'",
                                  "--spring.redis.host=127.0.0.1", "--spring.redis.port=" + str(self.redis_port),
                                  "--spring.redis.database=0", "--spring.redis.password=",
                                  "--spring.main.banner-mode=off"], "app-" + str(index + 1))
            self.app_pids.append(process.pid)
        deadline = time.monotonic() + 100
        for index in range(2):
            while True:
                try:
                    if self.request(index, "/user/me", user=1, method="GET")[1].get("success"):
                        break
                except (OSError, ValueError):
                    pass
                if self.processes[index + 1].poll() is not None:
                    raise RuntimeError("Application exited; inspect app-%d.log" % (index + 1))
                if time.monotonic() > deadline:
                    raise RuntimeError("Application startup timeout; inspect application logs")
                time.sleep(0.5)
        print("READY: two independent JVMs, isolated MySQL database and Redis process", flush=True)

    def request(self, server, path, user=None, method="POST"):
        headers = {} if user is None else {"authorization": self.tokens[user]}
        request = urllib.request.Request("http://127.0.0.1:%d%s" % (self.ports[server], path),
                                         data=b"" if method == "POST" else None, headers=headers, method=method)
        try:
            with LOCAL_HTTP.open(request, timeout=10) as response:
                return response.status, json.loads(response.read())
        except urllib.error.HTTPError as error:
            body = error.read()
            return error.code, json.loads(body) if body else {}

    def buy(self, server, voucher, user):
        status, result = self.request(server, "/voucher-order/seckill/" + str(voucher), user)
        assert status == 200, (status, result)
        assert result.get("errorMsg") != "服务器异常", result
        return result

    def new_voucher(self, stock=5, window="active"):
        windows = {
            "active": ("DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 HOUR)", "DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 DAY)"),
            "future": ("DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR)", "DATE_ADD(UTC_TIMESTAMP(),INTERVAL 2 HOUR)"),
            "ended": ("DATE_SUB(UTC_TIMESTAMP(),INTERVAL 2 HOUR)", "DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 HOUR)")}
        begin, end = windows[window]
        output = self.sql("INSERT INTO tb_voucher(shop_id,title,pay_value,actual_value,type,status) "
                          "VALUES(1,'seckill verification',4500,5000,1,1); SELECT LAST_INSERT_ID();").stdout.strip()
        voucher = int(output)
        self.sql("INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) VALUES(%d,%d,%s,%s);"
                 % (voucher, stock, begin, end))
        return voucher

    def record(self, name, voucher, initial, expected_orders, expected_stock, **details):
        values = self.sql("SELECT stock,(SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=%d),"
                          "(SELECT COUNT(*) FROM (SELECT user_id FROM tb_voucher_order WHERE voucher_id=%d "
                          "GROUP BY user_id HAVING COUNT(*)>1) d) FROM tb_seckill_voucher WHERE voucher_id=%d;"
                          % (voucher, voucher, voucher)).stdout.strip().split("\t")
        stock, orders, duplicates = map(int, values)
        assert (orders, stock, duplicates) == (expected_orders, expected_stock, 0), (name, values)
        assert initial - stock == orders, (name, "inventory/order mismatch")
        self.cases.append(dict(name=name, voucher_id=voucher, initial_stock=initial, remaining_stock=stock,
                               orders=orders, duplicate_groups=duplicates, status="PASS", **details))
        print("PASS: " + name, flush=True)

    def concurrent(self, voucher, users):
        barrier = threading.Barrier(len(users))
        def send(pair):
            index, user = pair
            barrier.wait(timeout=15)
            return self.buy(index % 2, voucher, user)
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(users)) as pool:
            return list(pool.map(send, enumerate(users)))

    def run(self):
        self.prepare()
        for window, expected in (("future", "秒杀还未开始"), ("ended", "秒杀已经结束")):
            voucher = self.new_voucher(window=window)
            result = self.buy(0, voucher, 1)
            assert result["errorMsg"] == expected, (window, result)
            self.record(window, voucher, 5, 0, 5)
        voucher = self.new_voucher(stock=0)
        assert self.buy(0, voucher, 1)["errorMsg"] == "库存不足"
        self.record("zero stock", voucher, 0, 0, 0)
        assert self.buy(0, 9223372036854775807, 1)["errorMsg"] == "秒杀券不存在"
        voucher = self.new_voucher()
        assert self.request(0, "/voucher-order/seckill/" + str(voucher))[0] == 401
        self.record("unauthenticated request", voucher, 5, 0, 5)

        voucher = self.new_voucher()
        first = self.buy(0, voucher, 1)
        assert first["success"] and isinstance(first["data"], str)
        assert not self.buy(1, voucher, 1)["success"]
        self.record("sequential duplicate across JVMs", voucher, 5, 1, 4)
        assert self.buy(1, voucher, 2)["success"]
        self.record("another user can buy", voucher, 5, 2, 3)
        duplicate = self.sql("INSERT INTO tb_voucher_order(id,user_id,voucher_id) VALUES(9000000000000000000,1,%d);"
                             % voucher, check=False)
        assert duplicate.returncode != 0 and "1062" in duplicate.stderr, duplicate.stderr
        self.record("database rejects duplicate user/voucher", voucher, 5, 2, 3)

        voucher = self.new_voucher()
        results = self.concurrent(voucher, [3] * 50)
        accepted = sum(result["success"] is True for result in results)
        assert accepted == 1, results
        self.record("50 same-user requests across two JVMs", voucher, 5, 1, 4, requests=50, accepted=accepted)

        voucher = self.new_voucher(stock=10)
        results = self.concurrent(voucher, list(range(1, 51)))
        accepted = sum(result["success"] is True for result in results)
        assert accepted == 10, results
        self.record("50 users compete for 10 vouchers across two JVMs", voucher, 10, 10, 0,
                    requests=50, accepted=accepted)

        # Hold a database row lock so JVM A holds the Redis lock before creating an order.
        voucher = self.new_voucher()
        locker = subprocess.Popen(self.mysql_base + ["--unbuffered", "--database=" + self.db, "--execute",
                                  "START TRANSACTION; SELECT stock INTO @held_stock FROM tb_seckill_voucher "
                                  "WHERE voucher_id=%d FOR UPDATE; SELECT 'LOCKED'; DO SLEEP(6); ROLLBACK;" % voucher],
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8",
                                  creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        self.processes.append(locker)
        assert locker.stdout.readline().strip() == "LOCKED"
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            pending = pool.submit(self.buy, 0, voucher, 60)
            deadline = time.monotonic() + 3
            key = self.lock_prefix + "60:" + str(voucher)
            while not self.redis.command("EXISTS", key):
                if time.monotonic() > deadline:
                    raise RuntimeError("JVM A did not acquire the distributed lock")
                time.sleep(0.02)
            competing = self.buy(1, voucher, 60)
            assert competing["errorMsg"] == "请勿重复提交", competing
            assert pending.result()["success"]
        locker.wait(timeout=10)
        self.record("JVM B is rejected while JVM A holds Redis lock", voucher, 5, 1, 4)

        # Failure is injected only in the isolated verification database, never in application code.
        voucher = self.new_voucher()
        self.sql("DELIMITER //\nCREATE TRIGGER reject_verification_order BEFORE INSERT ON tb_voucher_order "
                 "FOR EACH ROW BEGIN IF NEW.voucher_id=%d THEN SIGNAL SQLSTATE '45000' "
                 "SET MESSAGE_TEXT='verification insert failure'; END IF; END//\nDELIMITER ;\n" % voucher)
        status, failed = self.request(0, "/voucher-order/seckill/" + str(voucher), 61)
        assert status == 200 and failed.get("success") is False
        self.record("insert failure rolls back inventory", voucher, 5, 0, 5)
        self.sql("DROP TRIGGER reject_verification_order;")
        assert self.buy(1, voucher, 61)["success"]
        self.record("failed request releases lock; retry on other JVM succeeds", voucher, 5, 1, 4)

        totals = self.sql("SELECT COUNT(*),COUNT(DISTINCT id) FROM tb_voucher_order;").stdout.strip().split("\t")
        assert totals[0] == totals[1]
        self.dev_after = int(self.sql("SELECT COUNT(*) FROM tb_voucher_order;", self.source_db).stdout.strip())
        assert self.dev_before == self.dev_after, "Development orders unexpectedly changed"
        self.report = dict(status="PASS", database=self.db, app_pids=self.app_pids, app_ports=self.ports,
                           mysql_version=self.sql("SELECT VERSION();").stdout.strip(),
                           development_orders_before=self.dev_before, development_orders_after=self.dev_after,
                           total_orders=int(totals[0]), unique_order_ids=int(totals[1]), cases=self.cases)
        (REPORT_DIR / "results.json").write_text(json.dumps(self.report, ensure_ascii=False, indent=2), encoding="utf-8")
        (REPORT_DIR / "failure.json").unlink(missing_ok=True)
        print("ALL PASS; report: " + str(REPORT_DIR / "results.json"), flush=True)

    def close(self):
        for process in reversed(self.processes):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
        for log in self.logs:
            log.close()
        self.options_path.unlink(missing_ok=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True)
    parser.add_argument("--mysql", required=True)
    parser.add_argument("--redis", required=True)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/hm-dianping-0.0.1-SNAPSHOT.jar")
    parser.add_argument("--classpath-report", type=Path,
                        default=ROOT / "target/surefire-reports/TEST-com.hmdp.service.impl.VoucherOrderServiceTest.xml")
    parser.add_argument("--apply-dev-migration", action="store_true")
    acceptance = Acceptance(parser.parse_args())
    try:
        acceptance.run()
    except BaseException as error:
        (REPORT_DIR / "failure.json").write_text(json.dumps(dict(database=acceptance.db,
            error=str(error), completed_cases=acceptance.cases), ensure_ascii=False, indent=2), encoding="utf-8")
        raise
    finally:
        acceptance.close()
