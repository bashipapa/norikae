#!/usr/bin/env python3
"""
平日の帰宅用時刻表（data/timetable.json）を Yahoo!路線情報から作り直す。

- 山陽 荒井発（16時以降）・山陽明石発（21時以降）（大阪梅田・新開地方面）：各列車の停車駅一覧から新開地着を出す。
  新開地まで行かない列車は、終点で乗り換える次の電車を調べて「乗り換え後の新開地着」を入れる
- 神戸電鉄 新開地発（三田・粟生方面）16時以降：北鈴蘭台着・鈴蘭台止まりの鈴蘭台着。
  粟生線の電車が鈴蘭台で三田方面に接続する場合は、その北鈴蘭台着を入れる
- 北鈴蘭台発 新開地方面 16時以降（乗り過ごし時の戻り案内）

内容が変わったときだけファイルを書き換える（取得日だけの変更では書き換えない）。
取得結果がおかしいとき（本数が極端に少ない等）は書き換えずに終了コード1で終わる。

使い方: python3 tools/update_timetable.py [--dry-run]
標準ライブラリだけで動く（GitHub Actions でそのまま実行できる）。
"""
import datetime
import html
import json
import re
import sys
import time
import urllib.request
from collections import OrderedDict
from pathlib import Path

BASE = "https://transit.yahoo.co.jp"
ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "data" / "timetable.json"
KIND_WEEKDAY = 1

# Yahoo!路線情報の駅コード・方面コード
ARAI, AKASHI, SHINKAICHI, KITA_SUZURANDAI = 26264, 26377, 26399, 26332
SANYO_UP = 5920          # 山陽 大阪梅田・新開地方面
SHINTETSU_DOWN = 5771    # 神戸電鉄 新開地 → 三田・粟生方面
SHINTETSU_UP_KITA = 5820  # 神戸電鉄 北鈴蘭台 → 新開地・鈴蘭台方面

# 取り始める時刻（分）。荒井は夕方の直通特急（16:57発〜）も入れるため16時から
ARAI_FROM = 16 * 60
AKASHI_FROM = 21 * 60
SHINTETSU_FROM = 16 * 60
KITA_UP_FROM = 16 * 60

TYPE_LABELS = ("普通", "準急", "急行", "特快速", "快速", "直通特急", "特急", "Ｓ特急", "S特急", "阪神特急")

_cache = {}


def get(path):
    if path in _cache:
        return _cache[path]
    last = None
    for attempt in range(4):
        try:
            time.sleep(1.0)  # サイトに負担をかけないよう1秒あける
            req = urllib.request.Request(BASE + path, headers={"User-Agent": "norikae-timetable-updater"})
            with urllib.request.urlopen(req, timeout=30) as r:
                text = r.read().decode("utf-8")
            _cache[path] = text
            return text
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(2 ** attempt)
    raise RuntimeError(f"取得に失敗: {path}: {last}")


def lines(s):
    t = re.sub(r"<(script|style).*?</\1>", "", s, flags=re.S)
    t = re.sub(r"<[^>]+>", "\n", t)
    t = html.unescape(t)
    return [l.strip() for l in t.split("\n") if l.strip()]


def svc(t):
    """'HH:MM' → 営業日基準の分（4時前は+24時間）"""
    h, m = map(int, t.split(":"))
    v = h * 60 + m
    return v + 1440 if h < 4 else v


def fmt(v):
    return "%d:%02d" % (v // 60, v % 60)


def parse_train(s):
    """列車ページ → (行先, 種別, [(駅名, 着, 発, 駅コード)])"""
    L = lines(s)
    hi = next((i for i, l in enumerate(L) if "発→" in l and l.endswith("行き")), None)
    if hi is None:
        raise ValueError("列車ページの形式が想定と違います（行先が見つからない）")
    dest = L[hi].split("→")[-1].removesuffix("行き")
    ttype = next((l for l in L[hi + 1:hi + 12] if l in TYPE_LABELS), None)
    stops = []
    for code, name, arr, dep in re.findall(
        r'\{"stationCode":"(\d+)","stationName":"([^"]+)","areaCode":"[^"]*","prefCode":"[^"]*",'
        r'"arrivalTime":(null|"\d+"),"departureTime":(null|"\d+")\}', s):
        f = lambda v: None if v == "null" else "%s:%s" % (v.strip('"')[:-2], v.strip('"')[-2:])
        stops.append((name, f(arr), f(dep), code))
    if not stops:
        raise ValueError("列車ページの形式が想定と違います（停車駅が見つからない）")
    return dest, (ttype or "").replace("Ｓ", "S"), stops


def train_links(s, hours):
    out = []
    for path, hh, mm in re.findall(r'href="(/timetable/\d+/\d+/\d+\?kind=\d+&amp;hh=(\d+)&amp;mm=(\d+))"', s):
        hh, mm = int(hh), int(mm)
        if hh in hours:
            out.append((hh * 60 + mm, path.replace("&amp;", "&")))
    return sorted(set(out))


def stop(stops, name):
    return next((x for x in stops if x[0] == name), None)


def short(name):
    return re.sub(r"[(（].*?[)）]", "", name)


# ---------------------------------------------------------------- 山陽

def sanyo_from(code, origin_name, from_min):
    tt = get(f"/timetable/{code}/{SANYO_UP}?kind={KIND_WEEKDAY}")
    res = []
    for dep_min, path in train_links(tt, range(from_min // 60, 25)):
        if dep_min < from_min:
            continue
        dest, ttype, stops = parse_train(get(path))
        o = stop(stops, origin_name)
        e = OrderedDict([("dep", fmt(svc(o[2]))), ("type", ttype), ("dest", dest)])
        sk = stop(stops, "新開地")
        if sk:
            e["shinkaichi"] = fmt(svc(sk[1] or sk[2]))
            res.append(e)
            continue
        # 新開地まで行かない → 終点で次の電車に乗り換え
        last = stops[-1]
        t_arr = svc(last[1])
        e["transferAt"] = last[0]
        e["transferArr"] = fmt(t_arr)
        st_tt = get(f"/timetable/{last[3]}/{SANYO_UP}?kind={KIND_WEEKDAY}")
        found = None
        for c_min, c_path in train_links(st_tt, range(0, 25)):
            cm = c_min + (1440 if c_min < 240 else 0)
            if cm < t_arr + 1:
                continue
            d2, t2, s2 = parse_train(get(c_path))
            sk2 = stop(s2, "新開地")
            if sk2:
                x = stop(s2, last[0])
                found = (fmt(svc(x[2])), d2, fmt(svc(sk2[1] or sk2[2])), t2)
                break
        if found:
            e["transferDep"] = found[0]
            e["transferTrainType"] = found[3]
            e["transferTrainDest"] = found[1]
            e["shinkaichi"] = found[2]
            e["transferNote"] = f"{last[0]} {fmt(t_arr)}着 → {found[0]}発 {found[3]} {found[1]}行きに乗り換え"
        else:
            e["shinkaichi"] = None
            e["transferNote"] = f"{last[0]} {fmt(t_arr)}着。新開地へ行く電車なし"
        res.append(e)
    return res


# ---------------------------------------------------------------- 神戸電鉄

def shintetsu_down():
    tt = get(f"/timetable/{SHINKAICHI}/{SHINTETSU_DOWN}?kind={KIND_WEEKDAY}")
    trains = []
    for dep_min, path in train_links(tt, range(0, 25)):
        dm = dep_min if dep_min >= 240 else dep_min + 1440
        if dm < SHINTETSU_FROM:
            continue
        dest, ttype, stops = parse_train(get(path))
        sz, kt = stop(stops, "鈴蘭台"), stop(stops, "北鈴蘭台")
        trains.append({
            "dep": dm,
            "type": ttype, "dest": short(dest),
            "suzuArr": svc(sz[1]) if sz and sz[1] else None,
            "suzuDep": svc(sz[2]) if sz and sz[2] else None,
            "kitaArr": svc(kt[1]) if kt and kt[1] else None,
        })
    trains.sort(key=lambda x: x["dep"])
    res = []
    for t in trains:
        e = OrderedDict([("dep", fmt(t["dep"])), ("type", t["type"]), ("dest", t["dest"])])
        if t["kitaArr"] is not None:
            e["kitaSuzurandai"] = fmt(t["kitaArr"])
        else:
            # 粟生線など。先に出た三田方面の電車に鈴蘭台で接続（5分以内）していれば、その北鈴蘭台着
            conn = None
            if t["suzuArr"] is not None and t["dest"] != "鈴蘭台":
                conn = next((c for c in trains
                             if c["dep"] < t["dep"] and c["kitaArr"] is not None and c["suzuDep"] is not None
                             and t["suzuArr"] <= c["suzuDep"] <= t["suzuArr"] + 5), None)
            if conn:
                e["kitaSuzurandai"] = fmt(conn["kitaArr"])
                e["transferAtSuzurandai"] = True
                e["note"] = f"鈴蘭台で{conn['dest']}行きに乗り換え"
            else:
                e["kitaSuzurandai"] = None
                if t["dest"] == "鈴蘭台":
                    e["note"] = "鈴蘭台止まり（北鈴蘭台へは行かない）。鈴蘭台からタクシー"
                    e["suzurandai"] = fmt(t["suzuArr"])
                else:
                    e["note"] = "粟生線。北鈴蘭台に行かない"
        res.append(e)
    return res


def kita_up():
    tt = get(f"/timetable/{KITA_SUZURANDAI}/{SHINTETSU_UP_KITA}?kind={KIND_WEEKDAY}")
    res = []
    for dep_min, path in train_links(tt, range(0, 25)):
        dm = dep_min if dep_min >= 240 else dep_min + 1440
        if dm < KITA_UP_FROM:
            continue
        dest, _, _ = parse_train(get(path))
        res.append((dm, OrderedDict([("dep", fmt(dm)), ("dest", short(dest))])))
    return [e for _, e in sorted(res, key=lambda x: x[0])]


# ---------------------------------------------------------------- 検証・書き出し

def validate(new, old):
    problems = []

    def check_list(name, cur, prev):
        if not cur:
            problems.append(f"{name} が空")
            return
        times = [svc(e["dep"]) for e in cur]
        if times != sorted(times):
            problems.append(f"{name} の発車時刻が並んでいない")
        if prev and len(cur) < len(prev) * 0.5:
            problems.append(f"{name} の本数が前回の半分未満（{len(prev)} → {len(cur)}）")

    check_list("山陽 荒井発", new["sanyo"]["weekday"], old.get("sanyo", {}).get("weekday"))
    check_list("山陽 山陽明石発", new["sanyoAkashi"]["weekday"], old.get("sanyoAkashi", {}).get("weekday"))
    check_list("神戸電鉄 新開地発", new["shintetsu"]["weekday"], old.get("shintetsu", {}).get("weekday"))
    check_list("北鈴蘭台発 新開地方面", new["kitaSuzurandaiUp"]["weekday"], old.get("kitaSuzurandaiUp", {}).get("weekday"))
    if not any(e.get("kitaSuzurandai") for e in new["shintetsu"]["weekday"]):
        problems.append("北鈴蘭台に行く神戸電鉄が1本もない")
    for key in ("sanyo", "sanyoAkashi"):
        if not any(e.get("shinkaichi") for e in new[key]["weekday"]):
            problems.append(f"{key} に新開地へ行く電車が1本もない")
    return problems


def dump(o, ind=0):
    """1列車1行の読みやすい JSON"""
    pad = "  " * ind
    if isinstance(o, dict):
        if all(not isinstance(v, (dict, list)) for v in o.values()) and ind >= 2:
            return json.dumps(o, ensure_ascii=False)
        items = [f'{pad}  {json.dumps(k, ensure_ascii=False)}: {dump(v, ind + 1)}' for k, v in o.items()]
        return "{\n" + ",\n".join(items) + f"\n{pad}}}"
    if isinstance(o, list):
        if all(isinstance(x, str) for x in o):
            return "[\n" + ",\n".join(f"{pad}  {json.dumps(x, ensure_ascii=False)}" for x in o) + f"\n{pad}]"
        return "[\n" + ",\n".join(f"{pad}  {dump(x, ind + 1)}" for x in o) + f"\n{pad}]"
    return json.dumps(o, ensure_ascii=False)


def main():
    dry = "--dry-run" in sys.argv
    old = json.loads(OUT.read_text(encoding="utf-8"), object_pairs_hook=OrderedDict)

    data = {
        "sanyo": {"weekday": sanyo_from(ARAI, "荒井", ARAI_FROM)},
        "sanyoAkashi": {"weekday": sanyo_from(AKASHI, "山陽明石", AKASHI_FROM)},
        "shintetsu": {"weekday": shintetsu_down()},
        "kitaSuzurandaiUp": {
            "description": old.get("kitaSuzurandaiUp", {}).get("description", "北鈴蘭台発 新開地方面。乗り過ごして山の街側から戻るときの目安"),
            "weekday": kita_up(),
        },
    }

    problems = validate(data, old)
    if problems:
        print("取得結果に問題があるため書き換えません:", *problems, sep="\n- ", file=sys.stderr)
        return 1

    changed = [k for k in data if json.dumps(data[k], ensure_ascii=False) != json.dumps(old.get(k), ensure_ascii=False)]
    if not changed:
        print("時刻表に変更はありません")
        return 0
    print("変更あり:", ", ".join(changed))

    new = OrderedDict()
    for k, v in old.items():
        new[k] = data.get(k, v)
    for k in data:
        if k not in new:
            new[k] = data[k]
    meta = new.setdefault("meta", OrderedDict())
    today = datetime.date.today().isoformat()
    meta["sanyoRetrievedAt"] = today
    meta["shintetsuRetrievedAt"] = today
    meta["autoUpdate"] = "tools/update_timetable.py が週1回 Yahoo!路線情報から更新（.github/workflows/update-timetable.yml）"
    if dry:
        print(dump(new))
        return 0
    OUT.write_text(dump(new) + "\n", encoding="utf-8")
    print(f"{OUT} を更新しました")
    return 0


if __name__ == "__main__":
    sys.exit(main())
