#!/usr/bin/env python3
"""Seeds a fresh logb server (port 8090, user ben / "correct horse" created via /auth/setup) with the
tree the bootstrap fixture and the contract test expect, and writes the bootstrap snapshot.
Usage: seed-server.py <dir holding photo.png> <fixture output path>"""
import json, sys, urllib.request, http.cookiejar, uuid
S = sys.argv[1]; U = "http://localhost:8090/api"
jar = http.cookiejar.CookieJar(); opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
def req(method, path, body=None, raw=None, ctype="application/json"):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    r = urllib.request.Request(U + path, data=data, method=method, headers={"content-type": ctype} if data else {})
    with opener.open(r) as resp: t = resp.read()
    return json.loads(t) if t else None
def post(path, body): return req("POST", path, body)
r = req("POST", "/auth/login", {"username": "ben", "password": "correct horse"})
house = post("/objects", {"name": "House", "type": "home", "description": "Built 1998"})["id"]
garage = post("/objects", {"name": "Garage", "type": "home", "parent_id": house})["id"]
light = post("/objects", {"name": "Main light", "type": "appliance", "parent_id": garage})["id"]
golf = post("/objects", {"name": "Golf", "type": "car", "counter_unit": "km", "fuel_unit": "l", "purchase_date": "2019-03-14", "purchase_price_cents": 1850000})["id"]
a1 = post(f"/objects/{golf}/activities", {"date": "2026-03-01", "category": "maintenance", "title": "Oil change", "counter_value": 84210, "cost_cents": 18900, "notes": "5W-30"})["id"]
a2 = post(f"/objects/{golf}/activities", {"date": "2026-06-10", "category": "fuel", "title": "Fuel", "counter_value": 86000, "cost_cents": 7250, "quantity_milli": 42300})["id"]
post(f"/objects/{light}/activities", {"date": "2026-08-20", "category": "repair", "title": "Bulb replaced", "cost_cents": 499})
png = open(S + "/photo.png", "rb").read()
b = uuid.uuid4().hex.encode()
parts = b"".join([
    b"--" + b + b"\r\nContent-Disposition: form-data; name=\"activity_id\"\r\n\r\n" + str(a1).encode() + b"\r\n",
    b"--" + b + b"\r\nContent-Disposition: form-data; name=\"caption\"\r\n\r\nReceipt\r\n",
    b"--" + b + b"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"photo.png\"\r\nContent-Type: image/png\r\n\r\n" + png + b"\r\n",
    b"--" + b + b"--\r\n"])
att = req("POST", f"/objects/{golf}/attachments", raw=parts, ctype="multipart/form-data; boundary=" + b.decode())["id"]
req("PATCH", f"/objects/{golf}", {"name": "Golf", "type": "car", "counter_unit": "km", "fuel_unit": "l", "purchase_date": "2019-03-14", "purchase_price_cents": 1850000, "cover_attachment_id": att})
r1 = post(f"/objects/{golf}/reminders", {"title": "Oil change", "due_date": "2026-03-01", "due_counter": 84000, "repeat_months": 12, "repeat_counter": 15000})["id"]
post(f"/reminders/{r1}/done", {"activity_id": a1})
post(f"/objects/{golf}/reminders", {"title": "Inspection", "due_date": "2026-10-01"})
post(f"/objects/{golf}/reminders", {"title": "Monthly reading", "kind": "reading", "due_date": "2026-01-01", "every_n": 1, "every_unit": "month"})
boot = req("GET", "/sync/bootstrap")
json.dump(boot, open(sys.argv[2], "w"), indent=2)
print({k: len(v) for k, v in boot.items() if isinstance(v, list)}, "seq", boot["seq"])
print("ids", dict(house=house, garage=garage, light=light, golf=golf, a1=a1, a2=a2, att=att, r1=r1))
