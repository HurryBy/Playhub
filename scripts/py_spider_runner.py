import importlib.util
import json
import pathlib
import re
import sys
import traceback


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")


def load_payload():
    raw = sys.stdin.read()
    return json.loads(raw or "{}")


def call_with_fallback(fn, *variants):
    last_error = None
    for args in variants:
        try:
            return fn(*args)
        except TypeError as exc:
            last_error = exc
    if last_error:
        raise last_error
    return fn()


def normalize_result(value):
    if value is None:
        return {}
    if isinstance(value, (dict, list, str, int, float, bool)):
        return value
    return str(value)

def install_bs4_parser_fallback():
    try:
        import bs4

        original_beautiful_soup = bs4.BeautifulSoup

        class PatchedBeautifulSoup(original_beautiful_soup):
            def __init__(self, markup="", features=None, builder=None, **kwargs):
                requested = features
                if isinstance(requested, (list, tuple)) and requested:
                    requested = requested[0]
                try:
                    super().__init__(markup, features, builder, **kwargs)
                except bs4.FeatureNotFound:
                    if requested in ("lxml", "lxml-xml", "xml"):
                        super().__init__(markup, "html.parser", builder, **kwargs)
                        return
                    raise

        bs4.BeautifulSoup = PatchedBeautifulSoup
    except Exception:
        pass


def main():
    payload = load_payload()
    runtime_dir = pathlib.Path(__file__).resolve().parent / "python_runtime"
    sys.path.insert(0, str(runtime_dir))
    install_bs4_parser_fallback()

    script_path = pathlib.Path(payload["scriptPath"]).resolve()
    spec = importlib.util.spec_from_file_location("tvbox_py_spider", script_path)
    module = importlib.util.module_from_spec(spec)
    module.__dict__.setdefault("json", json)
    module.__dict__.setdefault("pathlib", pathlib)
    module.__dict__.setdefault("re", re)
    module.__dict__.setdefault("sys", sys)
    spec.loader.exec_module(module)

    SpiderClass = getattr(module, "Spider")
    spider = SpiderClass()
    spider.proxyUrl = payload.get("proxyUrl", "")
    spider.siteKey = payload.get("siteKey", "")

    if hasattr(spider, "init"):
        try:
            spider.init(payload.get("ext", ""))
        except TypeError:
            spider.init()

    action = payload.get("action")
    args = payload.get("args", {})

    if action == "home":
        result = call_with_fallback(spider.homeContent, (bool(args.get("filter", True)),))
    elif action == "category":
        extend = args.get("extend") or {}
        result = call_with_fallback(
            spider.categoryContent,
            (args.get("tid", ""), args.get("pg", "1"), bool(args.get("filter", True)), extend),
        )
    elif action == "detail":
        result = call_with_fallback(spider.detailContent, ([args.get("id", "")],), (args.get("id", ""),))
    elif action == "search":
        result = call_with_fallback(
            spider.searchContent,
            (args.get("wd", ""), bool(args.get("quick", False)), "1"),
            (args.get("wd", ""), bool(args.get("quick", False))),
        )
    elif action == "play":
        result = call_with_fallback(
            spider.playerContent,
            (args.get("flag", ""), args.get("id", ""), args.get("vipFlags", [])),
            (args.get("flag", ""), args.get("id", "")),
        )
    else:
        raise RuntimeError(f"Unsupported action: {action}")

    normalized = normalize_result(result)
    if isinstance(normalized, str):
        try:
            json.loads(normalized)
            sys.stdout.write(normalized)
        except Exception:
            sys.stdout.write(json.dumps({"raw": normalized}, ensure_ascii=False))
    else:
        sys.stdout.write(json.dumps(normalized, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except Exception:
        traceback.print_exc()
        sys.exit(1)
