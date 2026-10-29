"""Real HTTP acceptance for login, follows, feed pagination, likes, sign-in and cache.

Only a new private MySQL database and an owned temporary Redis are mutated.
Credentials/tokens stay out of the report; the test DB is retained for inspection.
"""
import argparse
import datetime
import json
from pathlib import Path
import sys
import time
import urllib.error
import urllib.request
import uuid
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
import verify_seckill as base


class PracticeAcceptance(base.Acceptance):
    TABLES = ("tb_user", "tb_user_info", "tb_follow", "tb_blog", "tb_shop",
              "tb_shop_type", "tb_voucher", "tb_seckill_voucher", "tb_voucher_order")

    def __init__(self, args):
        stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S_") + uuid.uuid4().hex[:6]
        base.REPORT_DIR = base.ROOT / "target/practice-acceptance" / stamp
        super().__init__(args)
        self.db = "hmdp_practice_verify_" + stamp

    def api(self, path, user=None, method="GET", payload=None):
        headers = {}
        if user is not None:
            headers["authorization"] = self.tokens[user]
        if payload is not None:
            headers["Content-Type"] = "application/json"
        body = json.dumps(payload).encode() if payload is not None else (b"" if method == "POST" else None)
        request = urllib.request.Request("http://127.0.0.1:%d%s" % (self.ports[0], path),
                                         data=body, headers=headers, method=method)
        try:
            with base.LOCAL_HTTP.open(request, timeout=15) as response:
                content = response.read()
                return response.status, json.loads(content) if content else {}
        except urllib.error.HTTPError as error:
            content = error.read()
            return error.code, json.loads(content) if content else {}

    def data(self, path, **kwargs):
        status, result = self.api(path, **kwargs)
        assert status == 200 and result.get("success") is True, (path, status, result)
        return result.get("data")

    def check(self, name, **details):
        self.cases.append(dict(name=name, status="PASS", **details))
        print("PASS: " + name, flush=True)

    def source_counts(self):
        return {table: int(self.sql("SELECT COUNT(*) FROM " + table + ";", self.source_db).stdout.strip())
                for table in self.TABLES}

    def prepare_practice(self):
        self.before = self.source_counts()
        self.sql("CREATE DATABASE `" + self.db + "` CHARACTER SET utf8mb4;", self.source_db)
        for table in self.TABLES:
            self.sql("CREATE TABLE " + table + " LIKE `" + self.source_db + "`." + table + ";")
        for user in (101, 102, 103):
            self.sql("INSERT INTO tb_user(id,phone,nick_name,icon) VALUES(%d,'%s','fixture%d','fixture.png');"
                     % (user, str(13990000000 + user), user))
        self.sql("INSERT INTO tb_shop(id,name,type_id,images,address,x,y,sold,comments,score) "
                 "VALUES(101,'fixture shop',901,'fixture.png','fixture',120,30,0,0,40);")
        redis_port = base.free_port()
        self.redis = base.Redis(redis_port)
        self.spawn([self.args.redis, "--bind", "127.0.0.1", "--port", str(redis_port), "--save", "",
                    "--appendonly", "no", "--dir", str(base.REPORT_DIR)], "redis")
        deadline = time.monotonic() + 20
        while True:
            try:
                if self.redis.command("PING") == "PONG":
                    break
            except OSError:
                pass
            if time.monotonic() > deadline:
                raise RuntimeError("Temporary Redis startup timed out")
            time.sleep(.1)
        properties = ET.parse(self.args.classpath_report).getroot().find("properties")
        classpath = next(p.attrib["value"] for p in properties if p.attrib["name"] == "java.class.path")
        self.ports = [base.free_port()]
        jdbc = "jdbc:mysql://%s:%s/%s?useSSL=false&serverTimezone=UTC" % (self.host, self.port, self.db)
        app = self.spawn([self.args.java, "-Duser.timezone=UTC", "-cp", classpath,
                          "com.hmdp.HmDianPingApplication", "--server.port=" + str(self.ports[0]),
                          "--spring.datasource.url=" + jdbc, "--spring.redis.host=127.0.0.1",
                          "--spring.redis.port=" + str(redis_port), "--spring.redis.database=0",
                          "--spring.redis.password=", "--spring.main.banner-mode=off"], "application")
        deadline = time.monotonic() + 100
        while True:
            if app.poll() is not None:
                raise RuntimeError("Application exited; inspect application.log")
            try:
                if self.api("/shop/of/type?typeId=901")[1].get("success"):
                    break
            except OSError:
                pass
            if time.monotonic() > deadline:
                raise RuntimeError("Application startup timed out")
            time.sleep(.3)
        print("READY: private MySQL + Redis + real HTTP", flush=True)

    def login_users(self):
        for user in (101, 102, 103):
            phone = str(13990000000 + user)
            self.data("/user/code?phone=" + phone, method="POST")
            code = self.redis.command("GET", "login:code:" + phone)
            token = self.data("/user/login", method="POST", payload=dict(phone=phone, code=code))
            assert isinstance(token, str) and token
            self.tokens[user] = token
            me = self.data("/user/me", user=user)
            assert me["id"] == user and "phone" not in me and "password" not in me
        self.check("SMS code login and token authentication for three independent users")

    def run(self):
        self.prepare_practice()
        for path, method in (("/blog/of/follow?lastId=3000", "GET"),
                             ("/user/sign/count", "GET"), ("/user/sign", "POST")):
            assert self.api(path, method=method)[0] == 401
        self.check("Feed and sign-in reject unauthenticated requests")
        self.login_users()

        for user in (101, 103):
            self.data("/follow/102/true", user=user, method="PUT")
        self.data("/follow/102/true", user=101, method="PUT")
        assert self.data("/follow/or/not/102", user=101) is True
        assert self.sql("SELECT COUNT(*) FROM tb_follow WHERE user_id=101 AND follow_user_id=102;").stdout.strip() == "1"
        common = self.data("/follow/common/103", user=101)
        assert [u["id"] for u in common] == [102]
        assert all("phone" not in u and "password" not in u for u in common)
        self.check("Follow, duplicate follow and common follows use consistent SQL and Redis data")

        ids = []
        for number in range(7):
            blog_id = self.data("/blog", user=102, method="POST", payload=dict(
                shopId=101, userId=101, title="fixture%d" % number, images="fixture.png",
                content="fixture", liked=0, comments=0))
            ids.append(blog_id)
        assert self.redis.command("ZCARD", "feed:101") == 7
        assert self.redis.command("ZCARD", "feed:103") == 7
        assert self.redis.command("ZCARD", "feed:102") == 0
        assert self.sql("SELECT COUNT(*) FROM tb_blog WHERE user_id=102;").stdout.strip() == "7"
        self.check("Publishing uses authenticated author and fans receive all seven posts")

        ordered = list(reversed(ids))
        # 固定分数，稳定覆盖相同时间戳跨多页；不依赖请求碰巧落在同一毫秒。
        for blog_id, score in zip(ordered, (2000, 1000, 1000, 1000, 1000, 1000, 900)):
            self.redis.command("ZADD", "feed:101", score, blog_id)
        seen = []
        cursor, offset = 3000, 0
        pages = 0
        while pages < 10:
            page = self.data("/blog/of/follow?lastId=%d&offset=%d" % (cursor, offset), user=101)
            seen.extend(blog["id"] for blog in page["list"])
            assert all(blog["name"] == "fixture102" for blog in page["list"])
            next_cursor = (page["minTime"], page["offset"])
            pages += 1
            if next_cursor == (cursor, offset):
                break
            cursor, offset = next_cursor
        assert seen == ordered and len(set(seen)) == 7, seen
        assert self.api("/blog/of/follow", user=101)[0] == 400
        assert self.api("/blog/of/follow?lastId=bad", user=101)[0] == 400
        assert self.api("/blog/of/follow?lastId=-1", user=101)[1].get("success") is False
        assert self.api("/blog/of/follow?lastId=3000&offset=-1", user=101)[1].get("success") is False
        self.check("Feed HTTP cursor pagination has no duplicates or omissions across equal timestamps", pages=pages)

        newest = ordered[0]
        self.data("/blog/like/" + str(newest), user=101, method="PUT")
        detail = self.data("/blog/" + str(newest), user=101)
        assert detail["liked"] == 1 and detail["isLike"] is True and detail["userId"] == 102
        assert self.data("/blog/of/follow?lastId=3000", user=101)["list"][0]["isLike"] is True
        assert [u["id"] for u in self.data("/blog/likes/" + str(newest), user=101)] == [101]
        self.data("/blog/like/" + str(newest), user=101, method="PUT")
        detail = self.data("/blog/" + str(newest), user=101)
        assert detail["liked"] == 0 and detail["isLike"] is False
        self.check("Like, unlike, liker list and feed viewer state agree with persisted counts")

        self.data("/follow/102/false", user=101, method="PUT")
        assert self.data("/follow/or/not/102", user=101) is False
        assert self.data("/follow/common/103", user=101) == []
        self.data("/blog", user=102, method="POST", payload=dict(
            shopId=101, title="after unfollow", images="fixture.png", content="fixture", liked=0, comments=0))
        assert self.redis.command("ZCARD", "feed:101") == 7
        assert self.redis.command("ZCARD", "feed:103") == 8
        self.check("Unfollow stops delivery of future posts while other fans still receive them")

        assert self.data("/user/sign/count", user=101) == 0
        self.data("/user/sign", user=101, method="POST")
        self.data("/user/sign", user=101, method="POST")
        assert self.data("/user/sign/count", user=101) == 1
        assert self.data("/user/sign/count", user=103) == 0
        day = datetime.datetime.now(datetime.timezone.utc).astimezone(
            datetime.timezone(datetime.timedelta(hours=8))).date()
        key = "sign:101:" + day.strftime("%Y%m") + ":"
        assert self.redis.command("GETBIT", key, day.day - 1) == 1
        for offset in range(day.day):
            self.redis.command("SETBIT", key, offset, 1)
        assert self.data("/user/sign/count", user=101) == day.day
        self.redis.command("SETBIT", key, day.day - 1, 0)
        assert self.data("/user/sign/count", user=101) == 0
        self.check("Sign-in is idempotent and user-isolated; consecutive count requires today")

        assert self.data("/shop/101")["name"] == "fixture shop"
        assert self.redis.command("EXISTS", "cache:shop:logic101") == 1
        self.data("/shop", method="PUT", payload=dict(id=101, name="updated fixture"))
        assert self.redis.command("EXISTS", "cache:shop:logic101") == 0
        assert self.data("/shop/101")["name"] == "updated fixture"
        assert self.api("/shop/999999")[1].get("success") is False
        assert self.redis.command("EXISTS", "cache:shop:logic999999") == 1
        assert self.redis.command("TTL", "cache:shop:logic999999") > 0
        assert self.data("/shop/of/type?typeId=901&x=120&y=30")[0]["id"] == 101
        self.check("Shop detail uses generic cache, update invalidation, negative caching and GEO")

        self.data("/user/logout", user=101, method="POST")
        assert self.api("/user/me", user=101)[0] == 401
        self.check("Logout invalidates authentication token")
        after = self.source_counts()
        assert self.before == after
        self.check("Development table row counts unchanged")
        report = dict(status="PASS", database=self.db, app_port=self.ports[0],
                      development_counts_before=self.before, development_counts_after=after, cases=self.cases)
        (base.REPORT_DIR / "results.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        print("ALL PASS: " + str(base.REPORT_DIR / "results.json"), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True)
    parser.add_argument("--mysql", required=True)
    parser.add_argument("--redis", required=True)
    parser.add_argument("--classpath-report", type=Path, default=base.ROOT /
                        "target/surefire-reports/TEST-com.hmdp.service.impl.UserSignServiceTest.xml")
    fixture = PracticeAcceptance(parser.parse_args())
    try:
        fixture.run()
    except BaseException as failure:
        (base.REPORT_DIR / "failure.json").write_text(json.dumps(dict(database=fixture.db,
            error=str(failure), cases=fixture.cases), indent=2), encoding="utf-8")
        raise
    finally:
        fixture.close()
