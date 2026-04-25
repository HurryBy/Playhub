from base64 import b64decode, b64encode

import requests


requests.packages.urllib3.disable_warnings()


class Spider:
    proxyUrl = "http://127.0.0.1:18080/proxy"

    def init(self, extend=""):
        return None

    def getName(self):
        return ""

    def isVideoFormat(self, url):
        return False

    def manualVideoCheck(self):
        return False

    def homeContent(self, filter):
        return {}

    def homeVideoContent(self):
        return {}

    def categoryContent(self, tid, pg, filter, extend):
        return {}

    def detailContent(self, ids):
        return {}

    def searchContent(self, key, quick, pg="1"):
        return {}

    def playerContent(self, flag, id, vipFlags=None):
        return {}

    def fetch(self, url, method="GET", headers=None, params=None, data=None, json=None, cookies=None,
              allow_redirects=True, timeout=10, **kwargs):
        request_method = (method or "GET").upper()
        return requests.request(
            request_method,
            url,
            headers=headers,
            params=params,
            data=data,
            json=json,
            cookies=cookies,
            allow_redirects=allow_redirects,
            timeout=timeout,
            verify=False,
            **kwargs,
        )

    def post(self, url, data=None, json=None, headers=None, timeout=10, **kwargs):
        return self.fetch(url, method="POST", data=data, json=json, headers=headers, timeout=timeout, **kwargs)

    def getProxyUrl(self):
        return getattr(self, "proxyUrl", self.proxyUrl)

    def e64(self, text):
        return b64encode((text or "").encode("utf-8")).decode("utf-8")

    def d64(self, text):
        return b64decode((text or "").encode("utf-8")).decode("utf-8")
