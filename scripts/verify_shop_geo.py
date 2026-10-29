"""Exercise nearby shops through real HTTP, Redis GEO and MySQL.

Creates a private database and a temporary Redis process. No application requests
or writes target the development database. Test DB and reports are retained;
only owned processes are stopped, and temporary credential files are removed.
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


class GeoAcceptance(base.Acceptance):
    def __init__(self, args):
        stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S_") + uuid.uuid4().hex[:6]
        base.REPORT_DIR = base.ROOT / "target/shop-geo-acceptance" / stamp
        super().__init__(args)
        self.db = "hmdp_geo_verify_" + stamp

    def api(self, path, method="GET", payload=None):
        headers = {}
        if payload is not None:
            headers["Content-Type"] = "application/json"
        data = json.dumps(payload).encode("utf-8") if payload is not None else (b"" if method == "POST" else None)
        request = urllib.request.Request("http://127.0.0.1:%d%s" % (self.ports[0], path),
                                         data=data, headers=headers, method=method)
        try:
            with base.LOCAL_HTTP.open(request, timeout=15) as response:
                return response.status, json.loads(response.read())
        except urllib.error.HTTPError as error:
            body = error.read()
            return error.code, json.loads(body) if body else {}

    def data(self, path, **kwargs):
        status, result = self.api(path, **kwargs)
        assert status == 200 and result.get("success") is True, (path, status, result)
        return result.get("data")

    def check(self, name, **details):
        self.cases.append(dict(name=name, status="PASS", **details))
        print("PASS: " + name, flush=True)

    def prepare_geo(self):
        self.before = self.sql("SELECT COUNT(*) FROM tb_shop;",
                               self.source_db).stdout.strip()
        self.sql("CREATE DATABASE `" + self.db + "` CHARACTER SET utf8mb4;", self.source_db)
        for table in ("tb_shop",):
            self.sql("CREATE TABLE " + table + " LIKE `" + self.source_db + "`." + table + ";")
        for number in range(1, 9):
            longitude = 120 + (number - 1) * .001 if number < 8 else 121
            self.sql("INSERT INTO tb_shop(id,name,type_id,images,address,x,y,sold,comments,score) "
                     "VALUES(%d,'fixture%d',901,'fixture.png','fixture',%s,30,0,0,40);"
                     % (100 + number, number, longitude))
        self.sql("INSERT INTO tb_shop(id,name,type_id,images,address,x,y,sold,comments,score) "
                 "VALUES(201,'other type',902,'fixture.png','fixture',120,30,0,0,40);")
        redis_port = base.free_port()
        self.redis = base.Redis(redis_port)
        self.spawn([self.args.redis, "--bind", "127.0.0.1", "--port", str(redis_port),
                    "--save", "", "--appendonly", "no", "--dir", str(base.REPORT_DIR)], "redis")
        deadline = time.monotonic() + 15
        while True:
            try:
                if self.redis.command("PING") == "PONG":
                    break
            except OSError:
                pass
            if time.monotonic() > deadline:
                raise RuntimeError("Temporary Redis did not start")
            time.sleep(.1)
        properties = ET.parse(self.args.classpath_report).getroot().find("properties")
        classpath = next(prop.attrib["value"] for prop in properties if prop.attrib["name"] == "java.class.path")
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
        print("READY: isolated HTTP + MySQL + Redis", flush=True)

    def run(self):
        self.prepare_geo()
        path = "/shop/of/type?typeId=901&x=120&y=30&current="
        first, second = self.data(path + "1"), self.data(path + "2")
        assert [shop["id"] for shop in first] == [101, 102, 103, 104, 105]
        assert [shop["id"] for shop in second] == [106, 107]
        assert all(first[i]["distance"] < first[i + 1]["distance"] for i in range(4))
        assert self.data(path + "3") == []
        self.check("GEO automatic import, distance order, five-kilometer filter and pagination")
        assert [shop["id"] for shop in self.data("/shop/of/type?typeId=902&x=120&y=30")] == [201]
        self.check("GEO type isolation")
        assert [shop["id"] for shop in self.data("/shop/of/type?typeId=901&current=2")] == [106, 107, 108]
        self.check("Existing database pagination without coordinates")
        assert self.api("/shop/of/type?typeId=901&x=120")[1].get("success") is False
        assert self.api("/shop/of/type?typeId=901&x=181&y=30")[1].get("success") is False
        self.check("Invalid coordinates rejected")
        added = self.data("/shop", method="POST", payload=dict(name="new fixture", typeId=901,
                          images="fixture.png", address="fixture", x=120.007, y=30, sold=0, comments=0, score=40))
        assert [shop["id"] for shop in self.data(path + "2")] == [106, 107, added]
        self.check("New shop invalidates and rebuilds the complete type index", new_shop_id=added)
        self.data("/shop", method="PUT", payload=dict(id=101, typeId=902, x=120.008, y=30))
        assert 101 not in [shop["id"] for shop in self.data(path + "1")]
        assert [shop["id"] for shop in self.data("/shop/of/type?typeId=902&x=120&y=30")] == [201, 101]
        self.check("Shop type and coordinate update invalidates old and new indexes")
        after = self.sql("SELECT COUNT(*) FROM tb_shop;",
                         self.source_db).stdout.strip()
        assert self.before == after
        self.check("Development shop row count unchanged")
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
                        "target/surefire-reports/TEST-com.hmdp.service.impl.ShopGeoServiceTest.xml")
    fixture = GeoAcceptance(parser.parse_args())
    try:
        fixture.run()
    except BaseException as failure:
        (base.REPORT_DIR / "failure.json").write_text(json.dumps(dict(database=fixture.db,
            error=str(failure), cases=fixture.cases), indent=2), encoding="utf-8")
        raise
    finally:
        fixture.close()
